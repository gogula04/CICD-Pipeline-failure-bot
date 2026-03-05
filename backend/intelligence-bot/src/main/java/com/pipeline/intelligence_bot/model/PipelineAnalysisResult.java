package com.pipeline.intelligence_bot.model;

import java.util.List;
import java.util.Map;

public class PipelineAnalysisResult {

        private String projectId;
        private String pipelineId;
        private String commitSha;

        private PipelineSummary pipelineSummary;

        private String rootCauseJobId;
        private String failureType;
        private String rootCause;
        private String fixRecommendation;

        private String changeImpact;
        private String changeAnalysis;

        private Map<String, Object> dependencyIntelligence;

        private List<Map<String, Object>> jobAnalyses;

        private int totalFailedJobs;

        public PipelineAnalysisResult() {}

        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }

        public String getPipelineId() { return pipelineId; }
        public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }

        public String getCommitSha() { return commitSha; }
        public void setCommitSha(String commitSha) { this.commitSha = commitSha; }

        public PipelineSummary getPipelineSummary() { return pipelineSummary; }
        public void setPipelineSummary(PipelineSummary pipelineSummary) {
                this.pipelineSummary = pipelineSummary;
        }

        public String getRootCauseJobId() { return rootCauseJobId; }
        public void setRootCauseJobId(String rootCauseJobId) {
                this.rootCauseJobId = rootCauseJobId;
        }

        public String getFailureType() { return failureType; }
        public void setFailureType(String failureType) {
                this.failureType = failureType;
        }

        public String getRootCause() { return rootCause; }
        public void setRootCause(String rootCause) {
                this.rootCause = rootCause;
        }

        public String getFixRecommendation() { return fixRecommendation; }
        public void setFixRecommendation(String fixRecommendation) {
                this.fixRecommendation = fixRecommendation;
        }

        public String getChangeImpact() { return changeImpact; }
        public void setChangeImpact(String changeImpact) {
                this.changeImpact = changeImpact;
        }

        public String getChangeAnalysis() { return changeAnalysis; }
        public void setChangeAnalysis(String changeAnalysis) {
                this.changeAnalysis = changeAnalysis;
        }

        public Map<String, Object> getDependencyIntelligence() {
                return dependencyIntelligence;
        }

        public void setDependencyIntelligence(Map<String, Object> dependencyIntelligence) {
                this.dependencyIntelligence = dependencyIntelligence;
        }

        public List<Map<String, Object>> getJobAnalyses() { return jobAnalyses; }
        public void setJobAnalyses(List<Map<String, Object>> jobAnalyses) {
                this.jobAnalyses = jobAnalyses;
        }

        public int getTotalFailedJobs() { return totalFailedJobs; }
        public void setTotalFailedJobs(int totalFailedJobs) {
                this.totalFailedJobs = totalFailedJobs;
        }
}