package com.pipeline.intelligence_bot.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.HashMap;

@Service
public class PythonAnalysisService {

    private final String PYTHON_ENGINE_URL = "http://localhost:5000/analyze";

    private final RestTemplate restTemplate = new RestTemplate();

    public Map<String, Object> analyzeLogs(String logs) {

        try {

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("log", logs);

            HttpEntity<Map<String, String>> request =
                    new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response =
                    restTemplate.exchange(
                            PYTHON_ENGINE_URL,
                            HttpMethod.POST,
                            request,
                            Map.class
                    );

            return response.getBody();

        } catch (Exception e) {

            Map<String, Object> error = new HashMap<>();
            error.put("failureType", "Python Engine Error");
            error.put("rootCause", e.getMessage());
            error.put("fixRecommendation", "Check Python analysis engine");

            return error;
        }
    }
}