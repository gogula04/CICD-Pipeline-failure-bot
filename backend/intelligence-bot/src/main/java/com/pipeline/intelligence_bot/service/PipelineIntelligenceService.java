package com.pipeline.intelligence_bot.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PipelineIntelligenceService {

    public Map<String, Object> buildCascadingFailureIntelligence(List<Map<String, Object>> jobs) {
        Map<String, Object> out = new LinkedHashMap<>();

        if (jobs == null || jobs.isEmpty()) {
            out.put("primaryFailure", null);
            out.put("coPrimaryFailures", Collections.emptyList());
            out.put("secondaryFailures", Collections.emptyList());
            out.put("independentFailures", Collections.emptyList());
            out.put("downstreamImpactedJobs", Collections.emptyList());
            out.put("dependencyChain", Collections.emptyList());
            out.put("executionOrder", Collections.emptyList());
            out.put("stageOrder", Collections.emptyList());
            out.put("stageHealth", Collections.emptyList());
            out.put("blockedStages", Collections.emptyList());
            out.put("failureBlastRadius", 0);
            return out;
        }

        List<Map<String, Object>> sortedJobs = new ArrayList<>(jobs);
        sortedJobs.sort(Comparator.comparingLong(job -> toLong(job.get("id"))));

        List<String> executionOrder = new ArrayList<>();
        Map<String, Integer> stageIndex = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> jobsByStage = new LinkedHashMap<>();

        for (Map<String, Object> job : sortedJobs) {
            executionOrder.add(toStr(job.get("id")));
            String stage = normalizedStage(job.get("stage"));
            stageIndex.putIfAbsent(stage, stageIndex.size());
            jobsByStage.computeIfAbsent(stage, unused -> new ArrayList<>()).add(job);
        }

        int earliestFailStage = Integer.MAX_VALUE;
        List<Map<String, Object>> failedJobs = new ArrayList<>();

        for (Map<String, Object> job : sortedJobs) {
            if (!"failed".equalsIgnoreCase(toStr(job.get("status")))) {
                continue;
            }

            failedJobs.add(job);

            if (Boolean.TRUE.equals(job.get("allow_failure"))) {
                continue;
            }

            int idx = stageIndex.getOrDefault(normalizedStage(job.get("stage")), Integer.MAX_VALUE);
            earliestFailStage = Math.min(earliestFailStage, idx);
        }

        if (earliestFailStage == Integer.MAX_VALUE && !failedJobs.isEmpty()) {
            earliestFailStage = stageIndex.getOrDefault(normalizedStage(failedJobs.get(0).get("stage")), Integer.MAX_VALUE);
        }

        if (earliestFailStage == Integer.MAX_VALUE) {
            out.put("primaryFailure", null);
            out.put("coPrimaryFailures", Collections.emptyList());
            out.put("secondaryFailures", Collections.emptyList());
            out.put("independentFailures", Collections.emptyList());
            out.put("downstreamImpactedJobs", Collections.emptyList());
            out.put("dependencyChain", Collections.emptyList());
            out.put("executionOrder", executionOrder);
            out.put("stageOrder", new ArrayList<>(stageIndex.keySet()));
            out.put("stageHealth", buildStageHealth(jobsByStage));
            out.put("blockedStages", Collections.emptyList());
            out.put("failureBlastRadius", 0);
            return out;
        }

        List<Map<String, Object>> primaryStageFailures = new ArrayList<>();
        List<Map<String, Object>> coPrimaryFailures = new ArrayList<>();
        List<Map<String, Object>> secondaryFailures = new ArrayList<>();
        List<Map<String, Object>> independentFailures = new ArrayList<>();
        List<Map<String, Object>> downstreamImpactedJobs = new ArrayList<>();
        List<Map<String, Object>> dependencyChain = new ArrayList<>();
        Set<String> blockedStages = new LinkedHashSet<>();

        for (Map<String, Object> job : sortedJobs) {
            String status = toStr(job.get("status")).toLowerCase();
            String stage = normalizedStage(job.get("stage"));
            int idx = stageIndex.getOrDefault(stage, Integer.MAX_VALUE);
            boolean allowFailure = Boolean.TRUE.equals(job.get("allow_failure"));

            if ("failed".equals(status)) {
                if (allowFailure) {
                    Map<String, Object> independent = minJob(job);
                    independent.put("impactReason", "Allowed to fail and may not block the pipeline");
                    independentFailures.add(independent);
                } else if (idx == earliestFailStage) {
                    primaryStageFailures.add(job);
                    blockedStages.add(stage);
                } else if (idx > earliestFailStage) {
                    Map<String, Object> secondary = minJob(job);
                    secondary.put("impactReason", "Failed after the primary upstream failure");
                    secondary.put("causedByStageIndex", earliestFailStage);
                    secondaryFailures.add(secondary);
                    blockedStages.add(stage);
                } else {
                    Map<String, Object> independent = minJob(job);
                    independent.put("impactReason", "Failed outside the inferred primary cascade path");
                    independentFailures.add(independent);
                }
            }

            if (idx > earliestFailStage && ("skipped".equals(status) || "canceled".equals(status) || "manual".equals(status))) {
                Map<String, Object> impacted = minJob(job);
                impacted.put("impactReason", guessImpactReason(status));
                downstreamImpactedJobs.add(impacted);
                blockedStages.add(stage);
            }
        }

        primaryStageFailures.sort(Comparator.comparingLong(job -> toLong(job.get("id"))));

        Map<String, Object> primaryFailure = minJob(primaryStageFailures.get(0));

        for (int index = 1; index < primaryStageFailures.size(); index++) {
            coPrimaryFailures.add(minJob(primaryStageFailures.get(index)));
        }

        String primaryStage = normalizedStage(primaryFailure.get("stage"));
        String primaryJobId = toStr(primaryFailure.get("jobId"));

        for (Map<String, Object> job : sortedJobs) {
            String stage = normalizedStage(job.get("stage"));
            int idx = stageIndex.getOrDefault(stage, Integer.MAX_VALUE);

            if (idx > earliestFailStage) {
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("fromJobId", primaryJobId);
                link.put("toJobId", toStr(job.get("id")));
                link.put("fromStage", primaryStage);
                link.put("toStage", stage);
                dependencyChain.add(link);
            }
        }

        out.put("primaryFailure", primaryFailure);
        out.put("coPrimaryFailures", coPrimaryFailures);
        out.put("secondaryFailures", secondaryFailures);
        out.put("independentFailures", independentFailures);
        out.put("downstreamImpactedJobs", downstreamImpactedJobs);
        out.put("dependencyChain", dependencyChain);
        out.put("executionOrder", executionOrder);
        out.put("stageOrder", new ArrayList<>(stageIndex.keySet()));
        out.put("stageHealth", buildStageHealth(jobsByStage));
        out.put("blockedStages", new ArrayList<>(blockedStages));
        out.put("earliestFailingStageIndex", earliestFailStage);
        out.put("earliestFailingStageName", primaryStage);
        out.put("totalJobs", jobs.size());
        out.put("totalFailedJobs", countStatus(jobs, "failed"));
        out.put("totalSkippedJobs", countStatus(jobs, "skipped"));
        out.put("totalCanceledJobs", countStatus(jobs, "canceled"));
        out.put("totalManualJobs", countStatus(jobs, "manual"));
        out.put("failureBlastRadius", secondaryFailures.size() + downstreamImpactedJobs.size());

        return out;
    }

    private List<Map<String, Object>> buildStageHealth(Map<String, List<Map<String, Object>>> jobsByStage) {
        List<Map<String, Object>> stageHealth = new ArrayList<>();

        for (Map.Entry<String, List<Map<String, Object>>> entry : jobsByStage.entrySet()) {
            Map<String, Object> stage = new LinkedHashMap<>();
            stage.put("stage", entry.getKey());
            stage.put("totalJobs", entry.getValue().size());
            stage.put("failedJobs", countStatus(entry.getValue(), "failed"));
            stage.put("successJobs", countStatus(entry.getValue(), "success"));
            stage.put("skippedJobs", countStatus(entry.getValue(), "skipped"));
            stage.put("canceledJobs", countStatus(entry.getValue(), "canceled"));
            stage.put("manualJobs", countStatus(entry.getValue(), "manual"));
            stageHealth.add(stage);
        }

        return stageHealth;
    }

    private Map<String, Object> minJob(Map<String, Object> job) {
        Map<String, Object> min = new HashMap<>();
        min.put("jobId", toStr(job.get("id")));
        min.put("jobName", toStr(job.get("name")));
        min.put("stage", normalizedStage(job.get("stage")));
        min.put("status", toStr(job.get("status")));
        min.put("allowFailure", Boolean.TRUE.equals(job.get("allow_failure")));
        min.put("when", toStr(job.get("when")));
        min.put("startedAt", toStr(job.get("started_at")));
        min.put("finishedAt", toStr(job.get("finished_at")));
        return min;
    }

    private int countStatus(List<Map<String, Object>> jobs, String status) {
        int count = 0;

        for (Map<String, Object> job : jobs) {
            if (status.equalsIgnoreCase(toStr(job.get("status")))) {
                count++;
            }
        }

        return count;
    }

    private String guessImpactReason(String status) {
        if ("skipped".equals(status)) {
            return "Skipped because an upstream stage failed";
        }

        if ("canceled".equals(status)) {
            return "Canceled after pipeline failure halted later execution";
        }

        if ("manual".equals(status)) {
            return "Manual job was not executed after the blocking failure";
        }

        return "Downstream job impacted by upstream execution failure";
    }

    private String normalizedStage(Object stage) {
        String value = toStr(stage);
        return value.isBlank() ? "unknown" : value;
    }

    private String toStr(Object value) {
        return value == null ? "" : value.toString();
    }

    private long toLong(Object value) {
        try {
            return value == null ? 0L : Long.parseLong(value.toString());
        } catch (Exception exception) {
            return 0L;
        }
    }
}
