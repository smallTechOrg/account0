package com.account0.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Raw HTTP client for the Anthropic Messages API (user requirement: plain HTTP, no SDK). */
@Component
public class AnthropicHttpTransport implements LlmTransport {
    private final LlmProperties props;
    private final ObjectMapper om;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public AnthropicHttpTransport(LlmProperties props, ObjectMapper om) { this.props = props; this.om = om; }

    @Override
    public LlmResponse send(LlmRequest req) throws LlmException {
        if (!props.isConfigured()) throw new LlmException("ACCOUNT0_ANTHROPIC_API_KEY is not set");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("max_tokens", req.maxTokens());
        if (req.system() != null) body.put("system", req.system());
        body.put("messages", List.of(Map.of("role", "user", "content", req.content())));
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(props.getBaseUrl() + "/v1/messages"))
                    .timeout(Duration.ofMinutes(5))
                    .header("content-type", "application/json")
                    .header("x-api-key", props.getApiKey())
                    .header("anthropic-version", "2023-06-01");
            if (props.getWorkspaceId() != null && !props.getWorkspaceId().isBlank())
                b.header("anthropic-workspace-id", props.getWorkspaceId());
            HttpResponse<String> r = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                r = http.send(b.POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 429 && r.statusCode() < 500) break;
                Thread.sleep(2000L * (attempt + 1));
            }
            if (r.statusCode() != 200) throw new LlmException("Anthropic API " + r.statusCode() + ": " + truncate(r.body()));
            JsonNode j = om.readTree(r.body());
            StringBuilder text = new StringBuilder();
            for (JsonNode c : j.path("content")) if ("text".equals(c.path("type").asText())) text.append(c.path("text").asText());
            String stop = j.path("stop_reason").asText();
            if ("refusal".equals(stop)) throw new LlmException("Model declined the request (refusal)");
            return new LlmResponse(text.toString(), j.path("usage").path("input_tokens").asLong(), j.path("usage").path("output_tokens").asLong(), stop);
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", e);
        } catch (Exception e) {
            throw new LlmException("Anthropic call failed: " + e.getMessage(), e);
        }
    }
    private static String truncate(String s) { return s == null ? "" : s.length() > 400 ? s.substring(0, 400) : s; }
}
