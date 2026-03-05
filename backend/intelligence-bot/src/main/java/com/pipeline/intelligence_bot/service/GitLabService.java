package com.pipeline.intelligence_bot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class GitLabService {

    @Value("${gitlab.api.url}")
    private String gitlabApiUrl;

    @Value("${gitlab.token}")
    private String privateToken;

    private final RestTemplate restTemplate = new RestTemplate();


    private HttpEntity<String> getAuthEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("PRIVATE-TOKEN", privateToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    private void validateProjectPipeline(String projectId, String pipelineId) {
        if (projectId == null || projectId.isBlank()) {
            throw new RuntimeException("projectId required");
        }
        if (pipelineId == null || pipelineId.isBlank()) {
            throw new RuntimeException("pipelineId required");
        }
    }

    private void validateProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new RuntimeException("projectId required");
        }
    }

    private void validateJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new RuntimeException("jobId is required");
        }
    }

    private String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }


    public Map<String, Object> getPipelineDetails(String projectId, String pipelineId) {
        validateProjectPipeline(projectId, pipelineId);

        String url = gitlabApiUrl +
                "/projects/" + projectId +
                "/pipelines/" + pipelineId;

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


    public String getPipelineJobsRaw(String projectId, String pipelineId) {
        validateProjectPipeline(projectId, pipelineId);

        String url = gitlabApiUrl +
                "/projects/" + projectId +
                "/pipelines/" + pipelineId +
                "/jobs?per_page=100&page=1";

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
            String url = gitlabApiUrl +
                    "/projects/" + projectId +
                    "/pipelines/" + pipelineId +
                    "/jobs?per_page=" + perPage +
                    "&page=" + page;

            ResponseEntity<List<Map<String, Object>>> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            getAuthEntity(),
                            new ParameterizedTypeReference<List<Map<String, Object>>>() {
                            }
                    );

            List<Map<String, Object>> batch = response.getBody();
            if (batch == null || batch.isEmpty()) break;

            all.addAll(batch);

            if (batch.size() < perPage) break;

            page++;
        }

        return all;
    }


    public Map<String, Object> getJobDetails(String projectId, String jobId) {
        validateProject(projectId);
        validateJob(jobId);

        String url = gitlabApiUrl +
                "/projects/" + projectId +
                "/jobs/" + jobId;

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

        String url = gitlabApiUrl +
                "/projects/" + projectId +
                "/jobs/" + jobId +
                "/trace";

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
        if (jobs == null || jobs.isEmpty()) return null;

        Map<String, Object> best = null;

        for (Map<String, Object> job : jobs) {
            String status = toStr(job.get("status")).toLowerCase();
            if (!"failed".equals(status)) continue;

            if (best == null) {
                best = job;
                continue;
            }

            String a = toStr(job.get("started_at"));
            String b = toStr(best.get("started_at"));

            if (!a.isBlank() && !b.isBlank()) {
                if (a.compareTo(b) < 0) best = job;
            } else {
                long idA = toLong(job.get("id"));
                long idB = toLong(best.get("id"));
                if (idA < idB) best = job;
            }
        }

        return best;
    }


    public List<Map<String, Object>> findAllFailedJobs(List<Map<String, Object>> jobs) {
        List<Map<String, Object>> failed = new ArrayList<>();
        if (jobs == null) return failed;

        for (Map<String, Object> job : jobs) {
            String status = toStr(job.get("status"));
            if ("failed".equalsIgnoreCase(status)) {
                failed.add(job);
            }
        }
        return failed;
    }


    public Map<String, Object> buildPipelineSummary(String projectId, String pipelineId) {
        List<Map<String, Object>> jobs = getPipelineJobsAsList(projectId, pipelineId);

        int total = jobs.size();
        int failed = 0;
        int success = 0;
        int running = 0;
        int pending = 0;
        int canceled = 0;
        int skipped = 0;

        List<String> failedJobIds = new ArrayList<>();

        for (Map<String, Object> job : jobs) {
            String status = toStr(job.get("status")).toLowerCase();

            switch (status) {
                case "failed":
                    failed++;
                    failedJobIds.add(toStr(job.get("id")));
                    break;
                case "success":
                    success++;
                    break;
                case "running":
                    running++;
                    break;
                case "pending":
                    pending++;
                    break;
                case "canceled":
                    canceled++;
                    break;
                case "skipped":
                    skipped++;
                    break;
                default:
                    // ignore others: manual, created, etc.
                    break;
            }
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("projectId", projectId);
        summary.put("pipelineId", pipelineId);

        summary.put("totalJobs", total);
        summary.put("passedJobs", success);
        summary.put("failedJobs", failed);

        summary.put("runningJobs", running);
        summary.put("pendingJobs", pending);
        summary.put("canceledJobs", canceled);
        summary.put("skippedJobs", skipped);

        double failureRate = total == 0 ? 0.0 : ((double) failed / total) * 100.0;
        summary.put("failureRatePercent", failureRate);

        summary.put("failedJobIds", failedJobIds);

        return summary;
    }


    public String getCommitDetails(String projectId, String commitSha) {
        validateProject(projectId);

        String url = gitlabApiUrl +
                "/projects/" + projectId +
                "/repository/commits/" + commitSha;

        ResponseEntity<String> response =
                restTemplate.exchange(url, HttpMethod.GET, getAuthEntity(), String.class);

        return response.getBody();
    }


    public String getCommitDiff(String projectId, String commitSha) {
        validateProject(projectId);

        String url = gitlabApiUrl +
                "/projects/" + projectId +
                "/repository/commits/" + commitSha +
                "/diff";

        ResponseEntity<String> response =
                restTemplate.exchange(url, HttpMethod.GET, getAuthEntity(), String.class);

        return response.getBody();
    }


    public List<Map<String, Object>> getCommitDiffAsList(String projectId, String commitSha) {
        validateProject(projectId);

        String url = gitlabApiUrl +
                "/projects/" + projectId +
                "/repository/commits/" + commitSha +
                "/diff";

        ResponseEntity<List<Map<String, Object>>> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        getAuthEntity(),
                        new ParameterizedTypeReference<List<Map<String, Object>>>() {
                        }
                );

        return response.getBody();
    }


    public String getFileContentAtCommit(String projectId, String filePath, String commitSha) {
        try {
            validateProject(projectId);
            if (filePath == null || filePath.isBlank()) return null;
            if (commitSha == null || commitSha.isBlank()) return null;

            String encodedPath = enc(filePath);

            String url = gitlabApiUrl +
                    "/projects/" + projectId +
                    "/repository/files/" + encodedPath +
                    "/raw?ref=" + enc(commitSha);

            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.GET, getAuthEntity(), String.class);

            return response.getBody();
        } catch (RestClientException e) {
            System.out.println("Error fetching file content: " + e.getMessage());
            return null;
        }
    }


    public String getFileContentAtRef(String projectId, String filePath, String ref) {
        try {
            validateProject(projectId);
            if (filePath == null || filePath.isBlank()) return null;
            if (ref == null || ref.isBlank()) ref = "main";

            String encodedPath = enc(filePath);

            String url = gitlabApiUrl +
                    "/projects/" + projectId +
                    "/repository/files/" + encodedPath +
                    "/raw?ref=" + enc(ref);

            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.GET, getAuthEntity(), String.class);

            return response.getBody();
        } catch (RestClientException e) {
            System.out.println("Error fetching file at ref: " + e.getMessage());
            return null;
        }
    }


    public List<Map<String, Object>> getFileCommitHistory(String projectId, String filePath) {
        try {
            validateProject(projectId);
            if (filePath == null || filePath.isBlank()) return Collections.emptyList();

            String url = gitlabApiUrl +
                    "/projects/" + projectId +
                    "/repository/commits?path=" + enc(filePath) +
                    "&per_page=100&page=1";

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

        } catch (Exception e) {
            System.out.println("Error fetching file commit history: " + e.getMessage());
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

            if (filePath == null || filePath.isBlank()) return null;
            if (currentCommitSha == null || currentCommitSha.isBlank()) return null;

            // normalize common case
            if ("pom.xml".equals(filePath)) {
                filePath = "Backend/pom.xml";
            }

            if (dependencyName == null || dependencyName.isBlank()) {
                System.out.println("Dependency name is null, cannot detect root cause");
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
                if (commits != null && !commits.isEmpty()) {
                    chosenPath = path;
                    break;
                }
            }

            if (commits == null || commits.isEmpty() || chosenPath == null) {
                System.out.println("No commits found for any path variation");
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

                if (!reachedCurrentCommit) continue;

                String content = getFileContentAtCommit(projectId, chosenPath, sha);
                if (content == null) continue;

                int groupCount = countOccurrences(content, groupId);
                int artifactCount = countOccurrences(content, artifactId);

                if (groupCount > 1 && artifactCount > 1) {
                    return sha;
                }
            }

            return null;

        } catch (Exception e) {
            System.out.println("Error finding root cause commit: " + e.getMessage());
            return null;
        }
    }

    private int countOccurrences(String text, String keyword) {
        if (text == null || keyword == null || keyword.isBlank()) return 0;

        int count = 0;
        int index = 0;

        while ((index = text.indexOf(keyword, index)) != -1) {
            count++;
            index += keyword.length();
        }
        return count;
    }


    private String toStr(Object o) {
        return o == null ? "" : o.toString();
    }

    private long toLong(Object o) {
        try {
            if (o == null) return 0L;
            return Long.parseLong(o.toString());
        } catch (Exception e) {
            return 0L;
        }
    }
}