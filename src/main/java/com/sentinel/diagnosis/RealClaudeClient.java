package com.sentinel.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.detection.PodInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends the diagnosis prompt to the real Claude API.
 *
 * <p>No official Anthropic Java SDK exists, so this calls the Messages API directly over
 * java.net.http.HttpClient (built into the JDK) rather than pulling in a third-party wrapper.
 */
public class RealClaudeClient implements ClaudeAnalyzer {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;

    public RealClaudeClient(String apiKey) {
        this(apiKey, "claude-sonnet-5");
    }

    public RealClaudeClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    @Override
    public ClaudeAnalysis analyze(PodInfo podInfo, String logs, List<EventInfo> events, PodSpecInfo spec) {
        String prompt = DiagnosisPrompt.build(podInfo, logs, events, spec);

        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 1024);
            var messages = body.putArray("messages");
            var message = messages.addObject();
            message.put("role", "user");
            message.put("content", prompt);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("Claude API returned status " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            String text = "";
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text = block.path("text").asText();
                    break;
                }
            }
            return parseClaudeJson(text);
        } catch (Exception e) {
            throw new RuntimeException("Claude request failed: " + e.getMessage(), e);
        }
    }

    /** Claude may wrap the JSON in prose or a code fence - pull out the {...} block rather than
     * assuming the whole response is bare JSON. */
    private ClaudeAnalysis parseClaudeJson(String text) {
        try {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}') + 1;
            JsonNode parsed = mapper.readTree(text.substring(start, end));

            List<String> evidence = new ArrayList<>();
            for (JsonNode e : parsed.path("evidence")) evidence.add(e.asText());

            return new ClaudeAnalysis(
                parsed.path("root_cause").asText("Unknown"),
                parsed.path("severity").asText("MEDIUM"),
                parsed.path("recommended_fix").asText("No recommendation available"),
                parsed.path("confidence").asDouble(0.0),
                evidence);
        } catch (Exception e) {
            return new ClaudeAnalysis(
                "Unable to parse Claude response",
                "MEDIUM",
                "Manual review required - diagnosis parsing failed",
                0.0,
                List.of(text == null || text.isEmpty() ? "empty response" : text.substring(0, Math.min(200, text.length()))));
        }
    }
}
