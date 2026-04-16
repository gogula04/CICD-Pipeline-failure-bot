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

@Service
public class AiChatService {

    private static final Logger logger = LoggerFactory.getLogger(AiChatService.class);

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

    @Value("${analysis.ai.max-tokens:900}")
    private int maxTokens;

    @Value("${analysis.ai.timeout-ms:45000}")
    private long timeoutMs;

    @Value("${groq.api.key:}")
    private String groqApiKey;

    public AiChatService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> answerQuestion(String question, Map<String, Object> analysis) {
        if (!isEnabled()) {
            return buildFallbackResponse(question, analysis, "Groq AI is not configured");
        }

        try {
            return answerWithGroq(question, analysis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("Chat request was interrupted: {}", exception.getMessage());
            return buildFallbackResponse(question, analysis, "Groq AI request was interrupted");
        } catch (Exception exception) {
            logger.warn("Chat request failed: {}", exception.getMessage());
            return buildFallbackResponse(question, analysis, "Groq AI request failed");
        }
    }

    private boolean isEnabled() {
        return enabled && groqApiKey != null && !groqApiKey.isBlank();
    }

    private Map<String, Object> answerWithGroq(String question, Map<String, Object> analysis)
            throws IOException, InterruptedException {

        Map<String, Object> context = buildChatContext(question, analysis);
        String prompt = buildPrompt(question, context);

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
                        "name", "ci_cd_pipeline_chat_answer",
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
        return parseChatResponse(content);
    }

    private Map<String, Object> parseChatResponse(String content) throws IOException {
        String json = extractJsonPayload(content);
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", asString(parsed.get("answer")));
        result.put("codeSnippet", asString(parsed.get("codeSnippet")));
        result.put("followUp", asString(parsed.get("followUp")));
        result.put("confidence", asString(parsed.get("confidence")));
        result.put("focus", asStringList(parsed.get("focus"), 8));
        result.put("aiProvider", "groq");
        result.put("aiModel", firstNonBlank(asString(parsed.get("aiModel")), groqModel));
        result.put("analysisSource", "ai-groq-chat");
        return result;
    }

    private String buildPrompt(String question, Map<String, Object> context) throws IOException {
        String serialized = objectMapper.writeValueAsString(context);

        return """
                You are an enterprise CI/CD debugging assistant.

                You are given:
                - Failure type
                - Root cause
                - Logs
                - File and line number
                - Commit information

                Answer the user's question clearly and practically.

                Focus on:
                - Why it failed
                - What to fix
                - Where to fix

                Do not give generic answers.
                If the user asks for code or a fix example, include the smallest practical patch or codeSnippet.
                Return JSON only. No markdown, no code fences, no prose outside the JSON.

                User question:
                %s

                Context JSON:
                %s
                """.formatted(question, serialized);
    }

    private String systemPrompt() {
        return """
                You are a concise, practical debugging copilot for GitLab CI/CD failures.
                Stay grounded in the provided analysis, commit data, and filtered logs.
                Prioritize direct fixes, exact failure locations, and commit relevance.
                """;
    }

    private Map<String, Object> buildChatContext(String question, Map<String, Object> analysis) {
        Map<String, Object> root = safeMap(analysis);
        Map<String, Object> reportMetadata = firstMap(root, "reportMetadata");
        Map<String, Object> pipeline = firstMap(root, "pipeline");
        Map<String, Object> failure = firstMap(root, "failure", "primaryFailure", "primaryFailureAnalysis");
        Map<String, Object> commit = firstMap(root, "commit", "commitAnalysis");
        Map<String, Object> trend = firstMap(root, "trend", "recentFailurePatterns");

        if (pipeline.isEmpty()) {
            pipeline = new LinkedHashMap<>();
            pipeline.put("projectId", root.get("projectId"));
            pipeline.put("pipelineId", root.get("pipelineId"));
            pipeline.put("analysisMode", root.get("analysisMode"));
            pipeline.put("summary", safeMap(root.get("pipelineSummary")));
            pipeline.put("overview", safeMap(root.get("pipelineOverview")));
            pipeline.put("operations", safeMap(root.get("operationalInsights")));
        }

        if (failure.isEmpty()) {
            failure = safeMap(root.get("primaryFailureAnalysis"));
        }

        if (commit.isEmpty()) {
            commit = safeMap(root.get("commitAnalysis"));
        }

        Map<String, Object> logs = safeMap(failure.get("preprocessedLogs"));

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("question", question);
        context.put("reportMetadata", pickFields(reportMetadata, List.of("reportId", "generatedAt", "analysisEngine", "aiProvider", "aiModel", "aiEnabled")));
        context.put("pipeline", pickPipelineContext(pipeline));
        context.put("failure", pickFailureContext(failure));
        context.put("logs", pickLogContext(logs, failure));
        context.put("commit", pickCommitContext(commit, root));
        context.put("trend", pickFields(trend, List.of("recurrenceSummary", "recentFailureRatePercent", "recurringSignal", "sameSignatureCount", "sameFingerprintCount")));
        return context;
    }

    private Map<String, Object> pickPipelineContext(Map<String, Object> pipeline) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("projectId", pipeline.get("projectId"));
        context.put("pipelineId", pipeline.get("pipelineId"));
        context.put("analysisMode", pipeline.get("analysisMode"));
        context.put("summary", pickFields(safeMap(pipeline.get("summary")), List.of("totalJobs", "failedJobs", "passedJobs", "failureRatePercent")));
        context.put("overview", pickFields(safeMap(pipeline.get("overview")), List.of("projectPath", "pipelineRef", "pipelineStatus", "pipelineSource", "pipelineDuration", "failureBlastRadius")));
        context.put("operations", pickFields(safeMap(pipeline.get("operations")), List.of("releaseReadiness", "riskLevel", "actionPriority")));
        return context;
    }

    private Map<String, Object> pickFailureContext(Map<String, Object> failure) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("failureType", firstNonBlank(asString(failure.get("failureType")), asString(failure.get("errorTypeDisplay")), asString(failure.get("errorType")), asString(failure.get("category"))));
        context.put("category", failure.get("category"));
        context.put("rootCause", failure.get("rootCause"));
        context.put("whatIsWrong", failure.get("whatIsWrong"));
        context.put("fixRecommendation", failure.get("fixRecommendation"));
        context.put("nextBestAction", failure.get("nextBestAction"));
        context.put("errorMessage", failure.get("errorMessage"));
        context.put("file", failure.get("file"));
        context.put("line", failure.get("line"));
        context.put("column", failure.get("column"));
        context.put("tool", failure.get("tool"));
        context.put("confidence", failure.get("confidence"));
        context.put("severity", failure.get("severity"));
        context.put("recommendedOwner", failure.get("recommendedOwner"));
        context.put("signals", asStringList(failure.get("signals"), 8));
        context.put("details", asStringList(failure.get("details"), 8));
        context.put("logHighlights", asStringList(failure.get("logHighlights"), 8));
        context.put("preprocessedLogs", pickLogContext(safeMap(failure.get("preprocessedLogs")), failure));
        return context;
    }

    private Map<String, Object> pickLogContext(Map<String, Object> logs, Map<String, Object> failure) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("summary", logs.get("summary"));
        context.put("firstSignalLine", logs.get("firstSignalLine"));
        context.put("signalCount", logs.get("signalCount"));
        context.put("highSignalLines", asStringList(logs.get("highSignalLines"), 8));
        context.put("contextWindow", asStringList(logs.get("contextWindow"), 8));
        context.put("cleanedLogExcerpt", truncateText(firstNonBlank(asString(logs.get("cleanedLogExcerpt")), asString(failure.get("errorMessage"))), 4000));
        return context;
    }

    private Map<String, Object> pickCommitContext(Map<String, Object> commit, Map<String, Object> root) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("commitSha", firstNonBlank(asString(commit.get("commitSha")), asString(root.get("pipelineCommit"))));
        context.put("commitTitle", commit.get("commitTitle"));
        context.put("commitAuthor", commit.get("commitAuthor"));
        context.put("causalAssessment", commit.get("causalAssessment"));
        context.put("smartFileCorrelation", commit.get("smartFileCorrelation"));
        context.put("causationType", commit.get("causationType"));
        context.put("causationConfidence", commit.get("causationConfidence"));
        context.put("changedFiles", normalizeFiles(commit.get("changedFiles")));
        context.put("likelyRelatedFiles", normalizeFiles(commit.get("likelyRelatedFiles")));
        return context;
    }

    private Map<String, Object> buildFallbackResponse(String question, Map<String, Object> analysis, String reason) {
        Map<String, Object> root = safeMap(analysis);
        Map<String, Object> failure = firstMap(root, "failure", "primaryFailure", "primaryFailureAnalysis");
        Map<String, Object> commit = firstMap(root, "commit", "commitAnalysis");
        Map<String, Object> logs = safeMap(failure.get("preprocessedLogs"));

        String failureType = firstNonBlank(asString(failure.get("failureType")), asString(failure.get("errorTypeDisplay")), asString(failure.get("errorType")), "Pipeline failure");
        String rootCause = firstNonBlank(asString(failure.get("rootCause")), asString(failure.get("whatIsWrong")), "The exact root cause still needs review.");
        String file = firstNonBlank(asString(failure.get("file")), "unknown file");
        String line = firstNonBlank(asString(failure.get("line")), "unknown line");
        String commitSha = firstNonBlank(asString(commit.get("commitSha")), asString(root.get("pipelineCommit")), "unknown commit");
        String fixRecommendation = firstNonBlank(asString(failure.get("fixRecommendation")), "Review the failing file, the first high-signal log line, and the recent commit.");
        String logSummary = firstNonBlank(asString(logs.get("summary")), "No log summary was available.");

        String answer = "Groq AI is not available right now (" + reason + "). "
                + "Based on the current analysis, this looks like " + failureType + " because " + rootCause + ". "
                + "Focus on " + file + ":" + line + " and the commit " + commitSha + ". "
                + "Suggested fix: " + fixRecommendation + " "
                + "Log summary: " + logSummary;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answer", answer);
        response.put("codeSnippet", "");
        response.put("followUp", "Ask about the commit, the failing file, or the fastest safe fix.");
        response.put("confidence", firstNonBlank(asString(failure.get("confidence")), "LOW"));
        response.put("focus", List.of("Root cause", "Fix location", "Commit relevance"));
        response.put("aiProvider", "fallback");
        response.put("aiModel", "deterministic-summary");
        response.put("analysisSource", "local-fallback");
        return response;
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

    private String extractJsonPayload(String content) {
        if (content == null || content.isBlank()) {
            return "{}";
        }

        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');

        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }

        return content;
    }

    private Map<String, Object> buildResponseSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("answer", stringSchema());
        properties.put("codeSnippet", nullableStringSchema());
        properties.put("followUp", nullableStringSchema());
        properties.put("confidence", stringSchema());
        properties.put("focus", stringArraySchema());
        properties.put("aiProvider", stringSchema());
        properties.put("aiModel", stringSchema());

        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(properties.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> firstMap(Map<String, Object> root, String... keys) {
        for (String key : keys) {
            Map<String, Object> candidate = safeMap(root.get(key));

            if (!candidate.isEmpty()) {
                return candidate;
            }
        }

        return new LinkedHashMap<>();
    }

    private Map<String, Object> pickFields(Map<String, Object> source, List<String> keys) {
        Map<String, Object> result = new LinkedHashMap<>();

        for (String key : keys) {
            result.put(key, source.get(key));
        }

        return result;
    }

    private List<Map<String, Object>> normalizeFiles(Object value) {
        List<Map<String, Object>> files = new ArrayList<>();

        for (Object item : asList(value)) {
            if (files.size() >= 8) {
                break;
            }

            if (item instanceof Map<?, ?> rawMap) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", firstNonBlank(asString(rawMap.get("path")), asString(rawMap.get("new_path")), asString(rawMap.get("old_path"))));
                entry.put("category", asString(rawMap.get("category")));
                entry.put("changeType", asString(rawMap.get("changeType")));
                files.add(entry);
            } else if (item != null) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", String.valueOf(item));
                entry.put("category", "");
                entry.put("changeType", "");
                files.add(entry);
            }
        }

        return files;
    }

    private List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }

        return List.of();
    }

    private List<String> asStringList(Object value, int maxItems) {
        List<String> values = new ArrayList<>();

        for (Object item : asList(value)) {
            if (values.size() >= maxItems) {
                break;
            }

            String stringValue = asString(item);

            if (!stringValue.isBlank()) {
                values.add(stringValue);
            }
        }

        return values;
    }

    private Map<String, Object> safeMap(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> converted = new LinkedHashMap<>();

            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                converted.put(String.valueOf(entry.getKey()), entry.getValue());
            }

            return converted;
        }

        return new LinkedHashMap<>();
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return "";
    }

    private String truncateText(String value, int maxChars) {
        String text = value == null ? "" : value;

        if (text.length() <= maxChars) {
            return text;
        }

        return text.substring(0, Math.max(1, maxChars - 1)) + "…";
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
}
