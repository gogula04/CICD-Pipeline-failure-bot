package com.pipeline.intelligence_bot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PythonAnalysisService {

    @Value("${analysis.python.url:http://localhost:5000/analyze}")
    private String pythonEngineUrl;

    private final RestTemplate restTemplate;

    public PythonAnalysisService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Map<String, Object> analyzeLogs(String logs) {
        if (logs == null || logs.isBlank()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("failureType", "No log content");
            empty.put("category", "Unknown");
            empty.put("confidence", "LOW");
            empty.put("analysisSource", "java-guardrail");
            return empty;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> requestBody = new LinkedHashMap<>();
            requestBody.put("log", logs);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response =
                    restTemplate.exchange(
                            pythonEngineUrl,
                            HttpMethod.POST,
                            request,
                            Map.class
                    );

            Map<String, Object> responseBody = response.getBody();

            if (responseBody == null) {
                throw new IllegalStateException("Python analysis returned an empty body");
            }

            responseBody.putIfAbsent("analysisSource", "python-pattern-engine");
            return responseBody;
        } catch (Exception exception) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("failureType", "Analysis Engine Unavailable");
            error.put("category", "Unknown");
            error.put("rootCause", exception.getMessage());
            error.put("fixRecommendation", "Use the Java-side fallback analysis and verify that the Python engine is running.");
            error.put("confidence", "LOW");
            error.put("analysisSource", "java-fallback-required");
            return error;
        }
    }
}
