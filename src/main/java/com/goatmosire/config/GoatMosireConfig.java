package com.goatmosire.config;

import java.nio.file.Path;

/**
 * GoatMosire configuration — loaded from system properties and defaults.
 */
public record GoatMosireConfig(
    Path worldsDir,
    Path importDir,
    int httpPort,
    boolean httpMode,
    boolean mcpMode
) {
    public static GoatMosireConfig load() {
        String worldsDir = System.getProperty("goatmosire.worldsDir",
            System.getenv().getOrDefault("GOATMOSIRE_WORLDS_DIR",
                "worlds"));
        String importDir = System.getProperty("goatmosire.importDir",
            System.getenv().getOrDefault("GOATMOSIRE_IMPORT_DIR",
                null));
        int port = Integer.parseInt(System.getProperty("goatmosire.port",
            System.getenv().getOrDefault("GOATMOSIRE_PORT", "8711")));
        boolean httpMode = !Boolean.parseBoolean(System.getProperty("goatmosire.mcpOnly", "false"));
        boolean mcpMode = !Boolean.parseBoolean(System.getProperty("goatmosire.httpOnly", "false"));

        return new GoatMosireConfig(Path.of(worldsDir), importDir != null ? Path.of(importDir) : null, port, httpMode, mcpMode);
    }
}
