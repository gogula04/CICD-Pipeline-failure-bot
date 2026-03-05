package com.pipeline.intelligence_bot.service;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PipelineIntelligenceService {

    public Map<String, Object> buildCascadingFailureIntelligence(
            List<Map<String, Object>> jobs
    ) {

        Map<String, Object> out = new HashMap<>();

        if (jobs == null || jobs.isEmpty()) {

            out.put("primaryFailure", null);
            out.put("coPrimaryFailures", Collections.emptyList());
            out.put("secondaryFailures", Collections.emptyList());
            out.put("independentFailures", Collections.emptyList());
            out.put("downstreamImpactedJobs", Collections.emptyList());
            out.put("dependencyChain", Collections.emptyList());
            out.put("executionOrder", Collections.emptyList());
            out.put("stageOrder", Collections.emptyList());

            return out;
        }

        List<Map<String, Object>> sorted = new ArrayList<>(jobs);
        sorted.sort(Comparator.comparingLong(j -> toLong(j.get("id"))));

        List<String> executionOrder = new ArrayList<>();
        for (Map<String, Object> job : sorted) {
            executionOrder.add(toStr(job.get("id")));
        }

        LinkedHashMap<String, Integer> stageIndex = new LinkedHashMap<>();

        for (Map<String, Object> job : sorted) {

            String stage = toStr(job.get("stage"));

            if (stage.isBlank()) {
                stage = "unknown";
            }

            stageIndex.putIfAbsent(stage, stageIndex.size());
        }

        int earliestFailStage = Integer.MAX_VALUE;

        for (Map<String, Object> job : sorted) {

            String status = toStr(job.get("status")).toLowerCase();

            if ("failed".equals(status)) {

                String stage = toStr(job.get("stage"));

                int idx = stageIndex.getOrDefault(stage, Integer.MAX_VALUE);

                earliestFailStage = Math.min(earliestFailStage, idx);
            }
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

            return out;
        }

        List<Map<String, Object>> primaryStageFailures = new ArrayList<>();

        List<Map<String, Object>> secondaryFailures = new ArrayList<>();

        List<Map<String, Object>> independentFailures = new ArrayList<>();

        List<Map<String, Object>> downstreamImpactedJobs = new ArrayList<>();

        for (Map<String, Object> job : sorted) {

            String status = toStr(job.get("status")).toLowerCase();
            String stage = toStr(job.get("stage"));
            int idx = stageIndex.getOrDefault(stage, Integer.MAX_VALUE);

            if ("failed".equals(status)) {

                if (idx == earliestFailStage) {

                    primaryStageFailures.add(job);

                } else if (idx > earliestFailStage) {

                    Map<String, Object> sec = minJob(job);

                    sec.put("causedByStageIndex", earliestFailStage);
                    sec.put("impactReason", "Failed due to upstream stage failure");

                    secondaryFailures.add(sec);
                }
            }

            if ("skipped".equals(status)
                    || "canceled".equals(status)
                    || "manual".equals(status)) {

                if (idx > earliestFailStage) {

                    Map<String, Object> impacted = minJob(job);

                    impacted.put("impactReason", guessImpactReason(status));

                    downstreamImpactedJobs.add(impacted);
                }
            }
        }

        primaryStageFailures.sort(
                Comparator.comparingLong(j -> toLong(j.get("id")))
        );

        Map<String, Object> primaryFailure = primaryStageFailures.get(0);

        List<Map<String, Object>> coPrimaryFailures = new ArrayList<>();

        for (int i = 1; i < primaryStageFailures.size(); i++) {

            coPrimaryFailures.add(minJob(primaryStageFailures.get(i)));
        }

        List<Map<String, Object>> dependencyChain = new ArrayList<>();

        for (Map<String, Object> job : sorted) {

            String stage = toStr(job.get("stage"));

            int idx = stageIndex.getOrDefault(stage, Integer.MAX_VALUE);

            if (idx > earliestFailStage) {

                Map<String, Object> link = new HashMap<>();

                link.put("fromJobId", toStr(primaryFailure.get("id")));
                link.put("toJobId", toStr(job.get("id")));
                link.put("fromStage", toStr(primaryFailure.get("stage")));
                link.put("toStage", stage);

                dependencyChain.add(link);
            }
        }

        Map<String, Object> failureGraph = new HashMap<>();

        failureGraph.put("primaryFailure", minJob(primaryFailure));
        failureGraph.put("coPrimaryFailures", coPrimaryFailures);
        failureGraph.put("secondaryFailures", secondaryFailures);
        failureGraph.put("independentFailures", independentFailures);
        failureGraph.put("downstreamImpactedJobs", downstreamImpactedJobs);
        failureGraph.put("dependencyChain", dependencyChain);
        failureGraph.put("executionOrder", executionOrder);
        failureGraph.put("stageOrder", new ArrayList<>(stageIndex.keySet()));
        failureGraph.put("earliestFailingStageIndex", earliestFailStage);
        failureGraph.put("earliestFailingStageName",
                toStr(primaryFailure.get("stage")));

        failureGraph.put("totalJobs", jobs.size());
        failureGraph.put("totalFailedJobs", countStatus(jobs, "failed"));
        failureGraph.put("totalSkippedJobs", countStatus(jobs, "skipped"));
        failureGraph.put("totalCanceledJobs", countStatus(jobs, "canceled"));
        failureGraph.put("totalManualJobs", countStatus(jobs, "manual"));

        out.putAll(failureGraph);

        return out;
    }



    private Map<String, Object> minJob(Map<String, Object> job) {

        Map<String, Object> m = new HashMap<>();

        m.put("jobId", toStr(job.get("id")));
        m.put("jobName", toStr(job.get("name")));
        m.put("stage", toStr(job.get("stage")));
        m.put("status", toStr(job.get("status")));

        return m;
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
            return "Skipped due to upstream failure";
        }

        if ("canceled".equals(status)) {
            return "Canceled due to pipeline failure";
        }

        if ("manual".equals(status)) {
            return "Manual job not executed";
        }

        return "Downstream impacted";
    }

    private String toStr(Object o) {

        return o == null ? "" : o.toString();
    }

    private long toLong(Object o) {

        try {

            if (o == null) return 0;

            return Long.parseLong(o.toString());

        } catch (Exception e) {

            return 0;
        }
    }
}