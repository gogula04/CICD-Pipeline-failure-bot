package com.pipeline.intelligence_bot.model;

import java.util.Map;

public class ChatRequest {

    private String question;
    private Map<String, Object> analysis;

    public ChatRequest() {
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Map<String, Object> getAnalysis() {
        return analysis;
    }

    public void setAnalysis(Map<String, Object> analysis) {
        this.analysis = analysis;
    }
}
