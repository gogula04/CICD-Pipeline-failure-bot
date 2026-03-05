package com.pipeline.intelligence_bot.model;

import java.util.List;

public class PipelineSummary {

    private String projectId;
    private String pipelineId;

    private int totalJobs;
    private int failedJobs;
    private int successJobs;
    private int skippedJobs;
    private int canceledJobs;
    private int runningJobs;
    private int pendingJobs;
    private int otherJobs;

    private List<Long> failedJobIds;

    public PipelineSummary() {}

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }

    public int getTotalJobs() { return totalJobs; }
    public void setTotalJobs(int totalJobs) { this.totalJobs = totalJobs; }

    public int getFailedJobs() { return failedJobs; }
    public void setFailedJobs(int failedJobs) { this.failedJobs = failedJobs; }

    public int getSuccessJobs() { return successJobs; }
    public void setSuccessJobs(int successJobs) { this.successJobs = successJobs; }

    public int getSkippedJobs() { return skippedJobs; }
    public void setSkippedJobs(int skippedJobs) { this.skippedJobs = skippedJobs; }

    public int getCanceledJobs() { return canceledJobs; }
    public void setCanceledJobs(int canceledJobs) { this.canceledJobs = canceledJobs; }

    public int getRunningJobs() { return runningJobs; }
    public void setRunningJobs(int runningJobs) { this.runningJobs = runningJobs; }

    public int getPendingJobs() { return pendingJobs; }
    public void setPendingJobs(int pendingJobs) { this.pendingJobs = pendingJobs; }

    public int getOtherJobs() { return otherJobs; }
    public void setOtherJobs(int otherJobs) { this.otherJobs = otherJobs; }

    public List<Long> getFailedJobIds() { return failedJobIds; }
    public void setFailedJobIds(List<Long> failedJobIds) { this.failedJobIds = failedJobIds; }
}