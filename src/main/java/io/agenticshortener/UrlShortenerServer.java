package io.agenticshortener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal, dependency-free HTTP URL shortener used by the assignment prototype. */
final class UrlShortenerServer {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final String publicBaseUrl;

    UrlShortenerServer(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 64);
        server.createContext("/health", this::health);
        server.createContext("/api/shorten", this::shorten);
        server.createContext("/api/analytics", this::analytics);
        server.createContext("/", this::redirect);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "url-shortener-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        return server;
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange, "GET"); return; }
        sendJson(exchange, 200, Map.of("status", "UP", "links", links.size()));
    }

    private void shorten(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange, "POST"); return; }
        try {
            Map<String, Object> payload = Json.object(readBody(exchange));
            Object rawUrl = payload.get("url");
            if (!(rawUrl instanceof String target)) {
                sendJson(exchange, 400, Map.of("error", "url must be a string"));
                return;
            }
            URI uri;
            try { uri = URI.create(target.trim()); }
            catch (IllegalArgumentException ex) {
                sendJson(exchange, 400, Map.of("error", "url must be a valid absolute HTTP or HTTPS URL"));
                return;
            }
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null ||
                    !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                sendJson(exchange, 400, Map.of("error", "url must be a valid absolute HTTP or HTTPS URL"));
                return;
            }

            String requestedAlias = payload.get("alias") instanceof String alias ? alias.trim() : "";
            String code = requestedAlias.isEmpty() ? newCode() : requestedAlias;
            if (!code.matches("[A-Za-z0-9_-]{3,32}")) {
                sendJson(exchange, 400, Map.of("error", "alias must be 3-32 letters, digits, '_' or '-'"));
                return;
            }
            Link link = new Link(code, uri.toASCIIString(), Instant.now().toString());
            if (links.putIfAbsent(code, link) != null) {
                sendJson(exchange, 409, Map.of("error", "short code already exists"));
                return;
            }
            sendJson(exchange, 201, Map.of(
                    "code", code,
                    "shortUrl", publicBaseUrl + "/" + code,
                    "targetUrl", link.target,
                    "createdAt", link.createdAt));
        } catch (IllegalArgumentException ex) {
            sendJson(exchange, 400, Map.of("error", "request body must be a JSON object"));
        } catch (BodyTooLargeException ex) {
            sendJson(exchange, 413, Map.of("error", "request body exceeds 64 KB"));
        }
    }

    private void analytics(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange, "GET"); return; }
        String prefix = "/api/analytics/";
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(prefix) || path.length() == prefix.length()) {
            sendJson(exchange, 400, Map.of("error", "use /api/analytics/{code}"));
            return;
        }
        Link link = links.get(path.substring(prefix.length()));
        if (link == null) { sendJson(exchange, 404, Map.of("error", "short code not found")); return; }
        sendJson(exchange, 200, Map.of(
                "code", link.code,
                "targetUrl", link.target,
                "createdAt", link.createdAt,
                "clicks", link.clicks.get()));
    }

    private void redirect(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET, HEAD");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String code = path.startsWith("/") ? path.substring(1) : path;
        if (code.isEmpty() || code.contains("/")) { sendJson(exchange, 404, Map.of("error", "short code not found")); return; }
        Link link = links.get(code);
        if (link == null) { sendJson(exchange, 404, Map.of("error", "short code not found")); return; }
        if ("GET".equals(exchange.getRequestMethod())) link.clicks.incrementAndGet();
        exchange.getResponseHeaders().set("Location", link.target);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new BodyTooLargeException();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        sendJson(exchange, 405, Map.of("error", "method not allowed"));
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.stringify(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private String newCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder code = new StringBuilder(7);
            for (int i = 0; i < 7; i++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            if (!links.containsKey(code.toString())) return code.toString();
        }
        throw new IllegalStateException("could not allocate a unique short code");
    }

    private static final class Link {
        private final String code;
        private final String target;
        private final String createdAt;
        private final AtomicLong clicks = new AtomicLong();
        private Link(String code, String target, String createdAt) {
            this.code = code;
            this.target = target;
            this.createdAt = createdAt;
        }
    }

    private static final class BodyTooLargeException extends RuntimeException {}
}
