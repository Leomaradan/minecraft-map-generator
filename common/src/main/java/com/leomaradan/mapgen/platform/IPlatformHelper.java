package com.leomaradan.mapgen.platform;

import java.nio.file.Path;

/** The few loader-specific services the common code needs. */
public interface IPlatformHelper {

    String getPlatformName();

    /** The game directory (.minecraft on the client, the server folder on a dedicated server). */
    Path getGameDirectory();

    boolean isDedicatedServer();
}
