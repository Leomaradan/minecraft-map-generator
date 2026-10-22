package com.leomaradan.mapgen.platform;

import java.util.ServiceLoader;

/** Finds the loader-specific implementations declared in META-INF/services. */
public final class Services {
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {
    }

    public static <T> T load(Class<T> type) {
        return ServiceLoader.load(type, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to load service for " + type.getName()));
    }
}
