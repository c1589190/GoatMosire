package com.goatmosire;

import com.goatmosire.config.GoatMosireConfig;
import com.goatmosire.http.GoatmosireHttpServer;
import com.goatmosire.mcp.McpServer;
import com.goatmosire.service.MapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        // Start HTTP server
        GoatmosireHttpServer httpServer = null;
        if (config.httpMode()) {
            httpServer = new GoatmosireHttpServer(config.httpPort(), mapService);
            httpServer.start();
        }

        // Start MCP server (on main thread if MCP-only, background thread otherwise)
        McpServer mcpServer = new McpServer(mapService);
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
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down...");
                if (hs != null) hs.stop();
                ms.stop();
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
                          -Dgoatmosire.worldsDir=<path>   GSim worlds directory (default: ~/GSimulator/worlds)
                          -Dgoatmosire.port=<port>        HTTP port (default: 8711)
                        """);
                    System.exit(0);
                }
            }
        }

        if (httpOnly) System.setProperty("goatmosire.mcpOnly", "true");
        if (mcpOnly) System.setProperty("goatmosire.httpOnly", "true");

        return GoatMosireConfig.load();
    }
}
