package com.goatmosire;

import com.goatmosire.config.GoatMosireConfig;
import com.goatmosire.http.GoatmosireHttpServer;
import com.goatmosire.mcp.McpServer;
import com.goatmosire.service.MapService;
import com.gsim.app.AppConfig;
import com.gsim.app.GSimulatorApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * GoatMosire entry point.
 *
 * <p>Usage:
 * <pre>
 *   java -jar goatmosire.jar                    → HTTP + MCP (default)
 *   java -jar goatmosire.jar --http-only        → HTTP only
 *   java -jar goatmosire.jar --mcp-only         → MCP stdio only
 *   java -Dgoatmosire.worldsDir=/path/to/worlds → custom worlds dir
 *   java -Dgoatmosire.port=8711                 → custom HTTP port
 * </pre>
 */
public class GoatMosireApp {

    private static final Logger log = LoggerFactory.getLogger(GoatMosireApp.class);

    public static void main(String[] args) throws Exception {
        GoatMosireConfig config = parseArgs(args);
        MapService mapService = new MapService(config.worldsDir());

        log.info("GoatMosire v0.1.0 starting...");
        log.info("  worlds dir: {}", config.worldsDir());
        log.info("  HTTP mode: {} (port {})", config.httpMode(), config.httpPort());
        log.info("  MCP mode: {}", config.mcpMode());

        // ── Embedded GSimulator HTTP API server (for LLM/Agent MCP tools) ──
        GSimulatorApplication gsimApp = null;
        int gsimPort = Integer.parseInt(System.getProperty("goatmosire.gsimPort",
            System.getenv().getOrDefault("GOATMOSIRE_GSIM_PORT", "8710")));
        if (!Boolean.parseBoolean(System.getProperty("goatmosire.noGsim", "false"))) {
            System.setProperty("api.port", String.valueOf(gsimPort));
            System.setProperty("api.enabled", "true");
            System.setProperty("worlds.dir", config.worldsDir().toAbsolutePath().toString());
            if (config.importDir() != null) {
                System.setProperty("import.dir", config.importDir().toAbsolutePath().toString());
            }
            try {
                final AppConfig gsimConfig = new AppConfig(
                    new com.gsim.config.ConfigLoader(new String[0]).load());
                final GSimulatorApplication app = new GSimulatorApplication(gsimConfig, false, true);
                gsimApp = app;
                new Thread(() -> {
                    try { app.start(); } catch (Exception e) { log.error("GSim embed failed", e); }
                }, "gsim-embed").start();
                Thread.sleep(2000);
                log.info("GSimulator HTTP API embedded on port {}", gsimPort);
            } catch (Exception e) {
                log.warn("Failed to start embedded GSimulator: {}", e.getMessage());
            }
        }

        // Start HTTP server
        GoatmosireHttpServer httpServer = null;
        if (config.httpMode()) {
            httpServer = new GoatmosireHttpServer(config.httpPort(), mapService);
            httpServer.start();
        }

        // Start MCP server (on main thread if MCP-only, background thread otherwise)
        McpServer mcpServer = new McpServer(mapService, config.importDir());
        if (config.mcpMode() && !config.httpMode()) {
            // MCP-only: run on main thread (blocking)
            mcpServer.start();
        } else if (config.mcpMode()) {
            // HTTP + MCP: MCP in background thread
            Thread mcpThread = new Thread(mcpServer, "mcp-stdio");
            mcpThread.setDaemon(true);
            mcpThread.start();
            log.info("MCP server running in background thread");
        }

        // If HTTP-only or HTTP+MCP, keep main thread alive
        if (config.httpMode()) {
            final GoatmosireHttpServer hs = httpServer;
            final McpServer ms = mcpServer;
            final GSimulatorApplication gs = gsimApp;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down...");
                if (hs != null) hs.stop();
                ms.stop();
                if (gs != null) gs.stop();
            }));
            log.info("GoatMosire ready. Press Ctrl+C to stop.");
            Thread.currentThread().join();
        }
    }

    private static GoatMosireConfig parseArgs(String[] args) {
        boolean httpOnly = false;
        boolean mcpOnly = false;

        for (String arg : args) {
            switch (arg) {
                case "--http-only" -> httpOnly = true;
                case "--mcp-only" -> mcpOnly = true;
                case "--help", "-h" -> {
                    System.out.println("""
                        GoatMosire — Hex map editor and MCP bridge for GSim
                        
                        Usage: java -jar goatmosire.jar [options]
                        
                        Options:
                          --http-only     Start HTTP server only (no MCP)
                          --mcp-only      Start MCP stdio server only (no HTTP)
                          --help, -h      Show this help
                        
                        System properties:
                          -Dgoatmosire.worldsDir=<path>   GSim worlds directory (default: ./worlds)
                          -Dgoatmosire.importDir=<path>   GSim import/docs directory (default: ./import)
                          -Dgoatmosire.port=<port>        HTTP port (default: 8711)
                          -Dgoatmosire.gsimPort=<port>    GSim embedded API port (default: 8710)
                          -Dgoatmosire.noGsim=true        Disable embedded GSim API
                        """);
                    System.exit(0);
                }
            }
        }

        if (httpOnly) System.setProperty("goatmosire.httpOnly", "true");
        if (mcpOnly) System.setProperty("goatmosire.mcpOnly", "true");

        return GoatMosireConfig.load();
    }
}
