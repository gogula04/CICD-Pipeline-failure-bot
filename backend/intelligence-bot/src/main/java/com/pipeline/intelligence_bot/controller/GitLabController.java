package com.pipeline.intelligence_bot.controller;

import com.pipeline.intelligence_bot.service.DiffAnalysisService;
import com.pipeline.intelligence_bot.service.FailureAnalysisService;
import com.pipeline.intelligence_bot.service.GitLabService;
import com.pipeline.intelligence_bot.service.PythonAnalysisService;
import com.pipeline.intelligence_bot.service.PipelineIntelligenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/gitlab")
public class GitLabController {

    private final GitLabService gitLabService;
    private final PythonAnalysisService pythonAnalysisService;
    private final FailureAnalysisService failureAnalysisService;
    private final PipelineIntelligenceService pipelineIntelligenceService;
    private DiffAnalysisService diffAnalysisService;



    public GitLabController(GitLabService gitLabService,
                            FailureAnalysisService failureAnalysisService,
                            PythonAnalysisService pythonAnalysisService,
                            DiffAnalysisService diffAnalysisService,
                            PipelineIntelligenceService pipelineIntelligenceService) {

        this.gitLabService = gitLabService;
        this.failureAnalysisService = failureAnalysisService;
        this.pythonAnalysisService = pythonAnalysisService;
        this.diffAnalysisService = diffAnalysisService;
        this.pipelineIntelligenceService = pipelineIntelligenceService;
    }



    @GetMapping("/jobs")
    public List<Map<String, Object>> getPipelineJobs(
            @RequestParam String projectId,
            @RequestParam String pipelineId) {

        return gitLabService.getPipelineJobsAsList(projectId, pipelineId);
    }




    @GetMapping("/logs")
    public String getJobLogs(
            @RequestParam String projectId,
            @RequestParam String jobId) {

        return gitLabService.getJobLogs(projectId, jobId);
    }



    @GetMapping("/pipelineSummary")
    public Map<String, Object> getPipelineSummary(
            @RequestParam String projectId,
            @RequestParam String pipelineId) {

        return gitLabService.buildPipelineSummary(projectId, pipelineId);
    }



    @GetMapping("/firstFailedJob")
    public Map<String, Object> getFirstFailedJob(
            @RequestParam String projectId,
            @RequestParam String pipelineId) {

        List<Map<String, Object>> jobs =
                gitLabService.getPipelineJobsAsList(projectId, pipelineId);

        Map<String, Object> failed =
                gitLabService.findFirstFailedJob(jobs);

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
            @RequestParam String pipelineId) {

        List<Map<String, Object>> jobs =
                gitLabService.getPipelineJobsAsList(projectId, pipelineId);

        return gitLabService.findAllFailedJobs(jobs);
    }



    @GetMapping("/basicFailureReport")
    public Map<String, Object> basicFailureReport(
            @RequestParam String projectId,
            @RequestParam String pipelineId) {

        List<Map<String, Object>> jobs =
                gitLabService.getPipelineJobsAsList(projectId, pipelineId);

        Map<String, Object> failed =
                gitLabService.findFirstFailedJob(jobs);

        if (failed == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "Pipeline successful. No failures detected.");
            return result;
        }
        Map<String, Object> pipelineSummary =
                gitLabService.buildPipelineSummary(projectId, pipelineId);

        String jobId = failed.get("id").toString();
        String jobName = failed.get("name") != null ?
                failed.get("name").toString() : "unknown";
        String stage = failed.get("stage") != null ?
                failed.get("stage").toString() : "unknown";

        String logs = gitLabService.getJobLogs(projectId, jobId);

        Map<String, Object> result = new HashMap<>();

        result.put("projectId", projectId);
        result.put("pipelineId", pipelineId);
        result.put("pipelineSummary", pipelineSummary);

        result.put("failedJobId", jobId);
        result.put("failedJobName", jobName);
        result.put("failedJobStage", stage);

        result.put("logLength", logs.length());
        result.put("logsPreview",
                logs.substring(0, Math.min(500, logs.length())));

        return result;
    }

    @GetMapping("/analyzeWithPython")
    public Map<String, Object> analyzePipelineWithPython(
            @RequestParam String projectId,
            @RequestParam String pipelineId) {

        List<Map<String, Object>> jobs =
                gitLabService.getPipelineJobsAsList(projectId, pipelineId);

        if (jobs == null || jobs.isEmpty()) {

            Map<String, Object> result = new HashMap<>();
            result.put("error", "No jobs found");
            return result;
        }

        Map<String, Object> failedJob = null;

        for (Map<String, Object> job : jobs) {

            if ("failed".equalsIgnoreCase(job.get("status").toString())) {

                failedJob = job;
                break;
            }
        }

        if (failedJob == null) {

            Map<String, Object> result = new HashMap<>();
            result.put("message", "No failures found");
            return result;
        }

        String jobId = failedJob.get("id").toString();

        String logs = gitLabService.getJobLogs(projectId, jobId);

        Map<String, Object> pythonResult;

        try {
            pythonResult = pythonAnalysisService.analyzeLogs(logs);
        } catch (Exception e) {

            pythonResult = new HashMap<>();

            pythonResult.put("analysisError", e.getMessage());
            pythonResult.put("status", "Python analysis failed");
        }

        pythonResult.put("projectId", projectId);
        pythonResult.put("pipelineId", pipelineId);
        pythonResult.put("jobId", jobId);

        Map<String, Object> pipelineSummary =
                gitLabService.buildPipelineSummary(projectId, pipelineId);

        pythonResult.put("pipelineSummary", pipelineSummary);

        String dependencyName =
                extractDependencyNameFromPythonResult(pythonResult);

        Map<String, Object> pipeline =
                gitLabService.getPipelineDetails(projectId, pipelineId);

        if (pipeline != null && pipeline.get("sha") != null) {

            String commitSha = pipeline.get("sha").toString();

            List<Map<String, Object>> diffs =
                    gitLabService.getCommitDiffAsList(projectId, commitSha);

            pythonResult.put("commitId", commitSha);

            String failureFile =
                    pythonResult.get("file") != null
                            ? pythonResult.get("file").toString()
                            : null;

            String failureLine =
                    pythonResult.get("line") != null
                            ? pythonResult.get("line").toString()
                            : null;



            Map<String, Object> precisionResult =
                    diffAnalysisService.analyzeDiffForFailure(
                            diffs,
                            failureFile,
                            failureLine,
                            dependencyName
                    );

            pythonResult.putAll(precisionResult);


            Map<String, Object> historyResult =
                    failureAnalysisService.analyzePipelineFailureWithHistory(
                            projectId,
                            pipelineId,
                            failureFile,
                            failureLine,
                            dependencyName
                    );


            pythonResult.putAll(historyResult);



            pythonResult.put("allChanges", diffs);
        }

        return pythonResult;
    }
    private String extractDependencyNameFromPythonResult(
            Map<String, Object> pythonResult) {

        if (pythonResult == null) {
            return null;
        }

        Object errorMessageObj = pythonResult.get("errorMessage");

        if (errorMessageObj == null) {
            return null;
        }

        String errorMessage = errorMessageObj.toString();


        Pattern pattern =
                Pattern.compile("must be unique:\\s*([a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+)");

        Matcher matcher = pattern.matcher(errorMessage);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    @GetMapping("/analyzePipelineFully")
    public Map<String, Object> analyzePipelineFully(
            @RequestParam String projectId,
            @RequestParam String pipelineId) {

        Map<String, Object> finalResult = new HashMap<>();

        try {


            List<Map<String, Object>> jobs =
                    gitLabService.getPipelineJobsAsList(projectId, pipelineId);

            if (jobs == null || jobs.isEmpty()) {

                finalResult.put("error", "No jobs found");
                return finalResult;
            }


            Map<String, Object> pipelineSummary =
                    gitLabService.buildPipelineSummary(projectId, pipelineId);

            finalResult.put("pipelineSummary", pipelineSummary);



            Map<String, Object> pipeline =
                    gitLabService.getPipelineDetails(projectId, pipelineId);

            String pipelineCommit = null;

            if (pipeline != null && pipeline.get("sha") != null) {
                pipelineCommit = pipeline.get("sha").toString();
            }

            finalResult.put("pipelineCommit", pipelineCommit);



            List<Map<String, Object>> failedJobs =
                    gitLabService.findAllFailedJobs(jobs);


            List<Map<String, Object>> jobAnalyses =
                    new ArrayList<>();


            String primaryFailureJobId = null;


            for (Map<String, Object> job : failedJobs) {

                Map<String, Object> jobResult =
                        new HashMap<>();

                String jobId =
                        job.get("id").toString();

                String jobName =
                        job.get("name") != null
                                ? job.get("name").toString()
                                : "unknown";

                String stage =
                        job.get("stage") != null
                                ? job.get("stage").toString()
                                : "unknown";


                jobResult.put("jobId", jobId);
                jobResult.put("jobName", jobName);
                jobResult.put("stage", stage);


                if (primaryFailureJobId == null) {
                    primaryFailureJobId = jobId;
                }



                String logs =
                        gitLabService.getJobLogs(projectId, jobId);



                Map<String, Object> pythonResult =
                        pythonAnalysisService.analyzeLogs(logs);

                pythonResult.put("projectId", projectId);
                pythonResult.put("pipelineId", pipelineId);
                pythonResult.put("jobId", jobId);
                pythonResult.put("jobName", jobName);
                pythonResult.put("stage", stage);



                String dependencyName =
                        extractDependencyNameFromPythonResult(pythonResult);


                if (pipelineCommit != null) {

                    pythonResult.put("commitId", pipelineCommit);



                    List<Map<String, Object>> diffs =
                            gitLabService.getCommitDiffAsList(
                                    projectId,
                                    pipelineCommit
                            );


                    String failureFile =
                            pythonResult.get("file") != null
                                    ? pythonResult.get("file").toString()
                                    : null;

                    String failureLine =
                            pythonResult.get("line") != null
                                    ? pythonResult.get("line").toString()
                                    : null;


                    Map<String, Object> diffResult =
                            diffAnalysisService.analyzeDiffForFailure(
                                    diffs,
                                    failureFile,
                                    failureLine,
                                    dependencyName
                            );

                    pythonResult.putAll(diffResult);



                    Map<String, Object> historyResult =
                            failureAnalysisService.analyzePipelineFailureWithHistory(
                                    projectId,
                                    pipelineId,
                                    failureFile,
                                    failureLine,
                                    dependencyName
                            );

                    pythonResult.putAll(historyResult);


                    pythonResult.put("allChanges", diffs);
                }


                jobResult.put("failureAnalysis", pythonResult);

                jobAnalyses.add(jobResult);
            }



            Map<String, Object> pipelineIntelligence =
                    pipelineIntelligenceService.buildCascadingFailureIntelligence(jobs);


            finalResult.put("jobAnalyses", jobAnalyses);

            finalResult.put("pipelineIntelligence", pipelineIntelligence);


            finalResult.put("projectId", projectId);
            finalResult.put("pipelineId", pipelineId);

            return finalResult;




        }
        catch (Exception e) {

            finalResult.put("error", e.getMessage());

            return finalResult;
        }
    }



}