package com.leomaradan.mapgen.client;

import com.leomaradan.mapgen.core.MapPack;
import com.leomaradan.mapgen.core.MapPackReader;
import com.leomaradan.mapgen.core.ValidationReport;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * "Customize" screen of the Map from ZIP world type: pick a ZIP (path, file dialog, drag and drop
 * or the {@code .minecraft/mapgen} folder), see the validation report, apply it.
 */
public final class SelectMapScreen extends Screen {
    private static final int MAX_LISTED = 6;
    private static final long VALIDATION_DELAY_MS = 300;

    private final CreateWorldScreen parent;
    private final WorldCreationContext context;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private EditBox pathBox;
    private MultiLineTextWidget status;
    private Button doneButton;

    private String pendingPath = "";
    private long pendingSince;
    private int validationGeneration;
    private @Nullable MapPack validPack;

    public SelectMapScreen(CreateWorldScreen parent, WorldCreationContext context) {
        super(Component.translatable("mapgen.screen.title"));
        this.parent = parent;
        this.context = context;
    }

    @Override
    protected void init() {
        this.layout.addTitleHeader(this.title, this.font);
        LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(6));
        content.defaultCellSetting().alignHorizontallyCenter();

        LinearLayout pathRow = content.addChild(LinearLayout.horizontal().spacing(4));
        this.pathBox = pathRow.addChild(new EditBox(this.font, 226, 20, Component.translatable("mapgen.screen.path")));
        this.pathBox.setMaxLength(4096);
        this.pathBox.setHint(Component.translatable("mapgen.screen.path.hint"));
        Button browse = pathRow.addChild(Button.builder(Component.translatable("mapgen.screen.browse"), b -> this.browse()).width(80).build());
        browse.setTooltip(Tooltip.create(Component.translatable("mapgen.screen.browse.tooltip")));

        LinearLayout folderRow = content.addChild(LinearLayout.horizontal().spacing(4));
        List<Path> zips = ClientMaps.listZips();
        if (!zips.isEmpty()) {
            CycleButton<Path> picker = CycleButton.builder((Path p) -> Component.literal(p.getFileName().toString()), zips.getFirst())
                    .withValues(zips)
                    .create(0, 0, 226, 20, Component.translatable("mapgen.screen.folder_maps"), (button, path) -> this.pathBox.setValue(path.toString()));
            folderRow.addChild(picker);
        } else {
            folderRow.addChild(Button.builder(Component.translatable("mapgen.screen.folder_empty"), b -> {
            }).width(226).build()).active = false;
        }
        folderRow.addChild(Button.builder(Component.translatable("mapgen.screen.open_folder"), b -> this.openMapsFolder()).width(80).build());

        this.status = content.addChild(new MultiLineTextWidget(Component.translatable("mapgen.screen.help", ClientMaps.mapsFolder().toString()), this.font)
                .setMaxWidth(310).setMaxRows(14));

        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        this.doneButton = footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> this.apply()).build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose()).build());
        this.doneButton.active = false;

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();

        // Responder set last so restoring the previous value triggers a validation.
        this.pathBox.setResponder(this::onPathChanged);
        MapPack previous = ClientMaps.selected();
        if (previous != null) {
            this.pathBox.setValue(previous.source().toString());
        } else if (zips.size() == 1) {
            this.pathBox.setValue(zips.getFirst().toString());
        }
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        files.stream()
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                .findFirst()
                .ifPresent(p -> this.pathBox.setValue(p.toString()));
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.pendingPath.isEmpty() && Util.getMillis() - this.pendingSince >= VALIDATION_DELAY_MS) {
            String path = this.pendingPath;
            this.pendingPath = "";
            this.validate(path);
        }
    }

    private void onPathChanged(String value) {
        this.validPack = null;
        this.doneButton.active = false;
        this.validationGeneration++;
        String path = stripQuotes(value.trim());
        if (path.isEmpty()) {
            this.pendingPath = "";
            this.setStatus(Component.translatable("mapgen.screen.help", ClientMaps.mapsFolder().toString()));
            return;
        }
        this.pendingPath = path;
        this.pendingSince = Util.getMillis();
    }

    private void validate(String text) {
        Path path;
        try {
            path = Path.of(text);
        } catch (InvalidPathException e) {
            this.setStatus(Component.translatable("mapgen.screen.invalid_path").withStyle(ChatFormatting.RED));
            return;
        }
        if (!Files.isRegularFile(path)) {
            this.setStatus(Component.translatable("mapgen.screen.not_found").withStyle(ChatFormatting.RED));
            return;
        }
        int generation = this.validationGeneration;
        this.setStatus(Component.translatable("mapgen.screen.validating").withStyle(ChatFormatting.GRAY));
        CompletableFuture.supplyAsync(() -> MapPackReader.read(path), Util.backgroundExecutor())
                .whenCompleteAsync((result, error) -> {
                    if (generation != this.validationGeneration) {
                        return;
                    }
                    if (error != null) {
                        this.setStatus(Component.literal(error.getMessage()).withStyle(ChatFormatting.RED));
                        return;
                    }
                    this.showResult(result);
                }, this.minecraft);
    }

    private void showResult(MapPackReader.Result result) {
        ValidationReport report = result.report();
        if (result.ok()) {
            ClientMaps.validateAgainst(result.pack(), this.context, report);
        }
        MutableComponent text = Component.empty();
        if (result.ok() && !report.hasErrors()) {
            MapPack pack = result.pack();
            this.validPack = pack;
            text.append(Component.translatable("mapgen.screen.valid", pack.name()).withStyle(ChatFormatting.GREEN)).append("\n")
                    .append(Component.literal(pack.summary()).withStyle(ChatFormatting.GRAY));
        } else {
            text.append(Component.translatable("mapgen.screen.invalid").withStyle(ChatFormatting.RED));
            appendLines(text, report.errors(), ChatFormatting.RED);
        }
        appendLines(text, report.warnings(), ChatFormatting.YELLOW);
        this.doneButton.active = this.validPack != null;
        this.setStatus(text);
    }

    private static void appendLines(MutableComponent text, List<String> lines, ChatFormatting color) {
        for (int i = 0; i < lines.size(); i++) {
            if (i == MAX_LISTED) {
                text.append("\n").append(Component.translatable("mapgen.screen.more", lines.size() - MAX_LISTED).withStyle(color));
                break;
            }
            text.append("\n").append(Component.literal("• " + lines.get(i)).withStyle(color));
        }
    }

    private void setStatus(Component text) {
        this.status.setMessage(text);
        this.repositionElements();
    }

    private void browse() {
        Path start = ClientMaps.mapsFolder();
        if (!Files.isDirectory(start)) {
            start = this.minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        }
        NativeFileDialog.openZip(start, path -> this.pathBox.setValue(path.toString()),
                () -> this.setStatus(Component.translatable("mapgen.screen.no_dialog", ClientMaps.mapsFolder().toString()).withStyle(ChatFormatting.YELLOW)));
    }

    private void openMapsFolder() {
        Path folder = ClientMaps.mapsFolder();
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            this.setStatus(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }
        Blaze3D.openPath(folder);
    }

    private void apply() {
        MapPack pack = this.validPack;
        if (pack == null) {
            return;
        }
        this.minecraft.gui.setScreen(this.parent);
        ClientMaps.apply(this.parent, pack);
    }

    private static String stripQuotes(String path) {
        // Paths copied from a file manager ("Copy as path") are often quoted.
        if (path.length() >= 2 && (path.startsWith("\"") && path.endsWith("\"") || path.startsWith("'") && path.endsWith("'"))) {
            return path.substring(1, path.length() - 1);
        }
        return path;
    }
}
