package com.goatmosire.http;

import com.goatmosire.service.MapService;
import com.sun.net.httpserver.HttpServer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GoatMosire HTTP server — serves the map API and static web editor.
 */
public class GoatmosireHttpServer {

    private static final Logger log = LoggerFactory.getLogger(GoatmosireHttpServer.class);
    private final int port;
    private final MapService mapService;
    private HttpServer server;

    /**
     * Creates an HTTP server that serves the map API and static web editor.
     *
     * @param port      listening port (bound to 127.0.0.1)
     * @param mapService shared map service instance
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public GoatmosireHttpServer(int port, MapService mapService) {
        this.port = port;
        this.mapService = mapService;
    }

    /**
     * Starts the HTTP server on the configured port and registers API and
     * static-file handlers.
     *
     * @throws IOException if the underlying server socket cannot be created
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/map", new MapApiHandler(mapService));
        server.createContext("/", new StaticFileHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        log.info("HTTP server started at http://127.0.0.1:{}", port);
    }

    /**
     * Stops the HTTP server gracefully, waiting up to 1 second for
     * in-flight requests to complete.
     */
    public void stop() {
        if (server != null) {
            server.stop(1);
            log.info("HTTP server stopped");
        }
    }
}
