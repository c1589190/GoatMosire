package com.goatmosire.config;

import java.nio.file.Path;

/**
 * GoatMosire configuration — loaded from system properties and defaults.
 *
 * @param worldsDir path to the GSim worlds directory
 * @param importDir path to import/docs directory, may be null
 * @param httpPort  port for the HTTP server
 * @param httpMode  whether the HTTP server is enabled
 * @param mcpMode   whether the MCP stdio server is enabled
 */
public record GoatMosireConfig(Path worldsDir, Path importDir, int httpPort, boolean httpMode, boolean mcpMode) {

    /**
     * Constructs a new GoatMosireConfig record.
     *
     * @param worldsDir path to the GSim worlds directory
     * @param importDir path to import/docs directory, may be null
     * @param httpPort  port for the HTTP server
     * @param httpMode  whether the HTTP server is enabled
     * @param mcpMode   whether the MCP stdio server is enabled
     */
    public GoatMosireConfig {
        // Compact constructor
    }

    /**
     * Loads configuration from system properties and environment variables.
     *
     * @return resolved configuration instance
     */
    public static GoatMosireConfig load() {
        String worldsDir = System.getProperty(
                "goatmosire.worldsDir", System.getenv().getOrDefault("GOATMOSIRE_WORLDS_DIR", "worlds"));
        String importDir =
                System.getProperty("goatmosire.importDir", System.getenv().getOrDefault("GOATMOSIRE_IMPORT_DIR", null));
        int port = Integer.parseInt(
                System.getProperty("goatmosire.port", System.getenv().getOrDefault("GOATMOSIRE_PORT", "8711")));
        boolean httpMode = !Boolean.parseBoolean(System.getProperty("goatmosire.mcpOnly", "false"));
        boolean mcpMode = !Boolean.parseBoolean(System.getProperty("goatmosire.httpOnly", "false"));

        return new GoatMosireConfig(
                Path.of(worldsDir), importDir != null ? Path.of(importDir) : null, port, httpMode, mcpMode);
    }
}
