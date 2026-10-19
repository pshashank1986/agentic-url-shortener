package io.agenticshortener;

import com.sun.net.httpserver.HttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Dependency-free smoke/regression tests; run through scripts/test.sh. */
public final class SelfTest {
    private SelfTest() {}

    public static void main(String[] args) throws Exception {
        jsonRoundTrip();
        workflowPathPolicy();
        shortenerHttpFlow();
        System.out.println("All self-tests passed.");
    }

    private static void jsonRoundTrip() {
        String original = "quote \" slash \\ newline\n unicode ☃";
        String serialized = Json.stringify(Map.of("value", original, "count", 3, "active", true));
        Map<String, Object> parsed = Json.object(serialized);
        check(original.equals(parsed.get("value")), "JSON string escaping");
        check(Long.valueOf(3).equals(parsed.get("count")), "JSON integer parsing");
        check(Boolean.TRUE.equals(parsed.get("active")), "JSON boolean parsing");
    }

    private static void workflowPathPolicy() throws Exception {
        boolean rejected = false;
        try { RepositorySupport.validatedChanges(Map.of("../escape.txt", "bad")); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "reject traversal paths");

        Path root = Files.createTempDirectory("agentic-source-");
        Path sandbox = Files.createTempDirectory("agentic-sandbox-");
        try {
            Files.writeString(root.resolve("README.md"), "original\n");
            String before = RepositorySupport.fingerprint(root);
            RepositorySupport.copyTree(root, sandbox.resolve("copy"));
            RepositorySupport.apply(sandbox.resolve("copy"), Map.of("README.md", "proposed\n"), java.util.List.of());
            check("original\n".equals(Files.readString(root.resolve("README.md"))), "source repo stays unchanged");
            check(before.equals(RepositorySupport.fingerprint(root)), "source fingerprint stays unchanged");
            check(Files.readString(sandbox.resolve("copy/README.md")).equals("proposed\n"), "proposal applies to sandbox");
        } finally {
            deleteTree(root);
            deleteTree(sandbox);
        }
    }

    private static void shortenerHttpFlow() throws Exception {
        UrlShortenerServer app = new UrlShortenerServer("http://localhost");
        HttpServer server = app.start(0);
        try {
            int port = server.getAddress().getPort();
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            HttpRequest create = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/shorten"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://example.com/a?b=1\",\"alias\":\"demo1\"}"))
                    .build();
            HttpResponse<String> created = client.send(create, HttpResponse.BodyHandlers.ofString());
            check(created.statusCode() == 201, "create short URL");
            check("demo1".equals(Json.object(created.body()).get("code")), "custom alias returned");

            HttpResponse<String> conflict = client.send(create, HttpResponse.BodyHandlers.ofString());
            check(conflict.statusCode() == 409, "duplicate alias rejected");

            HttpResponse<String> redirect = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/demo1")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            check(redirect.statusCode() == 302, "short code redirects");
            check("https://example.com/a?b=1".equals(redirect.headers().firstValue("Location").orElse("")), "redirect target");

            HttpResponse<String> head = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/demo1")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            check(head.statusCode() == 302, "HEAD can check redirect without body");

            HttpResponse<String> analytics = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/analytics/demo1")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            check(analytics.statusCode() == 200, "analytics endpoint");
            check(Long.valueOf(1).equals(Json.object(analytics.body()).get("clicks")), "GET click counted but HEAD is not counted");

            HttpRequest invalid = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/shorten"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"javascript:alert(1)\"}"))
                    .build();
            check(client.send(invalid, HttpResponse.BodyHandlers.ofString()).statusCode() == 400, "unsafe scheme rejected");
        } finally {
            server.stop(0);
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("FAILED: " + description);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
