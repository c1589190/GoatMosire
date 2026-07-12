package com.goatmosire.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves static files from the web/ resource directory.
 * Maps / → /index.html for the map editor.
 */
public class StaticFileHandler implements HttpHandler {

    private static final String WEB_ROOT = "/web";
    private static final Map<String, String> MIME = Map.of(
        "html", "text/html; charset=utf-8",
        "css", "text/css; charset=utf-8",
        "js", "application/javascript; charset=utf-8",
        "json", "application/json",
        "png", "image/png",
        "svg", "image/svg+xml"
    );

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) path = "/index.html";

        String resourcePath = WEB_ROOT + path;
        InputStream is = getClass().getResourceAsStream(resourcePath);

        if (is == null) {
            // Fallback: try serving index.html for SPA routing
            if (!path.contains(".")) {
                is = getClass().getResourceAsStream(WEB_ROOT + "/index.html");
            }
            if (is == null) {
                String msg = "404 Not Found: " + path;
                exchange.sendResponseHeaders(404, msg.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(msg.getBytes());
                }
                return;
            }
        }

        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        String contentType = MIME.getOrDefault(ext, "application/octet-stream");
        exchange.getResponseHeaders().set("Content-Type", contentType);

        byte[] bytes = is.readAllBytes();
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
