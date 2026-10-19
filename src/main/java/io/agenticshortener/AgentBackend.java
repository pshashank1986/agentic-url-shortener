package io.agenticshortener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** OpenAI-compatible JSON agent backend. No local template is treated as successful reasoning. */
final class AgentBackend {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    AgentBackend(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
    }

    static AgentBackend fromEnvironment() {
        String key = System.getenv("AGENTIC_LLM_API_KEY");
        String model = System.getenv("AGENTIC_LLM_MODEL");
        if (key == null || key.isBlank() || model == null || model.isBlank()) {
            throw new IllegalStateException("Set AGENTIC_LLM_API_KEY and AGENTIC_LLM_MODEL; otherwise the workflow safe-stops for manual review.");
        }
        String base = System.getenv().getOrDefault("AGENTIC_LLM_BASE_URL", "https://api.openai.com/v1");
        return new AgentBackend(base, key, model);
    }

    String model() { return model; }

    Map<String, Object> generate(String stage, Object context) throws Exception {
        String system = """
                You are the %s stage in a governed software engineering workflow.
                Use the actual requirement and repository evidence in the input. Never invent files,
                tests, validation results, approvals, or deployment status. Return exactly one JSON object
                with no Markdown fences. Keep outputs specific to this repository and requirement.
                For code changes, return a 'changes' object mapping safe relative file paths to complete UTF-8
                file contents, plus a 'deletions' array. Do not modify secrets, CI credentials, deployment
                targets, or files outside the repository. Do not claim code was applied or tested.
                """.formatted(stage);
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "temperature", 0.1,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", Json.stringify(context))));
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(requestBody)));
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Agent provider returned HTTP " + response.statusCode() + ". Check provider configuration; response body was not written to the audit log.");
        }
        Map<String, Object> envelope = Json.object(response.body());
        Object choicesValue = envelope.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty() || !(choices.get(0) instanceof Map<?, ?> choice)) {
            throw new IllegalStateException("Agent provider response did not contain choices[0].");
        }
        Object messageValue = choice.get("message");
        if (!(messageValue instanceof Map<?, ?> message) || !(message.get("content") instanceof String content)) {
            throw new IllegalStateException("Agent provider response did not contain message.content.");
        }
        String json = content.trim();
        if (json.startsWith("```")) {
            int firstLine = json.indexOf('\n');
            int closing = json.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) json = json.substring(firstLine + 1, closing).trim();
        }
        try { return Json.object(json); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("Agent returned invalid JSON for stage '" + stage + "'.", ex); }
    }
}
