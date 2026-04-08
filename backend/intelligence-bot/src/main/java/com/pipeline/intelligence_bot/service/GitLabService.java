package com.pipeline.intelligence_bot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GitLabService {

    @Value("${gitlab.api.url}")
    private String gitlabApiUrl;

    @Value("${gitlab.token:}")
    private String privateToken;

    private final RestTemplate restTemplate;

    public GitLabService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private HttpEntity<String> getAuthEntity() {
        validateConfigured();

        HttpHeaders headers = new HttpHeaders();
        headers.set("PRIVATE-TOKEN", privateToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    private void validateConfigured() {
        if (gitlabApiUrl == null || gitlabApiUrl.isBlank()) {
            throw new IllegalArgumentException("gitlab.api.url is not configured");
        }

        if (privateToken == null || privateToken.isBlank()) {
            throw new IllegalArgumentException("GitLab token is not configured. Set GITLAB_TOKEN.");
        }
    }

    private void validateProjectPipeline(String projectId, String pipelineId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is required");
        }

        if (pipelineId == null || pipelineId.isBlank()) {
            throw new IllegalArgumentException("pipelineId is required");
        }
    }

    private void validateProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is required");
        }
    }

    private void validateJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String projectPath(String projectId) {
        return gitlabApiUrl + "/projects/" + enc(projectId);
    }

    public Map<String, Object> getProjectDetails(String projectId) {
        validateProject(projectId);

        String url = projectPath(projectId);

        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        getAuthEntity(),
                        new ParameterizedTypeReference<Map<String, Object>>() {
                        }
                );

        return response.getBody();
    }

    public Map<String, Object> getPipelineDetails(String projectId, String pipelineId) {
        validateProjectPipeline(projectId, pipelineId);

        String url = projectPath(projectId) + "/pipelines/" + enc(pipelineId);

        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        getAuthEntity(),
                        new ParameterizedTypeReference<Map<String, Object>>() {
                        }
                );

        return response.getBody();
    }

    public List<Map<String, Object>> getRecentPipelines(String projectId, String ref, int limit) {
        validateProject(projectId);

        StringBuilder url = new StringBuilder(projectPath(projectId))
                .append("/pipelines?per_page=")
                .append(Math.max(1, limit));

        if (ref != null && !ref.isBlank()) {
            url.append("&ref=").append(enc(ref));
        }

        ResponseEntity<List<Map<String, Object>>> response =
                restTemplate.exchange(
                        url.toString(),
                        HttpMethod.GET,
                        getAuthEntity(),
                        new ParameterizedTypeReference<List<Map<String, Object>>>() {
                        }
                );

        List<Map<String, Object>> body = response.getBody();
        return body == null ? Collections.emptyList() : body;
    }

    public String getPipelineJobsRaw(String projectId, String pipelineId) {
        validateProjectPipeline(projectId, pipelineId);

        String url = projectPath(projectId)
                + "/pipelines/" + enc(pipelineId)
                + "/jobs?per_page=100&page=1";

        ResponseEntity<String> response =
                restTemplate.exchange(url, HttpMethod.GET, getAuthEntity(), String.class);

        return response.getBody();
    }

    public List<Map<String, Object>> getPipelineJobsAsList(String projectId, String pipelineId) {
        validateProjectPipeline(projectId, pipelineId);

        List<Map<String, Object>> all = new ArrayList<>();
        int page = 1;
        int perPage = 100;

        while (true) {
            String url = projectPath(projectId)
                    + "/pipelines/" + enc(pipelineId)
                    + "/jobs?per_page=" + perPage
                    + "&page=" + page;

            ResponseEntity<List<Map<String, Object>>> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            getAuthEntity(),
                            new ParameterizedTypeReference<List<Map<String, Object>>>() {
                            }
                    );

            List<Map<String, Object>> batch = response.getBody();

            if (batch == null || batch.isEmpty()) {
                break;
            }

            all.addAll(batch);

            if (batch.size() < perPage) {
                break;
            }

            page++;
        }

        return all;
    }

    public Map<String, Object> getJobDetails(String projectId, String jobId) {
        validateProject(projectId);
        validateJob(jobId);

        String url = projectPath(projectId) + "/jobs/" + enc(jobId);

        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        getAuthEntity(),
                        new ParameterizedTypeReference<Map<String, Object>>() {
                        }
                );

        return response.getBody();
    }

    public String getJobLogs(String projectId, String jobId) {
        validateProject(projectId);
        validateJob(jobId);

        String url = projectPath(projectId) + "/jobs/" + enc(jobId) + "/trace";

        ResponseEntity<String> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        getAuthEntity(),
                        String.class
                );

        return response.getBody();
    }

    public Map<String, Object> findFirstFailedJob(List<Map<String, Object>> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> sortedJobs = new ArrayList<>(jobs);
        sortedJobs.sort((left, right) -> Long.compare(toLong(left.get("id")), toLong(right.get("id"))));

        Map<String, Integer> stageOrder = new LinkedHashMap<>();
        for (Map<String, Object> job : sortedJobs) {
            String stage = toStr(job.get("stage"));
            String normalizedStage = stage.isBlank() ? "unknown" : stage;
            stageOrder.putIfAbsent(normalizedStage, stageOrder.size());
        }

        Map<String, Object> best = null;

        for (Map<String, Object> job : sortedJobs) {
            String status = toStr(job.get("status")).toLowerCase();

            if (!"failed".equals(status)) {
                continue;
            }

            if (best == null) {
                best = job;
                continue;
            }

            String currentStage = toStr(job.get("stage"));
            String bestStage = toStr(best.get("stage"));
            int currentStageIndex = stageOrder.getOrDefault(currentStage.isBlank() ? "unknown" : currentStage, Integer.MAX_VALUE);
            int bestStageIndex = stageOrder.getOrDefault(bestStage.isBlank() ? "unknown" : bestStage, Integer.MAX_VALUE);

            if (currentStageIndex < bestStageIndex) {
                best = job;
                continue;
            }

            if (currentStageIndex > bestStageIndex) {
                continue;
            }

            String currentStartedAt = toStr(job.get("started_at"));
            String bestStartedAt = toStr(best.get("started_at"));

            if (!currentStartedAt.isBlank() && !bestStartedAt.isBlank()) {
                if (currentStartedAt.compareTo(bestStartedAt) < 0) {
                    best = job;
                }
            } else if (toLong(job.get("id")) < toLong(best.get("id"))) {
                best = job;
            }
        }

        return best;
    }

    public List<Map<String, Object>> findAllFailedJobs(List<Map<String, Object>> jobs) {
        List<Map<String, Object>> failed = new ArrayList<>();

        if (jobs == null) {
            return failed;
        }

        for (Map<String, Object> job : jobs) {
            if ("failed".equalsIgnoreCase(toStr(job.get("status")))) {
                failed.add(job);
            }
        }

        return failed;
    }

    public Map<String, Object> buildPipelineSummary(String projectId, String pipelineId) {
        Map<String, Object> pipeline = getPipelineDetails(projectId, pipelineId);
        List<Map<String, Object>> jobs = getPipelineJobsAsList(projectId, pipelineId);

        return buildPipelineSummary(projectId, pipelineId, jobs, pipeline);
    }

    public Map<String, Object> buildPipelineSummary(
            String projectId,
            String pipelineId,
            List<Map<String, Object>> jobs,
            Map<String, Object> pipeline
    ) {

        int total = jobs == null ? 0 : jobs.size();
        int failed = 0;
        int success = 0;
        int running = 0;
        int pending = 0;
        int canceled = 0;
        int skipped = 0;
        int manual = 0;
        int created = 0;

        List<String> failedJobIds = new ArrayList<>();
        List<String> failedJobNames = new ArrayList<>();
        Set<String> stages = new LinkedHashSet<>();
        Map<String, Integer> jobStatuses = new LinkedHashMap<>();

        for (Map<String, Object> job : jobs) {
            String status = toStr(job.get("status")).toLowerCase();
            String stage = toStr(job.get("stage"));

            if (!stage.isBlank()) {
                stages.add(stage);
            }

            jobStatuses.put(status, jobStatuses.getOrDefault(status, 0) + 1);

            switch (status) {
                case "failed" -> {
                    failed++;
                    failedJobIds.add(toStr(job.get("id")));
                    failedJobNames.add(toStr(job.get("name")));
                }
                case "success" -> success++;
                case "running" -> running++;
                case "pending" -> pending++;
                case "canceled" -> canceled++;
                case "skipped" -> skipped++;
                case "manual" -> manual++;
                case "created" -> created++;
                default -> {
                    // keep in status map only
                }
            }
        }

        double failureRate = total == 0 ? 0.0 : ((double) failed / total) * 100.0;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("projectId", projectId);
        summary.put("pipelineId", pipelineId);
        summary.put("pipelineStatus", pipeline == null ? null : pipeline.get("status"));
        summary.put("pipelineRef", pipeline == null ? null : pipeline.get("ref"));
        summary.put("pipelineSource", pipeline == null ? null : pipeline.get("source"));
        summary.put("totalJobs", total);
        summary.put("passedJobs", success);
        summary.put("failedJobs", failed);
        summary.put("runningJobs", running);
        summary.put("pendingJobs", pending);
        summary.put("canceledJobs", canceled);
        summary.put("skippedJobs", skipped);
        summary.put("manualJobs", manual);
        summary.put("createdJobs", created);
        summary.put("failureRatePercent", failureRate);
        summary.put("failedJobIds", failedJobIds);
        summary.put("failedJobNames", failedJobNames);
        summary.put("distinctStages", new ArrayList<>(stages));
        summary.put("jobStatuses", jobStatuses);

        return summary;
    }

    public String getCommitDetails(String projectId, String commitSha) {
        validateProject(projectId);

        String url = projectPath(projectId) + "/repository/commits/" + enc(commitSha);

        ResponseEntity<String> response =
                restTemplate.exchange(url, HttpMethod.GET, getAuthEntity(), String.class);

        return response.getBody();
    }

    public Map<String, Object> getCommitDetailsAsMap(String projectId, String commitSha) {
        validateProject(projectId);

        String url = projectPath(projectId) + "/repository/commits/" + enc(commitSha);

        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        getAuthEntity(),
                        new ParameterizedTypeReference<Map<String, Object>>() {
                        }
                );

        return response.getBody();
    }

    public String getCommitDiff(String projectId, String commitSha) {
        validateProject(projectId);

        String url = projectPath(projectId) + "/repository/commits/" + enc(commitSha) + "/diff";

        ResponseEntity<String> response =
                restTemplate.exchange(url, HttpMethod.GET, getAuthEntity(), String.class);

        return response.getBody();
    }

    public List<Map<String, Object>> getCommitDiffAsList(String projectId, String commitSha) {
        validateProject(projectId);

        String url = projectPath(projectId) + "/repository/commits/" + enc(commitSha) + "/diff";

        ResponseEntity<List<Map<String, Object>>> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        getAuthEntity(),
                        new ParameterizedTypeReference<List<Map<String, Object>>>() {
                        }
                );

        List<Map<String, Object>> body = response.getBody();
        return body == null ? Collections.emptyList() : body;
    }

    public String getFileContentAtCommit(String projectId, String filePath, String commitSha) {
        try {
            validateProject(projectId);

            if (filePath == null || filePath.isBlank() || commitSha == null || commitSha.isBlank()) {
                return null;
            }

            String url = projectPath(projectId)
                    + "/repository/files/" + enc(filePath)
                    + "/raw?ref=" + enc(commitSha);

            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.GET, getAuthEntity(), String.class);

            return response.getBody();
        } catch (RestClientException exception) {
            System.out.println("Error fetching file content: " + exception.getMessage());
            return null;
        }
    }

    public String getFileContentAtRef(String projectId, String filePath, String ref) {
        try {
            validateProject(projectId);

            if (filePath == null || filePath.isBlank()) {
                return null;
            }

            String effectiveRef = ref == null || ref.isBlank() ? "main" : ref;

            String url = projectPath(projectId)
                    + "/repository/files/" + enc(filePath)
                    + "/raw?ref=" + enc(effectiveRef);

            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.GET, getAuthEntity(), String.class);

            return response.getBody();
        } catch (RestClientException exception) {
            System.out.println("Error fetching file at ref: " + exception.getMessage());
            return null;
        }
    }

    public List<Map<String, Object>> getFileCommitHistory(String projectId, String filePath) {
        try {
            validateProject(projectId);

            if (filePath == null || filePath.isBlank()) {
                return Collections.emptyList();
            }

            String url = projectPath(projectId)
                    + "/repository/commits?path=" + enc(filePath)
                    + "&per_page=100&page=1";

            ResponseEntity<List<Map<String, Object>>> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            getAuthEntity(),
                            new ParameterizedTypeReference<List<Map<String, Object>>>() {
                            }
                    );

            List<Map<String, Object>> body = response.getBody();
            return body == null ? Collections.emptyList() : body;
        } catch (Exception exception) {
            System.out.println("Error fetching file commit history: " + exception.getMessage());
            return Collections.emptyList();
        }
    }

    public String findRootCauseCommitForFile(
            String projectId,
            String filePath,
            String currentCommitSha,
            String dependencyName
    ) {
        try {
            validateProject(projectId);

            if (filePath == null || filePath.isBlank()) {
                return null;
            }

            if (currentCommitSha == null || currentCommitSha.isBlank()) {
                return null;
            }

            if (dependencyName == null || dependencyName.isBlank()) {
                return null;
            }

            List<String> possiblePaths = Arrays.asList(
                    filePath,
                    "Backend/" + filePath,
                    "backend/" + filePath,
                    filePath.toLowerCase(),
                    filePath.toUpperCase()
            );

            List<Map<String, Object>> commits = Collections.emptyList();
            String chosenPath = null;

            for (String path : possiblePaths) {
                commits = getFileCommitHistory(projectId, path);

                if (!commits.isEmpty()) {
                    chosenPath = path;
                    break;
                }
            }

            if (commits.isEmpty() || chosenPath == null) {
                return null;
            }

            boolean reachedCurrentCommit = false;
            String[] parts = dependencyName.split(":");
            String groupId = parts.length >= 1 ? parts[0] : "";
            String artifactId = parts.length >= 2 ? parts[1] : "";

            for (Map<String, Object> commit : commits) {
                String sha = toStr(commit.get("id"));

                if (sha.equals(currentCommitSha)) {
                    reachedCurrentCommit = true;
                    continue;
                }

                if (!reachedCurrentCommit) {
                    continue;
                }

                String content = getFileContentAtCommit(projectId, chosenPath, sha);

                if (content == null) {
                    continue;
                }

                int groupCount = countOccurrences(content, groupId);
                int artifactCount = countOccurrences(content, artifactId);

                if (groupCount > 1 && artifactCount > 1) {
                    return sha;
                }
            }

            return null;
        } catch (Exception exception) {
            System.out.println("Error finding root cause commit: " + exception.getMessage());
            return null;
        }
    }

    private int countOccurrences(String text, String keyword) {
        if (text == null || keyword == null || keyword.isBlank()) {
            return 0;
        }

        int count = 0;
        int index = 0;

        while ((index = text.indexOf(keyword, index)) != -1) {
            count++;
            index += keyword.length();
        }

        return count;
    }

    private String toStr(Object value) {
        return value == null ? "" : value.toString();
    }

    private long toLong(Object value) {
        try {
            if (value == null) {
                return 0L;
            }

            return Long.parseLong(value.toString());
        } catch (Exception exception) {
            return 0L;
        }
    }
}
