package com.pipeline.intelligence_bot.model;

public class PipelineAnalysisRequest {

    private String projectId;
    private String pipelineId;
    private String failedJobId;

    public PipelineAnalysisRequest() {
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(String pipelineId) {
        this.pipelineId = pipelineId;
    }

    public String getFailedJobId() {
        return failedJobId;
    }

    public void setFailedJobId(String failedJobId) {
        this.failedJobId = failedJobId;
    }
}
