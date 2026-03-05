package com.pipeline.intelligence_bot.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FailureAnalysisService {

    @Autowired
    private GitLabService gitLabService;
    @Autowired
    private DiffAnalysisService diffAnalysisService;


    public Map<String, String> analyzeFailure(String log) {

        Map<String, String> result = new HashMap<>();

        String failureType = "Unknown Failure";
        String rootCause = "Could not determine root cause";
        String fixRecommendation = "Check logs manually";

        if (log == null) {
            result.put("failureType", failureType);
            result.put("rootCause", rootCause);
            result.put("fixRecommendation", fixRecommendation);
            return result;
        }

        String lowerLog = log.toLowerCase();

        if (lowerLog.contains("duplicate declaration")
                || lowerLog.contains("must be unique")
                || lowerLog.contains("dependency")) {

            failureType = "Dependency Configuration Failure";
            rootCause = "Duplicate or conflicting dependency detected";
            fixRecommendation =
                    "Remove duplicate dependency in pom.xml or build file";
        }

        else if (lowerLog.contains("compilation failure")
                || lowerLog.contains("cannot find symbol")) {

            failureType = "Code Compilation Failure";
            rootCause = "Code syntax or missing symbol error";
            fixRecommendation =
                    "Fix compilation errors in source code";
        }

        else if (lowerLog.contains("test failed")
                || lowerLog.contains("there are test failures")) {

            failureType = "Test Failure";
            rootCause = "Unit tests failed";
            fixRecommendation =
                    "Fix failing test cases or logic";
        }

        else if (lowerLog.contains("java version")
                || lowerLog.contains("environment")) {

            failureType = "Environment Failure";
            rootCause = "Incorrect runtime environment configuration";
            fixRecommendation =
                    "Verify Java version and environment variables";
        }

        result.put("failureType", failureType);
        result.put("rootCause", rootCause);
        result.put("fixRecommendation", fixRecommendation);

        return result;
    }



    public Map<String, String> analyzeCommitDiff(String commitDiff) {

        Map<String, String> result = new HashMap<>();

        if (commitDiff == null || commitDiff.isEmpty()) {

            result.put("changeImpact", "Unknown");
            result.put("analysis", "No diff available");

            return result;
        }

        if (commitDiff.contains("pom.xml")) {

            result.put("changeImpact", "Build Configuration Change");

            result.put(
                    "analysis",
                    "Dependency or build configuration modified in pom.xml"
            );
        }

        else if (commitDiff.contains(".gitlab-ci.yml")) {

            result.put("changeImpact", "Pipeline Configuration Change");

            result.put(
                    "analysis",
                    "CI/CD pipeline configuration modified"
            );
        }

        else if (commitDiff.contains("build.gradle")) {

            result.put("changeImpact", "Build System Change");

            result.put(
                    "analysis",
                    "Gradle build configuration modified"
            );
        }

        else if (commitDiff.contains(".java")) {

            result.put("changeImpact", "Source Code Change");

            result.put(
                    "analysis",
                    "Application source code modified"
            );
        }

        else {

            result.put("changeImpact", "Unknown Change");

            result.put(
                    "analysis",
                    "Unable to classify change impact"
            );
        }

        return result;
    }



    public Map<String, Object> findActualRootCauseCommit(
            String projectId,
            String failureFile,
            String currentCommitSha,
            String dependencyName) {

        Map<String, Object> result = new HashMap<>();

        try {

            String rootCauseCommit =
                    gitLabService.findRootCauseCommitForFile(
                            projectId,
                            failureFile,
                            currentCommitSha,
                            dependencyName

                    );

            if (rootCauseCommit != null) {

                result.put("actualRootCauseCommit", rootCauseCommit);

                result.put(
                        "analysis",
                        "Failure likely introduced in earlier commit "
                                + rootCauseCommit
                );

                result.put("confidenceLevel", "VERY HIGH");

            } else {

                result.put(
                        "analysis",
                        "No earlier commit modifying file found"
                );

                result.put("confidenceLevel", "LOW");
            }

        } catch (Exception e) {

            result.put(
                    "analysis",
                    "Error analyzing Git history: " + e.getMessage()
            );

            result.put("confidenceLevel", "LOW");
        }

        return result;
    }
    public Map<String, Object> analyzePipelineFailureWithHistory(

            String projectId,
            String pipelineId,
            String failureFile,
            String failureLine,
            String dependencyName

    ) {

        Map<String, Object> result = new HashMap<>();

        try {



            Map<String, Object> pipeline =
                    gitLabService.getPipelineDetails(
                            projectId,
                            pipelineId
                    );

            if (pipeline == null) {

                result.put("error", "Pipeline not found");
                return result;
            }

            String commitSha =
                    pipeline.get("sha").toString();

            result.put("pipelineCommit", commitSha);




            List<Map<String, Object>> diffs =
                    gitLabService.getCommitDiffAsList(
                            projectId,
                            commitSha
                    );




            Map<String, Object> diffAnalysis =
                    diffAnalysisService.analyzeDiffForFailure(
                            diffs,
                            failureFile,
                            failureLine,
                            dependencyName
                    );

            result.put("diffAnalysis", diffAnalysis);


            boolean commitMatch =
                    diffAnalysis.get("rootCauseCommitMatch") != null &&
                            (boolean) diffAnalysis.get("rootCauseCommitMatch");




            if (!commitMatch) {

                String rootCauseCommit =
                        gitLabService.findRootCauseCommitForFile(
                                projectId,
                                failureFile,
                                commitSha,
                                dependencyName
                        );

                if (rootCauseCommit != null) {

                    result.put(
                            "actualRootCauseCommit",
                            rootCauseCommit
                    );

                    result.put(
                            "finalAnalysis",
                            "Pipeline failed in commit "
                                    + commitSha +
                                    " but root cause introduced in earlier commit "
                                    + rootCauseCommit
                    );

                    result.put(
                            "confidenceLevel",
                            "VERY HIGH"
                    );

                } else {

                    result.put(
                            "finalAnalysis",
                            "Failure not caused by this commit but root cause commit not found"
                    );

                    result.put(
                            "confidenceLevel",
                            "MEDIUM"
                    );
                }

            }

            else {

                result.put(
                        "actualRootCauseCommit",
                        commitSha
                );

                result.put(
                        "finalAnalysis",
                        "Failure directly caused by this commit"
                );

                result.put(
                        "confidenceLevel",
                        "CRITICAL"
                );
            }




            result.put(
                    "modifiedFiles",
                    diffAnalysis.get("modifiedFiles")
            );

            return result;

        }

        catch (Exception e) {

            result.put("error", e.getMessage());
            result.put("confidenceLevel", "LOW");

            return result;
        }
    }
}