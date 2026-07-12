package com.goatmosire.http;

import com.goatmosire.service.MapService;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * GoatMosire HTTP server — serves the map API and static web editor.
 */
public class GoatmosireHttpServer {

    private static final Logger log = LoggerFactory.getLogger(GoatmosireHttpServer.class);
    private final int port;
    private final MapService mapService;
    private HttpServer server;

    public GoatmosireHttpServer(int port, MapService mapService) {
        this.port = port;
        this.mapService = mapService;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/map", new MapApiHandler(mapService));
        server.createContext("/", new StaticFileHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        log.info("HTTP server started at http://127.0.0.1:{}", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            log.info("HTTP server stopped");
        }
    }
}
