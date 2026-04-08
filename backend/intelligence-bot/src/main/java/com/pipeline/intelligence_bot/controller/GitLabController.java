package com.pipeline.intelligence_bot.controller;

import com.pipeline.intelligence_bot.model.PipelineAnalysisRequest;
import com.pipeline.intelligence_bot.service.EnterprisePipelineAnalysisService;
import com.pipeline.intelligence_bot.service.GitLabService;
import com.pipeline.intelligence_bot.service.PythonAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/gitlab")
public class GitLabController {

    private final GitLabService gitLabService;
    private final PythonAnalysisService pythonAnalysisService;
    private final EnterprisePipelineAnalysisService enterprisePipelineAnalysisService;

    public GitLabController(
            GitLabService gitLabService,
            PythonAnalysisService pythonAnalysisService,
            EnterprisePipelineAnalysisService enterprisePipelineAnalysisService
    ) {
        this.gitLabService = gitLabService;
        this.pythonAnalysisService = pythonAnalysisService;
        this.enterprisePipelineAnalysisService = enterprisePipelineAnalysisService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "CI/CD Pipeline Failure Intelligence Bot");
        response.put("capabilitiesUrl", "/api/gitlab/capabilities");
        return response;
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return enterprisePipelineAnalysisService.getCapabilities();
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> getPipelineJobs(
            @RequestParam String projectId,
            @RequestParam String pipelineId
    ) {

        return gitLabService.getPipelineJobsAsList(projectId, pipelineId);
    }

    @GetMapping("/logs")
    public String getJobLogs(
            @RequestParam String projectId,
            @RequestParam String jobId
    ) {

        return gitLabService.getJobLogs(projectId, jobId);
    }

    @GetMapping("/pipelineSummary")
    public Map<String, Object> getPipelineSummary(
            @RequestParam String projectId,
            @RequestParam String pipelineId
    ) {

        return gitLabService.buildPipelineSummary(projectId, pipelineId);
    }

    @GetMapping("/firstFailedJob")
    public Map<String, Object> getFirstFailedJob(
            @RequestParam String projectId,
            @RequestParam String pipelineId
    ) {

        List<Map<String, Object>> jobs = gitLabService.getPipelineJobsAsList(projectId, pipelineId);
        Map<String, Object> failed = gitLabService.findFirstFailedJob(jobs);

        if (failed == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "No failed jobs found");
            return result;
        }

        return failed;
    }

    @GetMapping("/allFailedJobs")
    public List<Map<String, Object>> getAllFailedJobs(
            @RequestParam String projectId,
            @RequestParam String pipelineId
    ) {

        List<Map<String, Object>> jobs = gitLabService.getPipelineJobsAsList(projectId, pipelineId);
        return gitLabService.findAllFailedJobs(jobs);
    }

    @GetMapping("/basicFailureReport")
    public Map<String, Object> basicFailureReport(
            @RequestParam String projectId,
            @RequestParam String pipelineId
    ) {

        Map<String, Object> enterpriseReport =
                enterprisePipelineAnalysisService.analyzePipeline(projectId, pipelineId);

        Map<String, Object> primaryFailure = asMap(enterpriseReport.get("primaryFailureAnalysis"));
        Map<String, Object> pipelineSummary = asMap(enterpriseReport.get("pipelineSummary"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        result.put("pipelineId", pipelineId);
        result.put("pipelineCommit", enterpriseReport.get("pipelineCommit"));
        result.put("pipelineSummary", pipelineSummary);
        result.put("failureType", primaryFailure.get("failureType"));
        result.put("rootCause", primaryFailure.get("rootCause"));
        result.put("fixRecommendation", primaryFailure.get("fixRecommendation"));
        result.put("confidence", primaryFailure.get("confidence"));
        return result;
    }

    @GetMapping("/analyzeWithPython")
    public Map<String, Object> analyzePipelineWithPython(
            @RequestParam String projectId,
            @RequestParam String pipelineId
    ) {

        List<Map<String, Object>> jobs = gitLabService.getPipelineJobsAsList(projectId, pipelineId);
        Map<String, Object> failedJob = gitLabService.findFirstFailedJob(jobs);

        if (failedJob == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "No failures found");
            return result;
        }

        String jobId = failedJob.get("id").toString();
        String logs = gitLabService.getJobLogs(projectId, jobId);
        Map<String, Object> pythonResult = pythonAnalysisService.analyzeLogs(logs);

        pythonResult.put("projectId", projectId);
        pythonResult.put("pipelineId", pipelineId);
        pythonResult.put("jobId", jobId);
        pythonResult.put("jobName", failedJob.get("name"));
        pythonResult.put("stage", failedJob.get("stage"));

        return pythonResult;
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyzePipeline(@RequestBody PipelineAnalysisRequest request) {
        return enterprisePipelineAnalysisService.analyzePipeline(
                request.getProjectId(),
                request.getPipelineId(),
                request.getFailedJobId()
        );
    }

    @GetMapping("/analyzePipelineFully")
    public Map<String, Object> analyzePipelineFully(
            @RequestParam String projectId,
            @RequestParam String pipelineId,
            @RequestParam(required = false) String failedJobId
    ) {

        return enterprisePipelineAnalysisService.analyzePipeline(projectId, pipelineId, failedJobId);
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> converted = new LinkedHashMap<>();

            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                converted.put(String.valueOf(entry.getKey()), entry.getValue());
            }

            return converted;
        }

        return new LinkedHashMap<>();
    }
}
