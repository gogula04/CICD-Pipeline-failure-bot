package com.pipeline.intelligence_bot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AiReasoningService {

    private static final Logger logger = LoggerFactory.getLogger(AiReasoningService.class);

    private static final String GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_STRICT_MODEL = "openai/gpt-oss-20b";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${analysis.ai.enabled:true}")
    private boolean enabled;

    @Value("${analysis.ai.groq.model:openai/gpt-oss-20b}")
    private String groqModel;

    @Value("${analysis.ai.temperature:0.2}")
    private double temperature;

    @Value("${analysis.ai.max-tokens:1200}")
    private int maxTokens;

    @Value("${analysis.ai.timeout-ms:45000}")
    private long timeoutMs;

    @Value("${groq.api.key:}")
    private String groqApiKey;

    public AiReasoningService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return enabled && isConfigured();
    }

    public boolean isConfigured() {
        return groqApiKey != null && !groqApiKey.isBlank();
    }

    public String getProviderName() {
        return "groq";
    }

    public String getModelName() {
        return groqModel;
    }

    public String getProviderLabel() {
        return "Groq AI";
    }

    public String describeEngine() {
        if (!enabled) {
            return "Python signatures only";
        }

        if (!isConfigured()) {
            return "Python signatures only";
        }

        return "Dual engine: Python signatures + " + getProviderLabel() + " (" + getModelName() + ")";
    }

    public Map<String, Object> analyzeFailure(Map<String, Object> context) {
        if (!isEnabled()) {
            return Map.of();
        }

        try {
            return analyzeWithGroq(context);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("AI reasoning call was interrupted: {}", exception.getMessage());
            return Map.of();
        } catch (Exception exception) {
            logger.warn("AI reasoning call failed: {}", exception.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> analyzeWithGroq(Map<String, Object> context) throws IOException, InterruptedException {
        String prompt = buildPrompt(context);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", groqModel == null || groqModel.isBlank() ? GROQ_STRICT_MODEL : groqModel);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt()),
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("response_format", Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", "dual_engine_pipeline_failure_analysis",
                        "strict", true,
                        "schema", buildResponseSchema()
                )
        ));

        String body = objectMapper.writeValueAsString(requestBody);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_ENDPOINT))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + groqApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Groq API returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode payload = objectMapper.readTree(response.body());
        String content = extractGroqContent(payload);
        return parseAiResponse(content, "groq", requestBody.get("model").toString());
    }

    private Map<String, Object> parseAiResponse(String content, String providerName, String modelName) throws IOException {
        String json = extractJsonPayload(content);
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {
        });
        Map<String, Object> normalized = new LinkedHashMap<>();

        normalized.put("analysisSource", "ai-" + providerName);
        normalized.put("aiProvider", providerName);
        normalized.put("aiModel", modelName);
        normalized.put("category", asString(parsed.get("category")));
        normalized.put("failureType", asString(parsed.get("failureType")));
        normalized.put("tool", asString(parsed.get("tool")));
        normalized.put("confidence", asString(parsed.get("confidence")));
        normalized.put("rootCause", asString(parsed.get("rootCause")));
        normalized.put("whatIsWrong", asString(parsed.get("whatIsWrong")));
        normalized.put("fixRecommendation", asString(parsed.get("fixRecommendation")));
        normalized.put("nextBestAction", asString(parsed.get("nextBestAction")));
        normalized.put("errorMessage", asString(parsed.get("errorMessage")));
        normalized.put("file", asString(parsed.get("file")));
        normalized.put("line", asString(parsed.get("line")));
        normalized.put("column", asString(parsed.get("column")));
        normalized.put("classOrFunction", asString(parsed.get("classOrFunction")));
        normalized.put("symbolName", asString(parsed.get("symbolName")));
        normalized.put("symbolKind", asString(parsed.get("symbolKind")));
        normalized.put("missingSymbols", asStringList(parsed.get("missingSymbols")));
        normalized.put("details", asStringList(parsed.get("details")));
        normalized.put("signals", asStringList(parsed.get("signals")));
        normalized.put("fixOptions", asStringList(parsed.get("fixOptions")));
        normalized.put("recommendedOwner", asString(parsed.get("recommendedOwner")));
        normalized.put("reasoning", asStringList(parsed.get("reasoning")));
        normalized.put("summary", asString(parsed.get("summary")));
        normalized.put("triageUrgency", normalizeTriageUrgency(parsed.get("triageUrgency")));
        normalized.put("requiresHumanReview", Boolean.TRUE.equals(parsed.get("requiresHumanReview")));
        normalized.put("recommendationPolicy", asString(parsed.get("recommendationPolicy")));
        return normalized;
    }

    private String extractGroqContent(JsonNode payload) {
        JsonNode choices = payload.path("choices");

        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode message = choices.get(0).path("message");

            if (message.hasNonNull("content")) {
                return message.get("content").asText();
            }
        }

        return "";
    }

    private String buildPrompt(Map<String, Object> context) throws IOException {
        Map<String, Object> promptContext = new LinkedHashMap<>(context);
        promptContext.put("provider", getProviderLabel());
        promptContext.put("model", getModelName());
        promptContext.put("engine", describeEngine());

        String serialized = objectMapper.writeValueAsString(promptContext);

        return """
                You are Layer 2 in a dual-engine CI/CD pipeline failure analyzer powered by Groq AI.

                Layer 1 is a deterministic Python signature engine. Treat its classification, missing-symbol detection, and log fingerprints as the base signal.
                Use the preprocessed logs, pipeline metadata, commit context, and changed-file summaries to add contextual reasoning with Groq AI.
                Do not hallucinate a root cause if the evidence is weak. If the Python signal is already high confidence, refine the explanation instead of contradicting it.
                Return only valid JSON that matches the requested schema. No markdown, no code fences, no prose outside the JSON.

                Input JSON:
                %s
                """.formatted(serialized);
    }

    private String systemPrompt() {
        return """
                You are a careful CI/CD failure analyst.
                Your job is to refine a Python signature result with contextual reasoning from logs, commits, and pipeline state.
                Preserve deterministic signals when they are high confidence, and use the AI layer to enrich root-cause narrative, triage urgency, and fix guidance.
                """;
    }

    private Map<String, Object> buildResponseSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("category", nullableStringSchema());
        properties.put("failureType", nullableStringSchema());
        properties.put("tool", nullableStringSchema());
        properties.put("confidence", nullableStringSchema());
        properties.put("rootCause", stringSchema());
        properties.put("whatIsWrong", stringSchema());
        properties.put("fixRecommendation", stringSchema());
        properties.put("nextBestAction", stringSchema());
        properties.put("errorMessage", stringSchema());
        properties.put("file", nullableStringSchema());
        properties.put("line", nullableStringSchema());
        properties.put("column", nullableStringSchema());
        properties.put("classOrFunction", nullableStringSchema());
        properties.put("symbolName", nullableStringSchema());
        properties.put("symbolKind", nullableStringSchema());
        properties.put("missingSymbols", stringArraySchema());
        properties.put("details", stringArraySchema());
        properties.put("signals", stringArraySchema());
        properties.put("fixOptions", stringArraySchema());
        properties.put("recommendedOwner", stringSchema());
        properties.put("reasoning", stringArraySchema());
        properties.put("summary", stringSchema());
        properties.put("triageUrgency", triageUrgencySchema());
        properties.put("aiProvider", stringSchema());
        properties.put("aiModel", stringSchema());
        properties.put("analysisSource", stringSchema());
        properties.put("requiresHumanReview", booleanSchema());
        properties.put("recommendationPolicy", stringSchema());

        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(properties.keySet()));
        schema.put("additionalProperties", false);

        return schema;
    }

    private Map<String, Object> triageUrgencySchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("level", stringSchema());
        properties.put("action", stringSchema());
        properties.put("reason", stringSchema());

        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("level", "action", "reason"));
        schema.put("additionalProperties", false);

        return schema;
    }

    private Map<String, Object> stringSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        return schema;
    }

    private Map<String, Object> nullableStringSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", List.of("string", "null"));
        return schema;
    }

    private Map<String, Object> stringArraySchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", stringSchema());
        return schema;
    }

    private Map<String, Object> booleanSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "boolean");
        return schema;
    }

    private Map<String, Object> normalizeTriageUrgency(Object value) {
        Map<String, Object> urgency = new LinkedHashMap<>();
        Map<String, Object> source = safeMap(value);
        urgency.put("level", asString(source.get("level")));
        urgency.put("action", asString(source.get("action")));
        urgency.put("reason", asString(source.get("reason")));
        return urgency;
    }

    private List<String> asStringList(Object value) {
        List<String> out = new ArrayList<>();

        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String text = asString(item);
                if (!text.isBlank()) {
                    out.add(text);
                }
            }
        }

        return out;
    }

    private Map<String, Object> safeMap(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                converted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return converted;
        }

        return Map.of();
    }

    private String extractJsonPayload(String content) {
        String trimmed = content == null ? "" : content.trim();

        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");

            if (firstNewline >= 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');

        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        return trimmed;
    }

    private String asString(Object value) {
        return value == null ? "" : Objects.toString(value, "");
    }
}
