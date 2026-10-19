package io.agenticshortener;

import com.sun.net.httpserver.HttpServer;

/** Application entry point. Defaults to the URL service; use "workflow" for SDLC commands. */
public final class AgenticUrlShortener {
    private AgenticUrlShortener() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "workflow".equals(args[0])) {
            try {
                WorkflowCli.run(args);
            } catch (Exception ex) {
                System.err.println("Workflow failed: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                System.exit(2);
            }
            return;
        }
        if (args.length > 0 && ("help".equals(args[0]) || "--help".equals(args[0]))) {
            printUsage();
            return;
        }
        int port = args.length > 1 && "serve".equals(args[0]) ? Integer.parseInt(args[1])
                : args.length > 0 && !"serve".equals(args[0]) ? Integer.parseInt(args[0]) : 8080;
        String baseUrl = System.getenv().getOrDefault("PUBLIC_BASE_URL", "http://localhost:" + port);
        HttpServer server = new UrlShortenerServer(baseUrl).start(port);
        System.out.printf("URL shortener listening on http://localhost:%d%n", server.getAddress().getPort());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "url-shortener-shutdown"));
    }

    private static void printUsage() {
        System.out.println("""
                Agentic URL Shortener (Java 17)

                Run the URL service:
                  java -cp target/classes io.agenticshortener.AgenticUrlShortener [port]

                Run a governed engineering workflow:
                  java -cp target/classes io.agenticshortener.AgenticUrlShortener workflow plan --repo PATH --requirement TEXT
                  java -cp target/classes io.agenticshortener.AgenticUrlShortener workflow apply --run ID --approve-plan
                  java -cp target/classes io.agenticshortener.AgenticUrlShortener workflow finalize --run ID --approve-release
                  java -cp target/classes io.agenticshortener.AgenticUrlShortener workflow reject --run ID --reason TEXT
                  java -cp target/classes io.agenticshortener.AgenticUrlShortener workflow metrics

                See README.md for configuration, safety boundaries, and the provider contract.
                """);
    }
}
