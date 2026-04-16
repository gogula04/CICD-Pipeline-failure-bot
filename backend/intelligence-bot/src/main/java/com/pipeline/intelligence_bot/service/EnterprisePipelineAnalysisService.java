package com.pipeline.intelligence_bot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EnterprisePipelineAnalysisService {

    private static final Pattern DEPENDENCY_NAME_PATTERN =
            Pattern.compile("must be unique:\\s*([a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+)");

    private static final List<String> SUPPORTED_FAILURE_CATEGORIES = List.of(
            "Code Compilation Failure",
            "Build Configuration Failure",
            "Test Failure",
            "Environment Failure",
            "Infrastructure Failure",
            "External System Failure",
            "Pipeline Configuration Failure"
    );

    private static final List<String> SUPPORTED_CONFIGURATION_FILES = List.of(
            ".gitlab-ci.yml",
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "CMakeLists.txt",
            "Makefile",
            "package.json",
            "pyproject.toml",
            "requirements.txt",
            "Dockerfile"
    );

    private final GitLabService gitLabService;
    private final PythonAnalysisService pythonAnalysisService;
    private final DiffAnalysisService diffAnalysisService;
    private final PipelineIntelligenceService pipelineIntelligenceService;
    private final LogPreprocessingService logPreprocessingService;
    private final AiReasoningService aiReasoningService;

    @Value("${analysis.read-only-mode:true}")
    private boolean readOnlyMode;

    @Value("${analysis.version:enterprise-v2}")
    private String analysisVersion;

    public EnterprisePipelineAnalysisService(
            GitLabService gitLabService,
            PythonAnalysisService pythonAnalysisService,
            DiffAnalysisService diffAnalysisService,
            PipelineIntelligenceService pipelineIntelligenceService,
            LogPreprocessingService logPreprocessingService,
            AiReasoningService aiReasoningService
    ) {
        this.gitLabService = gitLabService;
        this.pythonAnalysisService = pythonAnalysisService;
        this.diffAnalysisService = diffAnalysisService;
        this.pipelineIntelligenceService = pipelineIntelligenceService;
        this.logPreprocessingService = logPreprocessingService;
        this.aiReasoningService = aiReasoningService;
    }

    public Map<String, Object> analyzePipeline(String projectId, String pipelineId) {
        return analyzePipeline(projectId, pipelineId, null);
    }

    public Map<String, Object> analyzePipeline(String projectId, String pipelineId, String failedJobId) {
        String normalizedProjectId = normalize(projectId);
        String normalizedPipelineId = normalize(pipelineId);
        String normalizedFailedJobId = normalize(failedJobId);

        if (normalizedProjectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }

        if (normalizedPipelineId == null) {
            throw new IllegalArgumentException("pipelineId is required");
        }

        Map<String, Object> pipeline = gitLabService.getPipelineDetails(normalizedProjectId, normalizedPipelineId);
        List<Map<String, Object>> jobs = gitLabService.getPipelineJobsAsList(normalizedProjectId, normalizedPipelineId);
        Map<String, Object> project = gitLabService.getProjectDetails(normalizedProjectId);

        String pipelineCommit = asString(pipeline.get("sha"));
        List<Map<String, Object>> diffs = pipelineCommit == null
                ? Collections.emptyList()
                : gitLabService.getCommitDiffAsList(normalizedProjectId, pipelineCommit);

        Map<String, Object> pipelineSummary =
                gitLabService.buildPipelineSummary(normalizedProjectId, normalizedPipelineId, jobs, pipeline);

        List<Map<String, Object>> failedJobs = gitLabService.findAllFailedJobs(jobs);
        Map<String, Object> selectedFailureSource = selectFailureSourceJob(
                normalizedProjectId,
                jobs,
                failedJobs,
                normalizedFailedJobId
        );
        List<Map<String, Object>> analysisFailedJobs = selectedFailureSource.isEmpty()
                ? failedJobs
                : List.of(selectedFailureSource);
        if (normalizedFailedJobId != null && !selectedFailureSource.isEmpty() && failedJobs.isEmpty()) {
            pipelineSummary = normalizePipelineSummaryForSelectedFailure(pipelineSummary, analysisFailedJobs);
        }
        List<Map<String, Object>> jobsToAnalyze = selectedFailureSource.isEmpty()
                ? Collections.emptyList()
                : List.of(selectedFailureSource);
        List<Map<String, Object>> jobAnalyses =
                buildJobAnalyses(
                        normalizedProjectId,
                        normalizedPipelineId,
                        pipelineCommit,
                        pipeline,
                        pipelineSummary,
                        jobsToAnalyze,
                        diffs
                );

        Map<String, Object> pipelineIntelligence =
                pipelineIntelligenceService.buildCascadingFailureIntelligence(jobs);
        pipelineIntelligence = normalizePipelineIntelligenceForSelectedJob(
                pipelineIntelligence,
                selectedFailureSource,
                normalizedFailedJobId != null
        );

        Map<String, Object> primaryFailure = asMap(pipelineIntelligence.get("primaryFailure"));
        Map<String, Object> primaryFailureAnalysis = findPrimaryFailureAnalysis(jobAnalyses, primaryFailure);
        Map<String, Object> commitAnalysis =
                buildCommitAnalysis(normalizedProjectId, pipelineCommit, diffs, primaryFailureAnalysis, jobAnalyses);

        Map<String, Object> recentFailurePatterns =
                buildRecentFailurePatterns(
                        normalizedProjectId,
                        normalizedPipelineId,
                        pipeline,
                        analysisFailedJobs,
                        primaryFailureAnalysis
                );
        if (analysisFailedJobs.isEmpty()) {
            primaryFailureAnalysis = buildSuccessfulPipelineAnalysis(
                    pipeline,
                    pipelineSummary,
                    recentFailurePatterns,
                    commitAnalysis
            );
            normalizeCommitAnalysisForSuccess(commitAnalysis);
        }
        enrichPrimaryFailureAnalysis(
                normalizedProjectId,
                asString(pipeline.get("ref")),
                pipelineCommit,
                primaryFailureAnalysis,
                commitAnalysis,
                recentFailurePatterns,
                pipelineIntelligence
        );
        List<Map<String, Object>> failureTimeline =
                buildFailureTimeline(primaryFailureAnalysis, pipelineIntelligence, commitAnalysis);
        Map<String, Object> proofEngine = buildProofEngine(
                normalizedProjectId,
                normalizedPipelineId,
                pipelineIntelligence,
                primaryFailureAnalysis,
                commitAnalysis,
                recentFailurePatterns
        );
        Map<String, Object> beforeAfterCommitAnalysis = buildBeforeAfterCommitAnalysis(
                primaryFailureAnalysis,
                proofEngine,
                commitAnalysis
        );
        Map<String, Object> dependencyImpact = buildDependencyImpact(
                primaryFailureAnalysis,
                pipelineIntelligence,
                commitAnalysis
        );
        Map<String, Object> testImpactAnalysis = buildTestImpactAnalysis(
                primaryFailureAnalysis,
                commitAnalysis,
                pipelineIntelligence
        );
        Map<String, Object> rootCauseGraph = buildRootCauseGraph(
                primaryFailureAnalysis,
                commitAnalysis,
                dependencyImpact,
                testImpactAnalysis,
                pipelineIntelligence
        );
        Map<String, Object> failurePatternIntelligence =
                buildFailurePatternIntelligence(primaryFailureAnalysis, recentFailurePatterns);
        Map<String, Object> fixConfidence = buildFixConfidence(
                primaryFailureAnalysis,
                commitAnalysis,
                recentFailurePatterns,
                proofEngine
        );
        Map<String, Object> teamLearningMode = buildTeamLearningMode(
                primaryFailureAnalysis,
                commitAnalysis,
                failurePatternIntelligence
        );
        Map<String, Object> executiveSummary = buildExecutiveSummary(
                primaryFailureAnalysis,
                proofEngine,
                testImpactAnalysis
        );

        Map<String, Object> pipelineOverview =
                buildPipelineOverview(project, pipeline, pipelineSummary, pipelineIntelligence, jobs, analysisFailedJobs);
        Map<String, Object> riskPropagation = buildRiskPropagation(
                primaryFailureAnalysis,
                pipelineOverview,
                pipelineIntelligence,
                recentFailurePatterns
        );
        Map<String, Object> pipelineHealthScore = buildPipelineHealthScore(
                primaryFailureAnalysis,
                pipelineSummary,
                pipelineOverview,
                pipelineIntelligence,
                recentFailurePatterns,
                commitAnalysis
        );
        List<Map<String, Object>> prioritizedFixes = buildPrioritizedFixes(
                primaryFailureAnalysis,
                teamLearningMode,
                dependencyImpact
        );
        Map<String, Object> notificationPreview = buildNotificationPreview(
                executiveSummary,
                riskPropagation,
                fixConfidence,
                primaryFailureAnalysis,
                prioritizedFixes
        );

        Map<String, Object> operationalInsights =
                buildOperationalInsights(primaryFailureAnalysis, pipelineSummary, pipelineIntelligence, commitAnalysis, analysisFailedJobs);

        Map<String, Object> governance = buildGovernance();
        Map<String, Object> enterpriseReadiness = buildEnterpriseReadiness();
        Map<String, Object> analysisCoverage =
                buildAnalysisCoverage(jobAnalyses, commitAnalysis, recentFailurePatterns, analysisFailedJobs, pipelineCommit);
        Map<String, Object> decisionEngine = buildDecisionEngine(primaryFailureAnalysis, commitAnalysis);
        Map<String, Object> verificationIntelligence = buildVerificationIntelligence(primaryFailureAnalysis);
        Map<String, Object> traceabilityIntelligence = buildTraceabilityIntelligence(primaryFailureAnalysis);
        Map<String, Object> environmentComparison =
                buildEnvironmentComparison(primaryFailureAnalysis, decisionEngine, commitAnalysis);
        Map<String, Object> advancedSignals =
                buildAdvancedSignals(primaryFailureAnalysis, recentFailurePatterns, pipelineSummary, pipelineOverview);
        List<Map<String, Object>> domainCoverage =
                buildDomainCoverage(verificationIntelligence, traceabilityIntelligence, environmentComparison, advancedSignals);
        Map<String, Object> smartAnswers =
                buildSmartAnswers(
                        primaryFailureAnalysis,
                        commitAnalysis,
                        recentFailurePatterns,
                        decisionEngine,
                        traceabilityIntelligence,
                        verificationIntelligence
                );

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportMetadata", buildReportMetadata(normalizedProjectId, normalizedPipelineId, analysisFailedJobs));
        report.put("projectId", normalizedProjectId);
        report.put("pipelineId", normalizedPipelineId);
        report.put("pipelineCommit", pipelineCommit);
        report.put("analysisMode", determineAnalysisMode(selectedFailureSource, normalizedFailedJobId));
        report.put("analysisSourceJobId", asString(selectedFailureSource.get("id")));
        report.put("analysisSourceJobName", asString(selectedFailureSource.get("name")));
        report.put("pipelineSummary", pipelineSummary);
        report.put("pipelineOverview", pipelineOverview);
        report.put("jobAnalyses", jobAnalyses);
        report.put("failedJobs", analysisFailedJobs);
        report.put("pipelineIntelligence", pipelineIntelligence);
        report.put("primaryFailureAnalysis", primaryFailureAnalysis);
        report.put("failureTimeline", failureTimeline);
        report.put("proofEngine", proofEngine);
        report.put("beforeAfterCommitAnalysis", beforeAfterCommitAnalysis);
        report.put("dependencyImpact", dependencyImpact);
        report.put("rootCauseGraph", rootCauseGraph);
        report.put("riskPropagation", riskPropagation);
        report.put("testImpactAnalysis", testImpactAnalysis);
        report.put("failurePatternIntelligence", failurePatternIntelligence);
        report.put("fixConfidence", fixConfidence);
        report.put("teamLearningMode", teamLearningMode);
        report.put("executiveSummary", executiveSummary);
        report.put("pipelineHealthScore", pipelineHealthScore);
        report.put("prioritizedFixes", prioritizedFixes);
        report.put("notificationPreview", notificationPreview);
        report.put("commitAnalysis", commitAnalysis);
        report.put("recentFailurePatterns", recentFailurePatterns);
        report.put("operationalInsights", operationalInsights);
        report.put("governance", governance);
        report.put("enterpriseReadiness", enterpriseReadiness);
        report.put("analysisCoverage", analysisCoverage);
        report.put("decisionEngine", decisionEngine);
        report.put("verificationIntelligence", verificationIntelligence);
        report.put("traceabilityIntelligence", traceabilityIntelligence);
        report.put("environmentComparison", environmentComparison);
        report.put("advancedSignals", advancedSignals);
        report.put("domainCoverage", domainCoverage);
        report.put("smartAnswers", smartAnswers);

        return report;
    }

    public Map<String, Object> getCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("readOnlyMode", readOnlyMode);
        capabilities.put("analysisVersion", analysisVersion);
        capabilities.put("supportedFailureCategories", SUPPORTED_FAILURE_CATEGORIES);
        capabilities.put("supportedConfigurationFiles", SUPPORTED_CONFIGURATION_FILES);
        capabilities.put("analysisModes", List.of(
                "python signatures only",
                "dual-engine python signatures + Groq AI reasoning",
                "multi-job root cause detection",
                "cascading failure intelligence",
                "commit and configuration correlation",
                "causal commit attribution",
                "proof-based commit blame validation",
                "before-vs-after pipeline comparison",
                "dependency impact reasoning",
                "risk propagation prediction",
                "auto-fix patch generation",
                "pipeline health scoring",
                "ranked fix prioritization",
                "failure timeline generation",
                "root-vs-noise error ranking",
                "recent pipeline trend signals",
                "enterprise governance metadata",
                "signature-catalog classification",
                "unknown failure fingerprinting",
                "confidence-gated recommendations",
                "code-vs-infra decision engine",
                "verification mismatch surfacing",
                "requirements and traceability status reporting"
        ));
        capabilities.put("analysisLayers", aiReasoningService.isEnabled()
                ? List.of("Layer 1: Python signatures", "Layer 2: Groq AI reasoning")
                : List.of("Layer 1: Python signatures"));
        capabilities.put("aiProviders", List.of("Groq AI"));
        capabilities.put("aiEngine", aiReasoningService.describeEngine());
        capabilities.put("recommendationPolicy",
                "The system gives high-confidence fixes for recognized signatures and guided triage for novel or low-confidence failures.");
        capabilities.put("problemDomains", List.of(
                "code-level problems",
                "build-system problems",
                "environment and dependency problems",
                "testing and verification problems",
                "requirements and traceability problems",
                "configuration problems",
                "infrastructure problems",
                "external dependency problems",
                "advanced intelligence problems"
        ));
        capabilities.put("targetProfile", "large engineering organizations using GitLab CI/CD");
        capabilities.put("apiContracts", List.of(
                "GET /api/gitlab/analyzePipelineFully",
                "POST /api/gitlab/analyze",
                "GET /api/gitlab/capabilities",
                "GET /api/gitlab/health"
        ));
        return capabilities;
    }

    private Map<String, Object> buildReportMetadata(
            String projectId,
            String pipelineId,
            List<Map<String, Object>> failedJobs
    ) {

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reportId", UUID.randomUUID().toString());
        metadata.put("generatedAt", Instant.now().toString());
        metadata.put("analysisVersion", analysisVersion);
        metadata.put("readOnlyMode", readOnlyMode);
        metadata.put("enterpriseTier", "enterprise-ready");
        metadata.put("analysisEngine", aiReasoningService.describeEngine());
        metadata.put("analysisLayers", aiReasoningService.isEnabled()
                ? List.of("Python signatures", "Groq AI reasoning")
                : List.of("Python signatures"));
        metadata.put("aiProvider", aiReasoningService.getProviderLabel());
        metadata.put("aiModel", aiReasoningService.getModelName());
        metadata.put("aiEnabled", aiReasoningService.isEnabled());
        metadata.put("pipelineTarget", projectId + "#" + pipelineId);
        metadata.put("hasFailures", !failedJobs.isEmpty());

        return metadata;
    }

    private Map<String, Object> selectFailureSourceJob(
            String projectId,
            List<Map<String, Object>> jobs,
            List<Map<String, Object>> failedJobs,
            String failedJobId
    ) {

        if (failedJobId != null) {
            Map<String, Object> jobDetails = gitLabService.getJobDetails(projectId, failedJobId);

            if (jobDetails != null && !jobDetails.isEmpty()) {
                return jobDetails;
            }

            for (Object candidate : safeList(jobs)) {
                Map<String, Object> job = safeMap(candidate);

                if (failedJobId.equals(asString(job.get("id")))) {
                    return job;
                }
            }

            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("id", failedJobId);
            fallback.put("name", "Failed job #" + failedJobId);
            fallback.put("stage", "");
            fallback.put("status", "failed");
            fallback.put("allow_failure", false);
            fallback.put("when", "");
            fallback.put("started_at", "");
            fallback.put("finished_at", "");
            return fallback;
        }

        Map<String, Object> firstFailedJob = gitLabService.findFirstFailedJob(jobs);
        return firstFailedJob == null ? Collections.emptyMap() : firstFailedJob;
    }

    private Map<String, Object> normalizePipelineSummaryForSelectedFailure(
            Map<String, Object> pipelineSummary,
            List<Map<String, Object>> analysisFailedJobs
    ) {

        Map<String, Object> normalized = new LinkedHashMap<>(pipelineSummary);
        int totalJobs = toInt(normalized.get("totalJobs"));
        normalized.put("failedJobs", analysisFailedJobs.size());
        normalized.put(
                "failureRatePercent",
                totalJobs == 0 ? 100.0 : ((double) analysisFailedJobs.size() / totalJobs) * 100.0
        );
        normalized.put("failedJobIds", analysisFailedJobs.stream()
                .map(job -> asString(job.get("id")))
                .filter(value -> value != null && !value.isBlank())
                .toList());
        normalized.put("failedJobNames", analysisFailedJobs.stream()
                .map(job -> firstNonBlank(asString(job.get("name")), "Failed job #" + asString(job.get("id"))))
                .filter(value -> value != null && !value.isBlank())
                .toList());
        return normalized;
    }

    private Map<String, Object> normalizePipelineIntelligenceForSelectedJob(
            Map<String, Object> pipelineIntelligence,
            Map<String, Object> selectedFailureSource,
            boolean explicitSelection
    ) {

        Map<String, Object> normalized = new LinkedHashMap<>(pipelineIntelligence);

        if (selectedFailureSource == null || selectedFailureSource.isEmpty()) {
            normalized.put("selectedFailureSource", Collections.emptyMap());
            normalized.put("selectedFailureSourceMode", explicitSelection ? "explicit-job" : "pipeline-success");
            return normalized;
        }

        Map<String, Object> summary = summarizeJob(selectedFailureSource);
        normalized.put("primaryFailure", summary);
        normalized.put("selectedFailureSource", summary);
        normalized.put("selectedFailureSourceMode", explicitSelection ? "explicit-job" : "auto-first-failed-job");
        return normalized;
    }

    private Map<String, Object> summarizeJob(Map<String, Object> job) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("jobId", asString(job.get("id")));
        summary.put("jobName", asString(job.get("name")));
        summary.put("stage", asString(job.get("stage")));
        summary.put("status", asString(job.get("status")));
        summary.put("allowFailure", Boolean.TRUE.equals(job.get("allow_failure")));
        summary.put("when", asString(job.get("when")));
        summary.put("startedAt", asString(job.get("started_at")));
        summary.put("finishedAt", asString(job.get("finished_at")));
        return summary;
    }

    private String determineAnalysisMode(Map<String, Object> selectedFailureSource, String failedJobId) {
        if (failedJobId != null) {
            return aiReasoningService.isEnabled()
                    ? "explicit-failed-job with Python signatures + Groq AI"
                    : "explicit-failed-job with python signatures";
        }

        if (selectedFailureSource != null && !selectedFailureSource.isEmpty()) {
            return aiReasoningService.isEnabled()
                    ? "auto-first-failed-job with Python signatures + Groq AI"
                    : "auto-first-failed-job with python signatures";
        }

        return "successful-pipeline-baseline";
    }

    private List<Map<String, Object>> buildJobAnalyses(
            String projectId,
            String pipelineId,
            String pipelineCommit,
            Map<String, Object> pipeline,
            Map<String, Object> pipelineSummary,
            List<Map<String, Object>> failedJobs,
            List<Map<String, Object>> diffs
    ) {

        List<Map<String, Object>> analyses = new ArrayList<>();

        for (Map<String, Object> job : failedJobs) {
            String jobId = asString(job.get("id"));
            String logs = gitLabService.getJobLogs(projectId, jobId);
            Map<String, Object> rawAnalysis = new LinkedHashMap<>(pythonAnalysisService.analyzeLogs(logs));
            Map<String, Object> preprocessedLogs = logPreprocessingService.preprocess(logs);
            Map<String, Object> aiInsights = aiReasoningService.analyzeFailure(buildAiContext(
                    projectId,
                    pipelineId,
                    pipelineCommit,
                    pipeline,
                    pipelineSummary,
                    job,
                    rawAnalysis,
                    preprocessedLogs,
                    diffs
            ));

            rawAnalysis.put("preprocessedLogs", preprocessedLogs);
            rawAnalysis.put("analysisLayers", aiReasoningService.isEnabled()
                    ? List.of("Layer 1: Python signatures", "Layer 2: Groq AI reasoning")
                    : List.of("Layer 1: Python signatures"));

            if (!aiInsights.isEmpty()) {
                rawAnalysis.put("aiInsights", aiInsights);
                mergeAiInsightsIntoAnalysis(rawAnalysis, aiInsights);
            }

            rawAnalysis.put("analysisSource", buildHybridAnalysisSource(rawAnalysis, aiInsights));
            Map<String, Object> normalizedAnalysis = normalizeFailureAnalysis(job, rawAnalysis, logs);

            String failureFile = asString(normalizedAnalysis.get("file"));
            String failureLine = asString(normalizedAnalysis.get("line"));
            String dependencyName = extractDependencyName(normalizedAnalysis, logs);

            Map<String, Object> diffCorrelation = diffs.isEmpty()
                    ? buildDiffCorrelationPlaceholder()
                    : diffAnalysisService.analyzeDiffForFailure(diffs, failureFile, failureLine, dependencyName);

            Map<String, Object> historicalCorrelation =
                    buildHistoricalCorrelation(projectId, pipelineCommit, failureFile, dependencyName, diffCorrelation);

            normalizedAnalysis.put("diffCorrelation", diffCorrelation);
            normalizedAnalysis.put("historicalCorrelation", historicalCorrelation);
            normalizedAnalysis.put("severity", inferSeverity(normalizedAnalysis, job));
            normalizedAnalysis.put(
                    "recommendedOwner",
                    firstNonBlank(asString(rawAnalysis.get("recommendedOwner")), inferRecommendedOwner(normalizedAnalysis))
            );
            normalizedAnalysis.put(
                    "supportsAutomatedRetry",
                    Boolean.TRUE.equals(rawAnalysis.get("supportsAutomatedRetry")) || supportsAutomatedRetry(normalizedAnalysis)
            );
            normalizedAnalysis.put("resolutionPlaybook", buildResolutionPlaybook(normalizedAnalysis, job));
            normalizedAnalysis.put("logHighlights", extractLogHighlights(logs));
            normalizedAnalysis.put("analysisSource", rawAnalysis.containsKey("analysisSource")
                    ? rawAnalysis.get("analysisSource")
                    : "java-fallback-enrichment");

            Map<String, Object> jobResult = new LinkedHashMap<>();
            jobResult.put("jobId", jobId);
            jobResult.put("jobName", asString(job.get("name")));
            jobResult.put("stage", asString(job.get("stage")));
            jobResult.put("status", asString(job.get("status")));
            jobResult.put("allowFailure", Boolean.TRUE.equals(job.get("allow_failure")));
            jobResult.put("startedAt", job.get("started_at"));
            jobResult.put("finishedAt", job.get("finished_at"));
            jobResult.put("failureAnalysis", normalizedAnalysis);

            analyses.add(jobResult);
        }

        analyses.sort(Comparator.comparing(item -> asString(item.get("jobId"))));

        return analyses;
    }

    private Map<String, Object> buildAiContext(
            String projectId,
            String pipelineId,
            String pipelineCommit,
            Map<String, Object> pipeline,
            Map<String, Object> pipelineSummary,
            Map<String, Object> job,
            Map<String, Object> pythonAnalysis,
            Map<String, Object> preprocessedLogs,
            List<Map<String, Object>> diffs
    ) {

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("projectId", projectId);
        context.put("pipelineId", pipelineId);
        context.put("pipelineCommit", pipelineCommit);
        context.put("pipeline", pipeline);
        context.put("pipelineSummary", pipelineSummary);
        context.put("selectedJob", summarizeJob(job));
        context.put("pythonAnalysis", pythonAnalysis);
        context.put("preprocessedLogs", preprocessedLogs);
        context.put("diffSummaries", summarizeDiffs(diffs));
        context.put("analysisGoal", "Refine the deterministic Python signature result with contextual reasoning.");
        return context;
    }

    private List<Map<String, Object>> summarizeDiffs(List<Map<String, Object>> diffs) {
        List<Map<String, Object>> summaries = new ArrayList<>();

        for (Object diffObject : safeList(diffs)) {
            if (summaries.size() >= 8) {
                break;
            }

            Map<String, Object> diff = safeMap(diffObject);
            Map<String, Object> summary = new LinkedHashMap<>();
            String path = firstNonBlank(asString(diff.get("new_path")), asString(diff.get("old_path")));
            String diffText = asString(diff.get("diff"));

            summary.put("path", path);
            summary.put("category", categorizeFile(path));
            summary.put("changeType", inferChangeType(diffText));
            summary.put("snippet", truncateMiddle(diffText, 900));
            summaries.add(summary);
        }

        return summaries;
    }

    private void mergeAiInsightsIntoAnalysis(Map<String, Object> analysis, Map<String, Object> aiInsights) {
        if (analysis == null || aiInsights == null || aiInsights.isEmpty()) {
            return;
        }

        analysis.put("aiProvider", asString(aiInsights.get("aiProvider")));
        analysis.put("aiModel", asString(aiInsights.get("aiModel")));
        analysis.put("aiInsights", aiInsights);
        analysis.put("summary", firstNonBlank(asString(aiInsights.get("summary")), asString(analysis.get("summary"))));
        analysis.put("reasoning", mergeStringLists(analysis.get("reasoning"), aiInsights.get("reasoning")));
        analysis.put("signals", mergeSignals(mergeStringList(analysis.get("signals")), aiInsights.get("signals")));
        analysis.put("details", mergeStringLists(analysis.get("details"), aiInsights.get("details")));
        analysis.put("fixOptions", mergeStringLists(analysis.get("fixOptions"), aiInsights.get("fixOptions")));
        analysis.put("recommendedOwner", firstNonBlank(asString(aiInsights.get("recommendedOwner")), asString(analysis.get("recommendedOwner"))));
        analysis.put("nextBestAction", firstNonBlank(asString(aiInsights.get("nextBestAction")), asString(analysis.get("nextBestAction"))));
        analysis.put("knowledgeGap", firstNonBlank(asString(aiInsights.get("knowledgeGap")), asString(analysis.get("knowledgeGap"))));
        analysis.put(
                "recommendationPolicy",
                firstNonBlank(asString(aiInsights.get("recommendationPolicy")), asString(analysis.get("recommendationPolicy")))
        );
        analysis.put("requiresHumanReview",
                aiInsights.containsKey("requiresHumanReview")
                        ? Boolean.TRUE.equals(aiInsights.get("requiresHumanReview"))
                        : analysis.get("requiresHumanReview")
        );
        analysis.put("triageUrgency", mergeTriageUrgency(
                safeMap(analysis.get("triageUrgency")),
                safeMap(aiInsights.get("triageUrgency"))
        ));

        if (!isHighConfidenceSignature(analysis)) {
            copyIfMeaningful(analysis, aiInsights, "category");
            copyIfMeaningful(analysis, aiInsights, "failureType");
            copyIfMeaningful(analysis, aiInsights, "tool");
            copyIfMeaningful(analysis, aiInsights, "confidence");
            copyIfMeaningful(analysis, aiInsights, "file");
            copyIfMeaningful(analysis, aiInsights, "line");
            copyIfMeaningful(analysis, aiInsights, "column");
            copyIfMeaningful(analysis, aiInsights, "classOrFunction");
            copyIfMeaningful(analysis, aiInsights, "symbolName");
            copyIfMeaningful(analysis, aiInsights, "symbolKind");
            analysis.put("missingSymbols", mergeStringLists(analysis.get("missingSymbols"), aiInsights.get("missingSymbols")));
            copyIfMeaningful(analysis, aiInsights, "errorMessage");
            copyIfMeaningful(analysis, aiInsights, "rootCause");
            copyIfMeaningful(analysis, aiInsights, "whatIsWrong");
            copyIfMeaningful(analysis, aiInsights, "fixRecommendation");
            analysis.put("secondaryIssues", mergeStringLists(analysis.get("secondaryIssues"), aiInsights.get("secondaryIssues")));
            copyIfMeaningful(analysis, aiInsights, "meaning");
            copyIfMeaningful(analysis, aiInsights, "likelyCause");
        }
    }

    private boolean isHighConfidenceSignature(Map<String, Object> analysis) {
        if (analysis == null) {
            return false;
        }

        String signatureId = asString(analysis.get("signatureId"));
        String confidence = asString(analysis.get("confidence"));
        return signatureId != null && !signatureId.isBlank() && "HIGH".equalsIgnoreCase(confidence);
    }

    private void copyIfMeaningful(Map<String, Object> target, Map<String, Object> source, String key) {
        if (target == null || source == null || key == null) {
            return;
        }

        Object value = source.get(key);

        if (hasMeaningfulValue(value)) {
            target.put(key, value);
        }
    }

    private boolean hasMeaningfulValue(Object value) {
        if (value == null) {
            return false;
        }

        if (value instanceof String string) {
            return !string.isBlank();
        }

        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }

        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }

        return true;
    }

    private Map<String, Object> mergeTriageUrgency(Map<String, Object> base, Map<String, Object> aiUrgency) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("level", firstNonBlank(asString(aiUrgency.get("level")), asString(base.get("level"))));
        merged.put("action", firstNonBlank(asString(aiUrgency.get("action")), asString(base.get("action"))));
        merged.put("reason", firstNonBlank(asString(aiUrgency.get("reason")), asString(base.get("reason"))));
        return merged;
    }

    private List<String> mergeStringLists(Object first, Object second) {
        Set<String> merged = new LinkedHashSet<>();
        merged.addAll(mergeStringList(first));
        merged.addAll(mergeStringList(second));
        return new ArrayList<>(merged);
    }

    private String buildHybridAnalysisSource(Map<String, Object> analysis, Map<String, Object> aiInsights) {
        String baseSource = firstNonBlank(asString(analysis.get("analysisSource")), "python-pattern-engine");

        if (aiInsights == null || aiInsights.isEmpty()) {
            return baseSource;
        }

        String provider = firstNonBlank(asString(aiInsights.get("aiProvider")), aiReasoningService.getProviderLabel());
        return "dual-engine (" + baseSource + " + " + provider + ")";
    }

    private String truncateMiddle(String value, int maxChars) {
        String text = value == null ? "" : value;

        if (text.length() <= maxChars) {
            return text;
        }

        int head = Math.max(1, maxChars / 2);
        int tail = Math.max(1, maxChars - head - 5);
        return text.substring(0, head) + "\n...\n" + text.substring(text.length() - tail);
    }

    private Map<String, Object> normalizeFailureAnalysis(
            Map<String, Object> job,
            Map<String, Object> rawAnalysis,
            String logs
    ) {

        Map<String, Object> analysis = new LinkedHashMap<>();
        Map<String, Object> fallback = fallbackAnalysis(logs);

        String category = firstNonBlank(asString(rawAnalysis.get("category")), asString(fallback.get("category")));
        String failureType = firstNonBlank(asString(rawAnalysis.get("failureType")), asString(fallback.get("failureType")));
        String tool = firstNonBlank(asString(rawAnalysis.get("tool")), asString(fallback.get("tool")), "Unknown");
        String confidence = firstNonBlank(asString(rawAnalysis.get("confidence")), asString(fallback.get("confidence")), "LOW");
        String file = firstNonBlank(asString(rawAnalysis.get("file")), asString(fallback.get("file")));
        String line = firstNonBlank(asString(rawAnalysis.get("line")), asString(fallback.get("line")));
        String column = firstNonBlank(asString(rawAnalysis.get("column")), asString(fallback.get("column")));
        String errorMessage =
                firstNonBlank(asString(rawAnalysis.get("errorMessage")), asString(fallback.get("errorMessage")), "No error signature extracted");
        String rootCause =
                firstNonBlank(asString(rawAnalysis.get("rootCause")), asString(fallback.get("rootCause")), "Root cause needs manual confirmation");
        String fixRecommendation =
                firstNonBlank(asString(rawAnalysis.get("fixRecommendation")), asString(fallback.get("fixRecommendation")), "Review the first failing log block and the most recent related code change.");

        analysis.put("category", category);
        analysis.put("failureType", failureType);
        analysis.put("errorType", firstNonBlank(asString(rawAnalysis.get("errorType")), failureType));
        analysis.put(
                "errorTypeDisplay",
                firstNonBlank(
                        asString(rawAnalysis.get("errorTypeDisplay")),
                        asString(rawAnalysis.get("errorType")),
                        failureType
                )
        );
        analysis.put("tool", tool);
        analysis.put("confidence", confidence);
        analysis.put("file", file);
        analysis.put("line", line);
        analysis.put("column", column);
        analysis.put("classOrFunction", asString(rawAnalysis.get("classOrFunction")));
        analysis.put("symbolName", asString(rawAnalysis.get("symbolName")));
        analysis.put("symbolKind", asString(rawAnalysis.get("symbolKind")));
        analysis.put("missingSymbols", mergeStringList(rawAnalysis.get("missingSymbols")));
        analysis.put("errorMessage", errorMessage);
        analysis.put("rootFailureStatement", firstNonBlank(asString(rawAnalysis.get("rootFailureStatement")), errorMessage));
        analysis.put("rootCause", rootCause);
        analysis.put("whatIsWrong", firstNonBlank(asString(rawAnalysis.get("whatIsWrong")), rootCause));
        analysis.put("fixRecommendation", fixRecommendation);
        analysis.put("secondaryIssues", mergeStringList(rawAnalysis.get("secondaryIssues")));
        analysis.put("details", mergeStringList(rawAnalysis.get("details")));
        analysis.put("meaning", asString(rawAnalysis.get("meaning")));
        analysis.put("likelyCause", asString(rawAnalysis.get("likelyCause")));
        analysis.put("fixOptions", mergeStringList(rawAnalysis.get("fixOptions")));
        analysis.put("formattedAnalysis", asString(rawAnalysis.get("formattedAnalysis")));
        analysis.put("jobStatus", asString(job.get("status")));
        analysis.put("categoryDescription", categoryDescription(category));
        analysis.put("signals", mergeSignals(inferSignals(logs, category), rawAnalysis.get("signals")));
        analysis.put("resolutionType", resolutionType(category));
        analysis.put("confidenceScore", confidenceScore(confidence));
        analysis.put("evidence", buildEvidence(rawAnalysis.get("evidence"), logs));
        analysis.put("summary", asString(rawAnalysis.get("summary")));
        analysis.put("reasoning", mergeStringList(rawAnalysis.get("reasoning")));
        analysis.put("triageUrgency", safeMap(rawAnalysis.get("triageUrgency")));
        analysis.put("aiProvider", asString(rawAnalysis.get("aiProvider")));
        analysis.put("aiModel", asString(rawAnalysis.get("aiModel")));
        analysis.put("aiInsights", safeMap(rawAnalysis.get("aiInsights")));
        analysis.put("analysisLayers", mergeStringList(rawAnalysis.get("analysisLayers")));
        analysis.put("preprocessedLogs", safeMap(rawAnalysis.get("preprocessedLogs")));
        analysis.put("signatureId", asString(rawAnalysis.get("signatureId")));
        analysis.put("matchedPattern", asString(rawAnalysis.get("matchedPattern")));
        analysis.put("failureFingerprint", asString(rawAnalysis.get("failureFingerprint")));
        analysis.put(
                "requiresHumanReview",
                rawAnalysis.containsKey("requiresHumanReview")
                        ? Boolean.TRUE.equals(rawAnalysis.get("requiresHumanReview"))
                        : "LOW".equalsIgnoreCase(confidence) || "MEDIUM".equalsIgnoreCase(confidence)
        );
        analysis.put(
                "recommendationPolicy",
                firstNonBlank(asString(rawAnalysis.get("recommendationPolicy")), "Confidence-gated recommendation")
        );
        analysis.put(
                "novelFailure",
                rawAnalysis.containsKey("novelFailure")
                        ? Boolean.TRUE.equals(rawAnalysis.get("novelFailure"))
                        : "Unknown".equalsIgnoreCase(category)
        );
        analysis.put(
                "nextBestAction",
                firstNonBlank(
                        asString(rawAnalysis.get("nextBestAction")),
                        "Review the first high-signal log lines and recent code changes before retrying."
                )
        );
        analysis.put("knowledgeGap", asString(rawAnalysis.get("knowledgeGap")));
        analysis.put("requirementIds", mergeStringList(rawAnalysis.get("requirementIds")));
        analysis.put("verificationEntities", safeMap(rawAnalysis.get("verificationEntities")));

        return analysis;
    }

    private Map<String, Object> buildHistoricalCorrelation(
            String projectId,
            String pipelineCommit,
            String failureFile,
            String dependencyName,
            Map<String, Object> diffCorrelation
    ) {

        Map<String, Object> history = new LinkedHashMap<>();
        history.put("currentCommit", pipelineCommit);
        history.put("rootCauseCommitMatch", diffCorrelation.get("rootCauseCommitMatch"));

        if (pipelineCommit == null || failureFile == null || failureFile.isBlank()) {
            history.put("historicalSignal", "Insufficient failure location data for historical commit tracing");
            return history;
        }

        if (Boolean.TRUE.equals(diffCorrelation.get("rootCauseCommitMatch"))) {
            history.put("historicalSignal", "Current commit directly correlates with the failure location");
            history.put("suspectedIntroducedInCommit", pipelineCommit);
            return history;
        }

        String rootCauseCommit =
                gitLabService.findRootCauseCommitForFile(projectId, failureFile, pipelineCommit, dependencyName);

        if (rootCauseCommit != null) {
            history.put("historicalSignal", "Failure signature appears to have been introduced before the current commit");
            history.put("suspectedIntroducedInCommit", rootCauseCommit);
        } else {
            history.put("historicalSignal", "No earlier root-cause commit was confidently identified");
        }

        return history;
    }

    private Map<String, Object> buildCommitAnalysis(
            String projectId,
            String pipelineCommit,
            List<Map<String, Object>> diffs,
            Map<String, Object> primaryFailureAnalysis,
            List<Map<String, Object>> jobAnalyses
    ) {

        Map<String, Object> commitAnalysis = new LinkedHashMap<>();
        List<Map<String, Object>> changedFiles = new ArrayList<>();
        List<Map<String, Object>> changedConfigurationFiles = new ArrayList<>();
        List<Map<String, Object>> changedCodeFiles = new ArrayList<>();
        List<Map<String, Object>> changedTestFiles = new ArrayList<>();
        List<Map<String, Object>> changedSourceFiles = new ArrayList<>();
        List<Map<String, Object>> likelyRelatedFiles = new ArrayList<>();
        String primaryFailureFile = primaryFailureAnalysis == null ? null : asString(primaryFailureAnalysis.get("file"));

        for (Map<String, Object> diff : diffs) {
            String filePath = firstNonBlank(asString(diff.get("new_path")), asString(diff.get("old_path")));
            String category = categorizeFile(filePath);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", filePath);
            entry.put("category", category);
            entry.put("changeType", inferChangeType(asString(diff.get("diff"))));
            entry.put("matchedPrimaryFailureFile", primaryFailureFile != null && filePath.endsWith(primaryFailureFile));
            changedFiles.add(entry);

            if (category.contains("Configuration")) {
                changedConfigurationFiles.add(entry);
            }

            if ("Source Code".equals(category) || "Test Code".equals(category)) {
                changedCodeFiles.add(entry);
            }

            if ("Test Code".equals(category)) {
                changedTestFiles.add(entry);
            }

            if ("Source Code".equals(category)) {
                changedSourceFiles.add(entry);
            }

            if (Boolean.TRUE.equals(entry.get("matchedPrimaryFailureFile")) || category.contains("Configuration")) {
                likelyRelatedFiles.add(entry);
            }
        }

        Map<String, Object> commitDetails = pipelineCommit == null
                ? Collections.emptyMap()
                : safeMap(gitLabService.getCommitDetailsAsMap(projectId, pipelineCommit));

        commitAnalysis.put("commitSha", pipelineCommit);
        commitAnalysis.put("commitTitle", commitDetails.get("title"));
        commitAnalysis.put("commitAuthor", commitDetails.get("author_name"));
        commitAnalysis.put("commitAuthoredAt", commitDetails.get("authored_date"));
        commitAnalysis.put("commitWebUrl", commitDetails.get("web_url"));
        commitAnalysis.put("changedFiles", changedFiles);
        commitAnalysis.put("changedConfigurationFiles", changedConfigurationFiles);
        commitAnalysis.put("changedCodeFiles", changedCodeFiles);
        commitAnalysis.put("changedTestFiles", changedTestFiles);
        commitAnalysis.put("changedSourceFiles", changedSourceFiles);
        commitAnalysis.put("likelyRelatedFiles", likelyRelatedFiles);
        commitAnalysis.put("totalChangedFiles", changedFiles.size());
        commitAnalysis.put("configurationFileCount", changedConfigurationFiles.size());
        commitAnalysis.put("codeFileCount", changedCodeFiles.size());
        commitAnalysis.put(
                "analysisHighlights",
                buildCommitHighlights(changedConfigurationFiles, likelyRelatedFiles, jobAnalyses)
        );

        Map<String, Object> diffCorrelation = safeMap(primaryFailureAnalysis.get("diffCorrelation"));
        Map<String, Object> historicalCorrelation = safeMap(primaryFailureAnalysis.get("historicalCorrelation"));
        Map<String, Object> causalCommitAnalysis = buildCausalCommitAnalysis(
                primaryFailureAnalysis,
                diffCorrelation,
                historicalCorrelation,
                changedConfigurationFiles,
                changedTestFiles,
                changedSourceFiles,
                likelyRelatedFiles
        );

        commitAnalysis.put("causalCommitAnalysis", causalCommitAnalysis);
        commitAnalysis.put("causationType", causalCommitAnalysis.get("causationType"));
        commitAnalysis.put("causationConfidence", causalCommitAnalysis.get("confidence"));
        commitAnalysis.put("causalAssessment", causalCommitAnalysis.get("whyThisCommitCausedThisFailure"));
        commitAnalysis.put("correlationReasoning", causalCommitAnalysis.get("correlationReasoning"));
        commitAnalysis.put("smartFileCorrelation", causalCommitAnalysis.get("smartFileCorrelation"));
        commitAnalysis.put("preExistingIssueSuspected", causalCommitAnalysis.get("preExistingIssueSuspected"));

        return commitAnalysis;
    }

    private Map<String, Object> buildRecentFailurePatterns(
            String projectId,
            String pipelineId,
            Map<String, Object> pipeline,
            List<Map<String, Object>> failedJobs,
            Map<String, Object> primaryFailureAnalysis
    ) {

        Map<String, Object> patterns = new LinkedHashMap<>();
        String ref = asString(pipeline.get("ref"));

        if (ref == null) {
            patterns.put("historyWindow", "No pipeline ref available for trend analysis");
            patterns.put("recentPipelines", Collections.emptyList());
            return patterns;
        }

        List<Map<String, Object>> recentPipelines = gitLabService.getRecentPipelines(projectId, ref, 8);
        List<Map<String, Object>> snapshots = new ArrayList<>();
        int failedCount = 0;
        int successCount = 0;

        for (Map<String, Object> recentPipeline : recentPipelines) {
            String status = asString(recentPipeline.get("status"));

            if ("failed".equalsIgnoreCase(status)) {
                failedCount++;
            }

            if ("success".equalsIgnoreCase(status)) {
                successCount++;
            }

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("pipelineId", asString(recentPipeline.get("id")));
            snapshot.put("status", status);
            snapshot.put("ref", asString(recentPipeline.get("ref")));
            snapshot.put("sha", asString(recentPipeline.get("sha")));
            snapshot.put("createdAt", recentPipeline.get("created_at"));
            snapshot.put("webUrl", recentPipeline.get("web_url"));
            snapshots.add(snapshot);
        }

        Set<String> currentFailedJobNames = new LinkedHashSet<>();
        for (Map<String, Object> job : failedJobs) {
            currentFailedJobNames.add(asString(job.get("name")));
        }

        Map<String, Integer> recurringJobs = new LinkedHashMap<>();
        List<Map<String, Object>> similarFailures = new ArrayList<>();
        int inspectedFailedPipelines = 0;
        int sameSignatureCount = 0;
        int sameFingerprintCount = 0;
        String currentSignature = asString(primaryFailureAnalysis.get("signatureId"));
        String currentFingerprint = asString(primaryFailureAnalysis.get("failureFingerprint"));

        for (Map<String, Object> snapshot : snapshots) {
            String recentPipelineId = asString(snapshot.get("pipelineId"));

            if (pipelineId.equals(recentPipelineId) || !"failed".equalsIgnoreCase(asString(snapshot.get("status")))) {
                continue;
            }

            if (inspectedFailedPipelines >= 3) {
                break;
            }

            inspectedFailedPipelines++;

            try {
                List<Map<String, Object>> recentJobs = gitLabService.getPipelineJobsAsList(projectId, recentPipelineId);
                List<Map<String, Object>> recentFailedJobs = gitLabService.findAllFailedJobs(recentJobs);
                Map<String, Object> firstFailedJob = gitLabService.findFirstFailedJob(recentJobs);

                for (Map<String, Object> failedJob : recentFailedJobs) {
                    String name = asString(failedJob.get("name"));

                    if (currentFailedJobNames.contains(name)) {
                        recurringJobs.put(name, recurringJobs.getOrDefault(name, 0) + 1);
                    }
                }

                if (firstFailedJob != null && (currentSignature != null || currentFingerprint != null)) {
                    String recentJobId = asString(firstFailedJob.get("id"));
                    String recentLogs = gitLabService.getJobLogs(projectId, recentJobId);
                    Map<String, Object> recentRawAnalysis = pythonAnalysisService.analyzeLogs(recentLogs);
                    String recentSignature = asString(recentRawAnalysis.get("signatureId"));
                    String recentFingerprint = asString(recentRawAnalysis.get("failureFingerprint"));
                    boolean signatureMatch = currentSignature != null && currentSignature.equals(recentSignature);
                    boolean fingerprintMatch = currentFingerprint != null && currentFingerprint.equals(recentFingerprint);

                    if (signatureMatch || fingerprintMatch) {
                        Map<String, Object> recurrence = new LinkedHashMap<>();
                        recurrence.put("pipelineId", recentPipelineId);
                        recurrence.put("jobId", recentJobId);
                        recurrence.put("jobName", asString(firstFailedJob.get("name")));
                        recurrence.put("matchedOn", signatureMatch ? "signature" : "fingerprint");
                        recurrence.put("signatureId", recentSignature);
                        recurrence.put("failureFingerprint", recentFingerprint);
                        recurrence.put("createdAt", snapshot.get("createdAt"));
                        similarFailures.add(recurrence);
                    }

                    if (signatureMatch) {
                        sameSignatureCount++;
                    }

                    if (fingerprintMatch) {
                        sameFingerprintCount++;
                    }
                }
            } catch (Exception exception) {
                patterns.put("historyCollectionWarning", "Recent pipeline history could not be fully inspected");
            }
        }

        double recentFailureRatePercent = recentPipelines.isEmpty()
                ? 0.0
                : ((double) failedCount / recentPipelines.size()) * 100.0;

        patterns.put("historyWindow", "Recent pipelines on ref " + ref);
        patterns.put("recentPipelines", snapshots);
        patterns.put("recentFailedCount", failedCount);
        patterns.put("recentSuccessCount", successCount);
        patterns.put("recentFailureRatePercent", recentFailureRatePercent);
        patterns.put("recurringFailedJobs", recurringJobs);
        patterns.put("recurringSignal", !recurringJobs.isEmpty());
        patterns.put("currentSignatureId", currentSignature);
        patterns.put("currentFailureFingerprint", currentFingerprint);
        patterns.put("similarFailures", similarFailures);
        patterns.put("sameSignatureCount", sameSignatureCount);
        patterns.put("sameFingerprintCount", sameFingerprintCount);
        patterns.put("recurringSignatureSignal", sameSignatureCount > 0 || sameFingerprintCount > 0);
        patterns.put(
                "recurrenceSummary",
                buildRecurrenceSummary(similarFailures, sameSignatureCount, sameFingerprintCount)
        );

        return patterns;
    }

    private Map<String, Object> buildPipelineOverview(
            Map<String, Object> project,
            Map<String, Object> pipeline,
            Map<String, Object> pipelineSummary,
            Map<String, Object> pipelineIntelligence,
            List<Map<String, Object>> jobs,
            List<Map<String, Object>> failedJobs
    ) {

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("projectName", project.get("name"));
        overview.put("projectPath", project.get("path_with_namespace"));
        overview.put("projectWebUrl", project.get("web_url"));
        overview.put("pipelineStatus", pipeline.get("status"));
        overview.put("pipelineRef", pipeline.get("ref"));
        overview.put("pipelineSource", pipeline.get("source"));
        overview.put("pipelineCreatedAt", pipeline.get("created_at"));
        overview.put("pipelineUpdatedAt", pipeline.get("updated_at"));
        overview.put("pipelineDuration", pipeline.get("duration"));
        overview.put("pipelineWebUrl", pipeline.get("web_url"));
        overview.put("stageOrder", pipelineIntelligence.get("stageOrder"));
        overview.put("stageHealth", pipelineIntelligence.get("stageHealth"));
        overview.put("totalStages", safeList(pipelineSummary.get("distinctStages")).size());
        overview.put("totalJobs", jobs.size());
        overview.put("failedJobs", failedJobs.size());
        overview.put("releaseReadiness", failedJobs.isEmpty() ? "READY" : "BLOCKED");
        overview.put("failureBlastRadius", pipelineIntelligence.get("failureBlastRadius"));

        return overview;
    }

    private Map<String, Object> buildOperationalInsights(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> pipelineSummary,
            Map<String, Object> pipelineIntelligence,
            Map<String, Object> commitAnalysis,
            List<Map<String, Object>> failedJobs
    ) {

        Map<String, Object> insights = new LinkedHashMap<>();
        String category = primaryFailureAnalysis == null ? null : asString(primaryFailureAnalysis.get("category"));
        int downstreamImpact = toInt(pipelineIntelligence.get("failureBlastRadius"));
        Map<String, Object> triageUrgency = safeMap(primaryFailureAnalysis.get("triageUrgency"));

        insights.put("releaseReadiness", failedJobs.isEmpty() ? "READY" : "BLOCKED");
        insights.put("riskLevel", inferRiskLevel(category, downstreamImpact, failedJobs.size()));
        insights.put("blockingFailureCount", failedJobs.size());
        insights.put("affectedStages", pipelineIntelligence.get("blockedStages"));
        insights.put(
                "actionPriority",
                firstNonBlank(asString(triageUrgency.get("action")), failedJobs.isEmpty() ? "Monitor only" : "Immediate triage required")
        );
        insights.put("triageUrgency", triageUrgency);
        insights.put("recommendedOwners", collectRecommendedOwners(failedJobs, primaryFailureAnalysis));
        insights.put("triageChecklist", buildTriageChecklist(primaryFailureAnalysis, commitAnalysis));
        insights.put("enterpriseRecommendations", buildEnterpriseRecommendations(primaryFailureAnalysis, pipelineSummary, commitAnalysis));

        return insights;
    }

    private Map<String, Object> buildGovernance() {
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("readOnlyGitLabAccess", readOnlyMode);
        governance.put("automaticRemediationEnabled", false);
        governance.put("sourceCodeMutation", "disabled");
        governance.put("securityControls", List.of(
                "GitLab access is read-only",
                "No automatic fixes are applied to source code or pipeline configuration",
                "Sensitive access tokens should be injected through environment variables",
                "Analysis output is intended for engineering triage, not unattended deployment decisions"
        ));
        return governance;
    }

    private Map<String, Object> buildEnterpriseReadiness() {
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("supportedFailureCategories", SUPPORTED_FAILURE_CATEGORIES);
        readiness.put("supportedConfigurationFiles", SUPPORTED_CONFIGURATION_FILES);
        readiness.put("enterpriseCapabilities", List.of(
                "multi-job pipeline correlation",
                "primary vs secondary failure differentiation",
                "commit and configuration intelligence",
                "causal commit narratives and file-correlation reasoning",
                "proof-engine evidence and before-vs-after comparison",
                "fix-confidence and prevention guidance",
                "auto-fix patch suggestions and prioritized remediation",
                "timeline-based cause-to-effect reporting",
                "trend-aware failure context",
                "governance and read-only safety controls"
        ));
        readiness.put("designedFor", "large engineering teams operating complex GitLab pipelines");
        return readiness;
    }

    private Map<String, Object> buildAnalysisCoverage(
            List<Map<String, Object>> jobAnalyses,
            Map<String, Object> commitAnalysis,
            Map<String, Object> recentFailurePatterns,
            List<Map<String, Object>> failedJobs,
            String pipelineCommit
    ) {

        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("jobLogAnalysisPerformed", !jobAnalyses.isEmpty());
        coverage.put("commitCorrelationPerformed", pipelineCommit != null);
        coverage.put("configurationReviewPerformed", !safeList(commitAnalysis.get("changedConfigurationFiles")).isEmpty());
        coverage.put("recentPatternAnalysisPerformed", !safeList(recentFailurePatterns.get("recentPipelines")).isEmpty());
        coverage.put("primaryFailureIdentified", !failedJobs.isEmpty());
        coverage.put("limitations", List.of(
                "Long-term persistence for cross-pipeline failure history is not stored in this repository yet",
                "Automated notifications and dashboards are not included in the current implementation",
                "Final remediation decisions still require engineering review"
        ));
        return coverage;
    }

    private Map<String, Object> buildDecisionEngine(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis
    ) {

        Map<String, Object> decision = new LinkedHashMap<>();
        String category = asString(primaryFailureAnalysis.get("category"));
        String commitAuthor = asString(commitAnalysis.get("commitAuthor"));
        String issueClass = classifyIssueClass(category);
        int confidencePercent = (int) Math.round(confidenceScore(asString(primaryFailureAnalysis.get("confidence"))) * 100.0);
        List<String> confidenceReasons =
                buildConfidenceExplanation(primaryFailureAnalysis, commitAnalysis, Collections.emptyMap());

        decision.put("issueClass", issueClass);
        decision.put("isCodeIssue", "CODE".equals(issueClass));
        decision.put("isInfraIssue", "INFRASTRUCTURE".equals(issueClass));
        decision.put("isExternalIssue", "EXTERNAL_SYSTEM".equals(issueClass));
        decision.put("isTestIssue", "TEST_OR_VERIFICATION".equals(issueClass));
        decision.put("isConfigurationIssue", "BUILD_OR_CONFIGURATION".equals(issueClass));
        decision.put("whoLikelyOwnsIt", likelyOwner(issueClass, commitAuthor, primaryFailureAnalysis));
        decision.put(
                "reasoning",
                firstNonBlank(asString(commitAnalysis.get("causalAssessment")), decisionReasoning(issueClass, category))
        );
        decision.put("shouldBlameRecentCommit",
                List.of("CODE", "BUILD_OR_CONFIGURATION", "TEST_OR_VERIFICATION").contains(issueClass)
                        && commitAuthor != null
        );
        decision.put("confidencePercent", confidencePercent);
        decision.put("confidenceReasons", confidenceReasons);
        decision.put(
                "confidenceNarrative",
                buildConfidenceNarrative(issueClass, confidencePercent, confidenceReasons)
        );

        return decision;
    }

    private Map<String, Object> buildVerificationIntelligence(Map<String, Object> primaryFailureAnalysis) {
        Map<String, Object> verification = new LinkedHashMap<>();
        Map<String, Object> entities = safeMap(primaryFailureAnalysis.get("verificationEntities"));
        List<String> requirementIds = mergeStringList(primaryFailureAnalysis.get("requirementIds"));
        List<String> ddVariables = mergeStringList(entities.get("dataDictionaryVariables"));
        boolean rvstestReferenced = Boolean.TRUE.equals(entities.get("rvstestReferenced"));
        boolean pythonReferenced = Boolean.TRUE.equals(entities.get("pythonVerificationReferenced"));
        boolean stubReferenced = Boolean.TRUE.equals(entities.get("stubReferenced"));
        boolean coverageReferenced = Boolean.TRUE.equals(entities.get("coverageReferenced"));

        verification.put("requirementIdsDetected", requirementIds);
        verification.put("dataDictionaryVariablesDetected", ddVariables);
        verification.put("rvstestReferenced", rvstestReferenced);
        verification.put("pythonVerificationReferenced", pythonReferenced);
        verification.put("stubReferenced", stubReferenced);
        verification.put("coverageReferenced", coverageReferenced);
        verification.put("verificationMismatchSuspected",
                rvstestReferenced || !ddVariables.isEmpty() || stubReferenced
        );
        verification.put("verificationFocusAreas", buildVerificationFocusAreas(ddVariables, rvstestReferenced, stubReferenced, coverageReferenced));
        verification.put("connectedAssets", List.of(
                "GitLab logs",
                "GitLab commit metadata",
                "GitLab diff metadata"
        ));
        verification.put("missingAssets", List.of(
                "rvstest artifact ingestion is not configured in this repository",
                "CSV data dictionary ingestion is not configured in this repository",
                "Structured verification result baselines are not configured in this repository"
        ));

        return verification;
    }

    private Map<String, Object> buildTraceabilityIntelligence(Map<String, Object> primaryFailureAnalysis) {
        Map<String, Object> traceability = new LinkedHashMap<>();
        List<String> requirementIds = mergeStringList(primaryFailureAnalysis.get("requirementIds"));

        traceability.put("jamaConnected", false);
        traceability.put("requirementIdsDetected", requirementIds);
        traceability.put("traceabilityStatus", requirementIds.isEmpty() ? "UNCONFIGURED" : "PARTIAL_FROM_LOG_EVIDENCE");
        traceability.put("requirementToCodeLinked", false);
        traceability.put("requirementToTestLinked", false);
        traceability.put("gaps", List.of(
                "Jama integration is not configured in this repository",
                "Requirement-to-code mapping source is not configured in this repository",
                "Requirement-to-test mapping source is not configured in this repository"
        ));

        if (!requirementIds.isEmpty()) {
            traceability.put("observedRequirementEvidence",
                    "Requirement-like identifiers were detected in logs, but no authoritative traceability source is connected.");
        }

        return traceability;
    }

    private Map<String, Object> buildEnvironmentComparison(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> decisionEngine,
            Map<String, Object> commitAnalysis
    ) {

        Map<String, Object> comparison = new LinkedHashMap<>();
        String issueClass = asString(decisionEngine.get("issueClass"));

        comparison.put("localEnvironmentAvailable", false);
        comparison.put("ciEnvironmentAvailable", true);
        comparison.put("productionEnvironmentAvailable", false);
        comparison.put("comparisonStatus", "PARTIAL");
        comparison.put("driftSuspected",
                List.of("INFRASTRUCTURE", "EXTERNAL_SYSTEM", "ENVIRONMENT").contains(issueClass)
        );
        comparison.put("signals", List.of(
                "GitLab CI context is available",
                "Local machine baseline is not connected",
                "Production baseline is not connected"
        ));
        comparison.put("recommendedNextStep",
                "To compare local vs CI vs prod precisely, connect exported environment manifests or version inventories.");
        comparison.put("recentConfigurationChanges", safeList(commitAnalysis.get("changedConfigurationFiles")));

        return comparison;
    }

    private Map<String, Object> buildAdvancedSignals(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> recentFailurePatterns,
            Map<String, Object> pipelineSummary,
            Map<String, Object> pipelineOverview
    ) {

        Map<String, Object> signals = new LinkedHashMap<>();
        String category = asString(primaryFailureAnalysis.get("category"));
        boolean recurringSignal = Boolean.TRUE.equals(recentFailurePatterns.get("recurringSignal"));
        boolean verificationMismatch =
                Boolean.TRUE.equals(safeMap(primaryFailureAnalysis.get("verificationEntities")).get("rvstestReferenced"))
                        || !mergeStringList(safeMap(primaryFailureAnalysis.get("verificationEntities")).get("dataDictionaryVariables")).isEmpty();

        signals.put("flakyTestSuspected",
                recurringSignal && ("Test Failure".equals(category) || Boolean.TRUE.equals(primaryFailureAnalysis.get("supportsAutomatedRetry")))
        );
        signals.put("regressionRisk",
                "Code Compilation Failure".equals(category) || "Test Failure".equals(category)
        );
        signals.put("coverageDropSuspected",
                containsAny(asString(primaryFailureAnalysis.get("failureType")).toLowerCase(Locale.ROOT), "coverage")
                        || Boolean.TRUE.equals(safeMap(primaryFailureAnalysis.get("verificationEntities")).get("coverageReferenced"))
        );
        signals.put("performanceRegressionStatus", "Not enough timing baselines connected yet");
        signals.put("verificationMismatchSuspected", verificationMismatch);
        signals.put("historySignal", recentFailurePatterns.get("historyWindow"));
        signals.put("pipelineDuration", pipelineOverview.get("pipelineDuration"));
        signals.put("failedJobs", pipelineSummary.get("failedJobs"));
        signals.put("recurringSignatureSignal", recentFailurePatterns.get("recurringSignatureSignal"));
        signals.put("recurrenceSummary", recentFailurePatterns.get("recurrenceSummary"));

        return signals;
    }

    private List<Map<String, Object>> buildDomainCoverage(
            Map<String, Object> verificationIntelligence,
            Map<String, Object> traceabilityIntelligence,
            Map<String, Object> environmentComparison,
            Map<String, Object> advancedSignals
    ) {

        List<Map<String, Object>> domains = new ArrayList<>();
        domains.add(domain("Code-Level Problems", "SUPPORTED", "Patterns cover compilation, linker, runtime, memory, merge, and coding-standard signatures."));
        domains.add(domain("Build System Problems", "SUPPORTED", "Build/config signatures cover Maven, Gradle, CMake, Docker, Helm, Terraform, and toolchain mismatch patterns."));
        domains.add(domain("Environment & Dependency Problems", "PARTIAL", asString(environmentComparison.get("recommendedNextStep"))));
        domains.add(domain("Testing & Verification Problems", "PARTIAL", joinReadable(buildVerificationFocusAreas(
                mergeStringList(verificationIntelligence.get("dataDictionaryVariablesDetected")),
                Boolean.TRUE.equals(verificationIntelligence.get("rvstestReferenced")),
                Boolean.TRUE.equals(verificationIntelligence.get("stubReferenced")),
                Boolean.TRUE.equals(verificationIntelligence.get("coverageReferenced"))
        ))));
        domains.add(domain("Requirements & Traceability Problems", "UNCONFIGURED", asString(traceabilityIntelligence.get("traceabilityStatus"))));
        domains.add(domain("Configuration Problems", "SUPPORTED", "Pipeline and build configuration failures are classified and correlated with changed files."));
        domains.add(domain("Infrastructure Problems", "SUPPORTED", "Runner, timeout, resource, image-pull, and flaky-execution signatures are supported."));
        domains.add(domain("External Dependency Problems", "SUPPORTED", "API, DB, artifact, network, DNS, auth, and TLS failure signatures are supported."));
        domains.add(domain("Advanced Intelligence Problems", "PARTIAL", joinReadable(List.of(
                "history-backed recurrence detection",
                "flaky-signal detection",
                "coverage-drop signal detection",
                String.valueOf(advancedSignals.get("performanceRegressionStatus"))
        ))));
        return domains;
    }

    private Map<String, Object> buildSmartAnswers(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis,
            Map<String, Object> recentFailurePatterns,
            Map<String, Object> decisionEngine,
            Map<String, Object> traceabilityIntelligence,
            Map<String, Object> verificationIntelligence
    ) {

        Map<String, Object> answers = new LinkedHashMap<>();
        String issueClass = asString(decisionEngine.get("issueClass"));
        String commitAuthor = asString(commitAnalysis.get("commitAuthor"));
        List<String> requirementIds = mergeStringList(traceabilityIntelligence.get("requirementIdsDetected"));
        Map<String, Object> exactFixGuidance = safeMap(primaryFailureAnalysis.get("exactFixGuidance"));
        Map<String, Object> triageUrgency = safeMap(primaryFailureAnalysis.get("triageUrgency"));

        if ("HEALTHY".equals(issueClass) || "Pipeline Success".equals(asString(primaryFailureAnalysis.get("category")))) {
            answers.put("whyDidPipelineFail", "It did not fail. The pipeline completed successfully with no blocking jobs.");
            answers.put("whoCausedIt", "No failure owner is needed because the pipeline passed.");
            answers.put("isItCodeOrInfra", "HEALTHY");
            answers.put("whichRequirementIsAffected", "No failing requirement impact was detected in this successful run.");
            answers.put("whatIsTheExactFix", "No fix is required. Use this pipeline as a healthy baseline.");
            answers.put("hasThisHappenedBefore", "This run is successful. Compare it against failed runs as a baseline.");
            answers.put("verificationConcern", "No active verification failure was detected in this pipeline.");
            answers.put("howUrgentIsIt", "Monitor only.");
            return answers;
        }

        answers.put("whyDidPipelineFail",
                firstNonBlank(
                        asString(primaryFailureAnalysis.get("whyExplanation")),
                        asString(primaryFailureAnalysis.get("rootCause")),
                        "The current logs do not contain enough high-confidence evidence yet."
                )
        );
        answers.put("whoCausedIt", likelyOwner(issueClass, commitAuthor, primaryFailureAnalysis));
        answers.put("isItCodeOrInfra", issueClass);
        answers.put("whichRequirementIsAffected",
                requirementIds.isEmpty()
                        ? "No connected requirement source is available. No authoritative requirement impact can be confirmed yet."
                        : String.join(", ", requirementIds)
        );
        answers.put("whatIsTheExactFix",
                firstNonBlank(
                        asString(exactFixGuidance.get("summary")),
                        asString(exactFixGuidance.get("suggestedDiff")),
                        asString(primaryFailureAnalysis.get("fixRecommendation")),
                        "No fix recommendation available."
                ));
        answers.put("hasThisHappenedBefore",
                firstNonBlank(
                        asString(recentFailurePatterns.get("recurrenceSummary")),
                        Boolean.TRUE.equals(recentFailurePatterns.get("recurringSignal"))
                                ? "Yes, a recurring signal was detected in recent failed pipelines."
                                : "No clear recurrence signal was detected in the recent history window."
                )
        );
        answers.put("verificationConcern",
                Boolean.TRUE.equals(verificationIntelligence.get("verificationMismatchSuspected"))
                        ? "Verification mismatch is suspected; inspect rvstest, dd_ mappings, stubs, or coverage thresholds."
                        : "No strong verification mismatch signal was detected from the available logs."
        );
        answers.put("howUrgentIsIt",
                firstNonBlank(asString(triageUrgency.get("action")), "Urgency has not been classified yet."));

        return answers;
    }

    private Map<String, Object> buildSuccessfulPipelineAnalysis(
            Map<String, Object> pipeline,
            Map<String, Object> pipelineSummary,
            Map<String, Object> recentFailurePatterns,
            Map<String, Object> commitAnalysis
    ) {

        Map<String, Object> analysis = new LinkedHashMap<>();
        boolean recurring = Boolean.TRUE.equals(recentFailurePatterns.get("recurringSignatureSignal"));
        List<String> signals = new ArrayList<>();
        signals.add("all blocking jobs completed successfully");

        if (recurring) {
            signals.add("recent history contains failures, but the current pipeline recovered successfully");
        }

        analysis.put("category", "Pipeline Success");
        analysis.put("failureType", "No blocking failure detected");
        analysis.put("tool", firstNonBlank(asString(pipeline.get("source")), "GitLab CI"));
        analysis.put("confidence", "HIGH");
        analysis.put("errorType", "NO_FAILURE");
        analysis.put("errorTypeDisplay", "No Failure");
        analysis.put("classOrFunction", null);
        analysis.put("symbolName", null);
        analysis.put("symbolKind", null);
        analysis.put("errorMessage", "No blocking error detected; pipeline passed successfully.");
        analysis.put("rootFailureStatement", "No blocking failure detected; pipeline passed successfully.");
        analysis.put("rootCause", "The pipeline completed successfully with no failed jobs.");
        analysis.put("whatIsWrong", "Nothing is currently broken in this pipeline run.");
        analysis.put("fixRecommendation", "No immediate fix is required. Use this run as a healthy CI baseline.");
        analysis.put("jobStatus", "success");
        analysis.put("categoryDescription", "The CI pipeline is healthy and no blocking failure was detected in this run.");
        analysis.put("signals", signals);
        analysis.put("resolutionType", "Monitoring");
        analysis.put("confidenceScore", 0.98);
        analysis.put("evidence", Map.of(
                "summary", "Pipeline passed successfully",
                "passedJobs", pipelineSummary.get("passedJobs"),
                "failedJobs", pipelineSummary.get("failedJobs")
        ));
        analysis.put("requiresHumanReview", false);
        analysis.put("recommendationPolicy", "Success baseline");
        analysis.put("novelFailure", false);
        analysis.put("nextBestAction", "Keep this pipeline result as a baseline and compare future failures against it.");
        analysis.put("requirementIds", Collections.emptyList());
        analysis.put("verificationEntities", Collections.emptyMap());
        analysis.put("severity", "LOW");
        analysis.put("recommendedOwner", "Pipeline owner");
        analysis.put("supportsAutomatedRetry", false);
        analysis.put("resolutionPlaybook", List.of(
                "No corrective change is required.",
                "Use this successful run as a comparison baseline for future failures.",
                "Review recurring-history signals only if similar failures reappear."
        ));
        analysis.put("logHighlights", Collections.emptyList());
        analysis.put("analysisSource", "success-pipeline-baseline");
        analysis.put("missingSymbols", Collections.emptyList());
        analysis.put("secondaryIssues", Collections.emptyList());
        analysis.put("formattedAnalysis",
                "ROOT FAILURE: No blocking failure detected\n\nERROR TYPE: NO_FAILURE\n\nLOCATION:\nSuccessful pipeline\n\nWHAT IS WRONG: Nothing is currently broken in this pipeline run.\n\nFIX: No immediate fix is required. Use this run as a healthy CI baseline.\n\nSECONDARY ISSUES: None");
        analysis.put("signatureId", null);
        analysis.put("matchedPattern", null);
        analysis.put("failureFingerprint", null);
        return analysis;
    }

    private void normalizeCommitAnalysisForSuccess(Map<String, Object> commitAnalysis) {
        commitAnalysis.put("causationType", "NO_FAILURE");
        commitAnalysis.put("causationConfidence", "NOT_APPLICABLE");
        commitAnalysis.put("causalAssessment", "No failure attribution is required because the pipeline passed successfully.");
        commitAnalysis.put("correlationReasoning", List.of(
                "The current commit correlates with a successful pipeline outcome.",
                "No root-cause file attribution is needed for a healthy pipeline run."
        ));
        commitAnalysis.put("smartFileCorrelation", "The changed files completed successfully in CI for this pipeline.");
        commitAnalysis.put("preExistingIssueSuspected", false);

        List<String> highlights = new ArrayList<>(mergeStringList(commitAnalysis.get("analysisHighlights")));
        highlights.add(0, "The current commit executed successfully in CI.");
        commitAnalysis.put("analysisHighlights", new ArrayList<>(new LinkedHashSet<>(highlights)));
    }

    private Map<String, Object> buildProofEngine(
            String projectId,
            String pipelineId,
            Map<String, Object> pipelineIntelligence,
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis,
            Map<String, Object> recentFailurePatterns
    ) {

        Map<String, Object> proof = new LinkedHashMap<>();
        List<String> evidence = new ArrayList<>();

        if ("Pipeline Success".equals(asString(primaryFailureAnalysis.get("category")))) {
            proof.put("verdict", "PIPELINE_HEALTHY");
            proof.put("proofStrength", "NOT_APPLICABLE");
            proof.put("conclusion", "No blame proof is required because the pipeline passed successfully.");
            proof.put("evidence", List.of(
                    "No failed jobs were detected in the pipeline.",
                    "Current commit correlates with a healthy CI outcome."
            ));
            proof.put("previousSuccessfulPipeline", Collections.emptyMap());
            proof.put("previousSuccessfulComparison", Collections.emptyMap());
            proof.put("historicalMatch", Collections.emptyMap());
            return proof;
        }

        Map<String, Object> primaryFailure = safeMap(pipelineIntelligence.get("primaryFailure"));
        String primaryJobName = asString(primaryFailure.get("jobName"));
        String primaryStage = asString(primaryFailure.get("stage"));
        Map<String, Object> previousSuccessfulSnapshot = findRecentPipelineSnapshot(
                safeList(recentFailurePatterns.get("recentPipelines")),
                pipelineId,
                "success"
        );
        Map<String, Object> previousSuccessfulComparison = previousSuccessfulSnapshot.isEmpty()
                ? Collections.emptyMap()
                : buildPipelineComparisonSnapshot(
                        projectId,
                        asString(previousSuccessfulSnapshot.get("pipelineId")),
                        primaryJobName,
                        primaryStage,
                        primaryFailureAnalysis
                );
        List<Map<String, Object>> similarFailures = castMapList(recentFailurePatterns.get("similarFailures"));
        Map<String, Object> mostRecentSimilarFailure = similarFailures.isEmpty()
                ? Collections.emptyMap()
                : similarFailures.get(0);
        boolean noCurrentConfigChanges = safeList(commitAnalysis.get("changedConfigurationFiles")).isEmpty();
        boolean changedTestsOnly = !safeList(commitAnalysis.get("changedTestFiles")).isEmpty()
                && safeList(commitAnalysis.get("changedConfigurationFiles")).isEmpty();
        boolean sameHistoricalFailure = !similarFailures.isEmpty();
        boolean previousSuccessSignalDetected = Boolean.TRUE.equals(previousSuccessfulComparison.get("signalDetected"));
        boolean previousSuccessExists = !previousSuccessfulSnapshot.isEmpty();
        String verdict;
        String conclusion;
        String proofStrength;

        if (sameHistoricalFailure) {
            evidence.add("Same failure signature or fingerprint was detected in pipeline "
                    + asString(mostRecentSimilarFailure.get("pipelineId"))
                    + ".");
        }

        if (previousSuccessExists) {
            if (previousSuccessSignalDetected) {
                evidence.add("Comparable warning evidence was already present in previous successful pipeline "
                        + asString(previousSuccessfulSnapshot.get("pipelineId"))
                        + ".");
            } else {
                evidence.add("Previous successful pipeline "
                        + asString(previousSuccessfulSnapshot.get("pipelineId"))
                        + " did not show the current failure signal in the comparable job.");
            }
        }

        if (noCurrentConfigChanges) {
            evidence.add("The current commit did not modify build or pipeline configuration files.");
        } else {
            evidence.add("The current commit changed configuration files: "
                    + joinReadable(limitList(collectEntryPaths(safeList(commitAnalysis.get("changedConfigurationFiles"))), 3))
                    + ".");
        }

        if (changedTestsOnly) {
            evidence.add("The current commit changed test files without touching the failing configuration artifact.");
        }

        if (sameHistoricalFailure && noCurrentConfigChanges) {
            verdict = "NOT_INTRODUCED_BY_CURRENT_COMMIT";
            conclusion = "Evidence shows this issue existed before this commit.";
            proofStrength = "HIGH";
        } else if (previousSuccessExists
                && !previousSuccessSignalDetected
                && !noCurrentConfigChanges
                && List.of("DIRECT", "CONFIG_CORRELATED").contains(asString(commitAnalysis.get("causationType")))) {
            verdict = "LIKELY_INTRODUCED_BY_CURRENT_COMMIT";
            conclusion = "Evidence suggests the current commit introduced the failure signal after a previously clean pipeline.";
            proofStrength = "HIGH";
        } else if (previousSuccessExists && !previousSuccessSignalDetected && changedTestsOnly) {
            verdict = "TRIGGERED_BY_CURRENT_COMMIT_SURFACING_OLDER_ISSUE";
            conclusion = "Evidence suggests the current commit triggered a path that surfaced an older configuration defect.";
            proofStrength = "MEDIUM";
        } else if (sameHistoricalFailure) {
            verdict = "PRE_EXISTING_PATTERN";
            conclusion = "Evidence points to a recurring pattern that likely predates the current commit.";
            proofStrength = "MEDIUM";
        } else {
            verdict = "INSUFFICIENT_PROOF";
            conclusion = "The current evidence is not strong enough to prove whether the failure was introduced or only exposed by this commit.";
            proofStrength = "LOW";
        }

        proof.put("verdict", verdict);
        proof.put("proofStrength", proofStrength);
        proof.put("conclusion", conclusion);
        proof.put("evidence", evidence);
        proof.put("previousSuccessfulPipeline", previousSuccessfulSnapshot);
        proof.put("previousSuccessfulComparison", previousSuccessfulComparison);
        proof.put("historicalMatch", mostRecentSimilarFailure);
        return proof;
    }

    private Map<String, Object> buildBeforeAfterCommitAnalysis(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> proofEngine,
            Map<String, Object> commitAnalysis
    ) {

        Map<String, Object> analysis = new LinkedHashMap<>();

        if ("Pipeline Success".equals(asString(primaryFailureAnalysis.get("category")))) {
            analysis.put("beforePipelineId", null);
            analysis.put("beforeState", "No failure baseline comparison is required for a successful pipeline.");
            analysis.put("afterPipelineId", asString(commitAnalysis.get("commitSha")));
            analysis.put("afterState", "Pipeline passed successfully.");
            analysis.put("differences", List.of("Current pipeline is healthy and can be used as a comparison baseline."));
            analysis.put("conclusion", "The current commit produced a successful CI result.");
            return analysis;
        }

        Map<String, Object> previousSuccessfulPipeline = safeMap(proofEngine.get("previousSuccessfulPipeline"));
        Map<String, Object> previousSuccessfulComparison = safeMap(proofEngine.get("previousSuccessfulComparison"));
        String rootError = asString(safeMap(primaryFailureAnalysis.get("signalRanking")).get("rootError"));
        List<String> differences = new ArrayList<>();

        String beforeSummary;

        if (previousSuccessfulPipeline.isEmpty()) {
            beforeSummary = "No previous successful pipeline was available for direct comparison.";
        } else if (Boolean.TRUE.equals(previousSuccessfulComparison.get("signalDetected"))) {
            beforeSummary = "Comparable warning evidence was already present in pipeline "
                    + asString(previousSuccessfulPipeline.get("pipelineId"))
                    + ".";
            differences.add("The warning existed before the current pipeline, but it did not block that earlier run.");
        } else {
            beforeSummary = "No comparable warning signal was detected in previous successful pipeline "
                    + asString(previousSuccessfulPipeline.get("pipelineId"))
                    + ".";
            differences.add("The failure signal appears after the current change set.");
        }

        if (safeList(commitAnalysis.get("changedConfigurationFiles")).isEmpty()) {
            differences.add("No build configuration file was changed in the current commit.");
        } else {
            differences.add("Current commit changed build or pipeline configuration files.");
        }

        if (!safeList(commitAnalysis.get("changedTestFiles")).isEmpty()) {
            differences.add("Current commit introduced or modified tests, which may have triggered a new dependency or verification path.");
        }

        analysis.put("beforePipelineId", asString(previousSuccessfulPipeline.get("pipelineId")));
        analysis.put("beforeState", beforeSummary);
        analysis.put("afterPipelineId", asString(commitAnalysis.get("commitSha")));
        analysis.put("afterState", firstNonBlank(rootError, asString(primaryFailureAnalysis.get("rootCause"))));
        analysis.put("differences", differences);
        analysis.put("conclusion", asString(proofEngine.get("conclusion")));
        return analysis;
    }

    private Map<String, Object> buildDependencyImpact(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> pipelineIntelligence,
            Map<String, Object> commitAnalysis
    ) {

        Map<String, Object> impact = new LinkedHashMap<>();

        if ("Pipeline Success".equals(asString(primaryFailureAnalysis.get("category")))) {
            impact.put("dependencies", Collections.emptyList());
            impact.put("affectedStages", Collections.emptyList());
            impact.put("affectedModulesEstimate", estimateAffectedModules(commitAnalysis));
            impact.put("summary", "No dependency disruption was detected in this successful pipeline.");
            impact.put("reasoning", List.of("The pipeline passed, so no dependency-related blocking impact is active in this run."));
            return impact;
        }

        List<String> dependencies = extractDependencyCandidates(primaryFailureAnalysis);
        Set<String> affectedStages = new LinkedHashSet<>();
        Map<String, Object> primaryFailure = safeMap(pipelineIntelligence.get("primaryFailure"));
        String primaryStage = asString(primaryFailure.get("stage"));

        if (primaryStage != null) {
            affectedStages.add(primaryStage);
        }

        affectedStages.addAll(mergeStringList(pipelineIntelligence.get("blockedStages")));
        int moduleEstimate = estimateAffectedModules(commitAnalysis);
        String errorMessage = firstNonBlank(asString(primaryFailureAnalysis.get("errorMessage")), "").toLowerCase(Locale.ROOT);
        List<String> reasoning = new ArrayList<>();
        String summary;

        if (!dependencies.isEmpty() && containsAny(errorMessage, "must be unique", "duplicate dependency", "duplicate declaration")) {
            summary = joinReadable(dependencies)
                    + " appears in duplicate build evidence. Duplicate dependency declarations can break the resolution tree and affect "
                    + joinReadable(new ArrayList<>(affectedStages))
                    + ".";
            reasoning.add("Duplicate dependency declarations destabilize transitive resolution and packaging.");
            reasoning.add("Affected modules estimate: " + moduleEstimate + ".");
        } else if (!dependencies.isEmpty()) {
            summary = "Dependency evidence was detected for "
                    + joinReadable(dependencies)
                    + ", with impact extending into "
                    + joinReadable(new ArrayList<>(affectedStages))
                    + ".";
            reasoning.add("Dependency-related failures usually affect compile, test, and packaging flows.");
        } else {
            summary = "No explicit dependency identifier was extracted, but the failure can still propagate through "
                    + joinReadable(new ArrayList<>(affectedStages))
                    + ".";
        }

        impact.put("dependencies", dependencies);
        impact.put("affectedStages", new ArrayList<>(affectedStages));
        impact.put("affectedModulesEstimate", moduleEstimate);
        impact.put("summary", summary);
        impact.put("reasoning", reasoning);
        return impact;
    }

    private Map<String, Object> buildRiskPropagation(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> pipelineOverview,
            Map<String, Object> pipelineIntelligence,
            Map<String, Object> recentFailurePatterns
    ) {

        Map<String, Object> risk = new LinkedHashMap<>();
        List<String> ifNotFixed = new ArrayList<>();
        String category = asString(primaryFailureAnalysis.get("category"));

        if ("Pipeline Success".equals(category)) {
            risk.put("summary", "No immediate propagation risk is active because the pipeline passed.");
            risk.put("ifNotFixed", List.of("No blocking remediation is required for this successful run."));
            risk.put("futurePipelineRisk", "LOW");
            risk.put("deploymentBlocked", false);
            risk.put("ciStabilityRisk", "STABLE");
            return risk;
        }

        boolean recurringSignature = Boolean.TRUE.equals(recentFailurePatterns.get("recurringSignatureSignal"));
        boolean blockedRelease = "BLOCKED".equalsIgnoreCase(asString(pipelineOverview.get("releaseReadiness")));
        int blastRadius = toInt(pipelineIntelligence.get("failureBlastRadius"));

        if ("Build Configuration Failure".equals(category) || "Pipeline Configuration Failure".equals(category)) {
            ifNotFixed.add("Future pipelines on the same branch are likely to fail until the configuration defect is corrected.");
        }

        if (blockedRelease) {
            ifNotFixed.add("Deployment and release movement remain blocked while this failure persists.");
        }

        if (blastRadius > 0) {
            ifNotFixed.add("Downstream jobs and later stages will continue to be skipped or fail secondarily.");
        }

        if (recurringSignature) {
            ifNotFixed.add("CI stability will degrade further because the same failure pattern is already recurring.");
        }

        if (ifNotFixed.isEmpty()) {
            ifNotFixed.add("No major propagation risk was identified from the current evidence.");
        }

        risk.put("summary", ifNotFixed.get(0));
        risk.put("ifNotFixed", ifNotFixed);
        risk.put("futurePipelineRisk", recurringSignature || blockedRelease ? "HIGH" : "MEDIUM");
        risk.put("deploymentBlocked", blockedRelease);
        risk.put("ciStabilityRisk", recurringSignature ? "DEGRADED" : "WATCH");
        return risk;
    }

    private Map<String, Object> buildTestImpactAnalysis(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis,
            Map<String, Object> pipelineIntelligence
    ) {

        Map<String, Object> impact = new LinkedHashMap<>();
        List<String> changedTests = collectEntryPaths(safeList(commitAnalysis.get("changedTestFiles")));
        List<String> testNames = new ArrayList<>();

        for (String path : changedTests) {
            testNames.add(fileName(path));
        }

        String causationType = asString(commitAnalysis.get("causationType"));
        String summary;

        if ("Pipeline Success".equals(asString(primaryFailureAnalysis.get("category")))) {
            summary = changedTests.isEmpty()
                    ? "No changed test files were identified, and the pipeline passed successfully."
                    : "Changed tests executed successfully in the current pipeline.";
        } else if (changedTests.isEmpty()) {
            summary = "No changed test files were identified in the current commit.";
        } else if ("SURFACED_BY_TEST_CHANGE".equals(causationType)) {
            summary = "New or changed tests likely triggered execution paths that exposed the failing configuration or dependency defect.";
        } else if ("Test Failure".equals(asString(primaryFailureAnalysis.get("category")))) {
            summary = "Changed tests are directly involved in the failing verification path.";
        } else {
            summary = "Changed tests may be contributing context, but they are not the only signal in the failure.";
        }

        impact.put("triggeringTests", limitList(testNames, 5));
        impact.put("changedTestFiles", limitList(changedTests, 5));
        impact.put("primaryStage", asString(safeMap(pipelineIntelligence.get("primaryFailure")).get("stage")));
        impact.put("summary", summary);
        impact.put("reasoning", List.of(
                "Test changes can trigger dependency resolution, compile paths, or verification setup differences.",
                "This report separates test-triggered exposure from direct test-logic defects when possible."
        ));
        return impact;
    }

    private Map<String, Object> buildFailurePatternIntelligence(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> recentFailurePatterns
    ) {

        Map<String, Object> pattern = new LinkedHashMap<>();
        if ("Pipeline Success".equals(asString(primaryFailureAnalysis.get("category")))) {
            pattern.put("pattern", "No active failure pattern in this successful pipeline");
            pattern.put("recurring", false);
            pattern.put("patternMeaning", "The current run is healthy and can be used as a baseline.");
            pattern.put("likelyRootBehavior", List.of("healthy pipeline execution"));
            return pattern;
        }

        String signatureId = asString(primaryFailureAnalysis.get("signatureId"));
        String category = asString(primaryFailureAnalysis.get("category"));
        String patternName = inferPatternName(signatureId, category, primaryFailureAnalysis);
        List<String> likelyBehaviors = inferPatternBehaviors(signatureId, category, primaryFailureAnalysis);

        pattern.put("pattern", patternName);
        pattern.put("recurring", Boolean.TRUE.equals(recentFailurePatterns.get("recurringSignatureSignal")));
        pattern.put("patternMeaning", asString(recentFailurePatterns.get("recurrenceSummary")));
        pattern.put("likelyRootBehavior", likelyBehaviors);
        return pattern;
    }

    private Map<String, Object> buildFixConfidence(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis,
            Map<String, Object> recentFailurePatterns,
            Map<String, Object> proofEngine
    ) {

        Map<String, Object> fixConfidence = new LinkedHashMap<>();
        if ("Pipeline Success".equals(asString(primaryFailureAnalysis.get("category")))) {
            fixConfidence.put("band", "NOT_APPLICABLE");
            fixConfidence.put("breakdown", Collections.emptyList());
            fixConfidence.put("narrative", "No fix is required because the pipeline passed successfully.");
            return fixConfidence;
        }

        List<Map<String, Object>> breakdown = new ArrayList<>();
        int score = 0;

        if (asString(primaryFailureAnalysis.get("signatureId")) != null) {
            score += 40;
            breakdown.add(confidenceFactor("Signature match", 40, "A known catalog signature matched the failure."));
        }

        if (asString(safeMap(primaryFailureAnalysis.get("signalRanking")).get("rootError")) != null) {
            score += 30;
            breakdown.add(confidenceFactor("Log evidence", 30, "A clear root error was extracted from the logs."));
        }

        if (Boolean.TRUE.equals(recentFailurePatterns.get("recurringSignatureSignal"))) {
            score += 10;
            breakdown.add(confidenceFactor("Historical match", 10, "Recent pipelines showed the same signature or fingerprint."));
        }

        String causationType = asString(commitAnalysis.get("causationType"));

        if (List.of("DIRECT", "CONFIG_CORRELATED").contains(causationType)) {
            score += 10;
            breakdown.add(confidenceFactor("Commit correlation", 10, "The current commit aligns strongly with the failing location or config."));
        } else if (List.of("INDIRECT", "SURFACED_BY_TEST_CHANGE", "UNCERTAIN").contains(causationType)) {
            score -= 6;
            breakdown.add(confidenceFactor("Weak commit correlation", -6, "The commit correlation is indirect or incomplete."));
        }

        Map<String, Object> exactFixGuidance = safeMap(primaryFailureAnalysis.get("exactFixGuidance"));

        if (asString(exactFixGuidance.get("autoFixPatch")) != null || asString(exactFixGuidance.get("suggestedDiff")) != null) {
            score += 15;
            breakdown.add(confidenceFactor("Exact fix available", 15, "The analyzer produced a concrete corrective action."));
        }

        if (Boolean.TRUE.equals(primaryFailureAnalysis.get("requiresHumanReview"))) {
            score -= 8;
            breakdown.add(confidenceFactor("Human review required", -8, "Some ambiguity remains in the recommendation."));
        }

        if ("NOT_INTRODUCED_BY_CURRENT_COMMIT".equals(asString(proofEngine.get("verdict")))) {
            score += 6;
            breakdown.add(confidenceFactor("Proof engine", 6, "Historical proof strengthens the diagnosis even without direct blame on the commit."));
        }

        int boundedScore = Math.max(25, Math.min(98, score));
        fixConfidence.put("percent", boundedScore);
        fixConfidence.put("band", boundedScore >= 90 ? "VERY_HIGH" : boundedScore >= 75 ? "HIGH" : "MEDIUM");
        fixConfidence.put("breakdown", breakdown);
        fixConfidence.put(
                "narrative",
                boundedScore + "% fix confidence based on signature match, log evidence, history, and commit correlation quality."
        );
        return fixConfidence;
    }

    private Map<String, Object> buildTeamLearningMode(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis,
            Map<String, Object> failurePatternIntelligence
    ) {

        Map<String, Object> learning = new LinkedHashMap<>();
        List<String> prevention = new ArrayList<>();
        List<String> processImprovements = new ArrayList<>();
        String signatureId = asString(primaryFailureAnalysis.get("signatureId"));
        String category = asString(primaryFailureAnalysis.get("category"));

        if ("Pipeline Success".equals(category)) {
            learning.put("pattern", "Healthy baseline");
            learning.put("prevention", List.of(
                    "Use this successful pipeline as a reference baseline for future incident comparison.",
                    "Keep current build and test guardrails in place."
            ));
            learning.put("processImprovements", List.of(
                    "Capture this healthy run as part of release-readiness evidence.",
                    "Compare future failures against this successful commit and pipeline shape."
            ));
            return learning;
        }

        if (containsAny(lower(signatureId), "duplicate", "dependency")
                || containsAny(lower(asString(primaryFailureAnalysis.get("errorMessage"))), "must be unique", "duplicate dependency")) {
            prevention.add("Enforce dependency uniqueness checks in CI before the full build runs.");
            prevention.add("Add the Maven Enforcer plugin or an equivalent dependency-hygiene gate.");
            prevention.add("Centralize shared versions in dependency management instead of manual scattered edits.");
        }

        if ("Test Failure".equals(category) || "TEST_OR_VERIFICATION".equals(classifyIssueClass(category))) {
            prevention.add("Link requirement, test, and verification assets so expectation drift is caught before merge.");
        }

        if (!safeList(commitAnalysis.get("changedConfigurationFiles")).isEmpty()) {
            processImprovements.add("Route configuration-file changes through build-aware review gates.");
        }

        processImprovements.add("Turn recurring patterns into pre-merge static or CI checks.");
        processImprovements.add("Use this incident pattern to update team build and verification playbooks.");

        learning.put("pattern", failurePatternIntelligence.get("pattern"));
        learning.put("prevention", prevention);
        learning.put("processImprovements", processImprovements);
        return learning;
    }

    private Map<String, Object> buildExecutiveSummary(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> proofEngine,
            Map<String, Object> testImpactAnalysis
    ) {

        Map<String, Object> summary = new LinkedHashMap<>();
        String category = asString(primaryFailureAnalysis.get("category"));
        String proofVerdict = asString(proofEngine.get("verdict"));
        String oneLine;

        if ("Pipeline Success".equals(category)) {
            oneLine = "Pipeline passed successfully with no blocking failures detected";

            if (!mergeStringList(testImpactAnalysis.get("triggeringTests")).isEmpty()) {
                oneLine += "; changed tests executed cleanly";
            }

            summary.put("rootCauseOneLine", oneLine + ".");
            summary.put("managerSummary", oneLine + ".");
            return summary;
        }

        if (containsAny(lower(asString(primaryFailureAnalysis.get("errorMessage"))), "must be unique", "duplicate dependency")) {
            oneLine = "Duplicate build dependencies caused the pipeline failure";
        } else if (category != null) {
            oneLine = category + " caused the pipeline failure";
        } else {
            oneLine = "A pipeline failure was detected";
        }

        if ("NOT_INTRODUCED_BY_CURRENT_COMMIT".equals(proofVerdict)) {
            oneLine += "; evidence shows the issue existed before the current commit";
        } else if ("TRIGGERED_BY_CURRENT_COMMIT_SURFACING_OLDER_ISSUE".equals(proofVerdict)) {
            oneLine += "; the current commit likely triggered a path that exposed an older defect";
        } else if ("LIKELY_INTRODUCED_BY_CURRENT_COMMIT".equals(proofVerdict)) {
            oneLine += "; the current commit likely introduced the failing signal";
        }

        if (!mergeStringList(testImpactAnalysis.get("triggeringTests")).isEmpty()) {
            oneLine += "; changed tests were part of the triggering path";
        }

        summary.put("rootCauseOneLine", oneLine + ".");
        summary.put("managerSummary", oneLine + ".");
        return summary;
    }

    private Map<String, Object> buildRootCauseGraph(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis,
            Map<String, Object> dependencyImpact,
            Map<String, Object> testImpactAnalysis,
            Map<String, Object> pipelineIntelligence
    ) {

        Map<String, Object> graph = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        int index = 1;

        if ("Pipeline Success".equals(asString(primaryFailureAnalysis.get("category")))) {
            nodes.add(graphNode("n1", "Trigger", "Commit executed", "The current commit ran in CI."));
            nodes.add(graphNode("n2", "Validation", "Jobs passed", "All blocking jobs completed successfully."));
            nodes.add(graphNode("n3", "Outcome", "Healthy pipeline", "No root cause graph is needed because the pipeline is green."));
            graph.put("nodes", nodes);
            graph.put("graphSummary", buildGraphSummary(nodes));
            return graph;
        }

        boolean testTriggered = !mergeStringList(testImpactAnalysis.get("triggeringTests")).isEmpty()
                || "SURFACED_BY_TEST_CHANGE".equals(asString(commitAnalysis.get("causationType")));

        if (testTriggered) {
            nodes.add(graphNode(
                    "n" + index++,
                    "Trigger",
                    "Test change",
                    firstNonBlank(asString(testImpactAnalysis.get("summary")), "Changed tests triggered a new execution path.")
            ));
        } else if (!safeList(commitAnalysis.get("changedConfigurationFiles")).isEmpty()) {
            nodes.add(graphNode(
                    "n" + index++,
                    "Trigger",
                    "Configuration change",
                    "Build or CI configuration changes are part of the failure path."
            ));
        } else {
            nodes.add(graphNode(
                    "n" + index++,
                    "Trigger",
                    "Pipeline execution",
                    "The pipeline run exposed the first blocking failure signal."
            ));
        }

        if (!mergeStringList(dependencyImpact.get("dependencies")).isEmpty()) {
            nodes.add(graphNode(
                    "n" + index++,
                    "Mechanism",
                    "Dependency resolution triggered",
                    firstNonBlank(asString(dependencyImpact.get("summary")), "Dependency resolution entered a broken state.")
            ));
        }

        nodes.add(graphNode(
                "n" + index++,
                "Root cause",
                firstNonBlank(asString(safeMap(primaryFailureAnalysis.get("signalRanking")).get("rootError")), "Root error"),
                firstNonBlank(asString(primaryFailureAnalysis.get("rootCause")), "The analyzer identified a blocking root cause.")
        ));

        Map<String, Object> primaryFailure = safeMap(pipelineIntelligence.get("primaryFailure"));
        nodes.add(graphNode(
                "n" + index++,
                "Failure",
                firstNonBlank(asString(primaryFailure.get("jobName")), "Primary failing job"),
                "The first blocking job failed in stage " + firstNonBlank(asString(primaryFailure.get("stage")), "unknown") + "."
        ));

        if (!mergeStringList(pipelineIntelligence.get("blockedStages")).isEmpty()) {
            nodes.add(graphNode(
                    "n" + index,
                    "Impact",
                    "Blocked stages",
                    "Downstream stages were blocked: " + String.join(", ", mergeStringList(pipelineIntelligence.get("blockedStages"))) + "."
            ));
        }

        graph.put("nodes", nodes);
        graph.put("graphSummary", buildGraphSummary(nodes));
        return graph;
    }

    private Map<String, Object> buildPipelineHealthScore(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> pipelineSummary,
            Map<String, Object> pipelineOverview,
            Map<String, Object> pipelineIntelligence,
            Map<String, Object> recentFailurePatterns,
            Map<String, Object> commitAnalysis
    ) {

        Map<String, Object> health = new LinkedHashMap<>();
        List<Map<String, Object>> breakdown = new ArrayList<>();
        int score = 100;
        int failedJobs = toInt(pipelineSummary.get("failedJobs"));
        int blastRadius = toInt(pipelineIntelligence.get("failureBlastRadius"));
        boolean recurring = Boolean.TRUE.equals(recentFailurePatterns.get("recurringSignatureSignal"));
        boolean configIssue = "BUILD_OR_CONFIGURATION".equals(classifyIssueClass(asString(primaryFailureAnalysis.get("category"))));
        int warningCount = mergeStringList(primaryFailureAnalysis.get("logHighlights")).size();

        int failurePenalty = Math.min(35, failedJobs * 15);
        score -= failurePenalty;
        breakdown.add(healthFactor("Failures", -failurePenalty, "Failed jobs reduce the health score."));

        int blastPenalty = Math.min(20, blastRadius * 8);
        score -= blastPenalty;
        breakdown.add(healthFactor("Blast radius", -blastPenalty, "Downstream impact lowers pipeline resilience."));

        if (recurring && failedJobs > 0) {
            score -= 12;
            breakdown.add(healthFactor("Recurrence", -12, "Recurring signatures indicate unstable pipeline behavior."));
        }

        if (configIssue && failedJobs > 0) {
            score -= 10;
            breakdown.add(healthFactor("Configuration quality", -10, "Build or CI configuration issues weaken overall pipeline health."));
        }

        int warningPenalty = Math.min(8, Math.max(0, warningCount - 2) * 2);
        score -= warningPenalty;
        breakdown.add(healthFactor("Warnings/signals", -warningPenalty, "High warning volume often predicts additional instability."));

        if (safeList(commitAnalysis.get("changedConfigurationFiles")).isEmpty()) {
            score += 2;
            breakdown.add(healthFactor("Stable config surface", 2, "No config-file changes in this commit slightly improves confidence."));
        }

        if (failedJobs == 0) {
            score += 6;
            breakdown.add(healthFactor("Successful execution", 6, "The pipeline completed with no failed jobs."));
        }

        int boundedScore = Math.max(0, Math.min(100, score));
        health.put("score", boundedScore);
        health.put("grade", boundedScore >= 85 ? "A" : boundedScore >= 70 ? "B" : boundedScore >= 55 ? "C" : boundedScore >= 40 ? "D" : "F");
        health.put("summary", "Pipeline health score " + boundedScore + "/100 based on failures, recurrence, config quality, and warning volume.");
        health.put("breakdown", breakdown);
        health.put("releaseReadiness", pipelineOverview.get("releaseReadiness"));
        return health;
    }

    private List<Map<String, Object>> buildPrioritizedFixes(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> teamLearningMode,
            Map<String, Object> dependencyImpact
    ) {

        List<Map<String, Object>> fixes = new ArrayList<>();
        String errorMessage = lower(asString(primaryFailureAnalysis.get("errorMessage")));
        String category = asString(primaryFailureAnalysis.get("category"));
        List<String> dependencies = mergeStringList(dependencyImpact.get("dependencies"));

        if ("Pipeline Success".equals(category)) {
            fixes.add(prioritizedFix(
                    1,
                    "No immediate fix required",
                    "Keep this successful pipeline as a baseline reference.",
                    "The current run is healthy."
            ));
            fixes.add(prioritizedFix(
                    2,
                    "Preserve current guardrails",
                    "Keep build, dependency, and test checks enabled.",
                    "Healthy pipelines should still preserve their prevention controls."
            ));
            return fixes;
        }

        if (containsAny(errorMessage, "must be unique", "duplicate dependency")) {
            fixes.add(prioritizedFix(
                    1,
                    "Remove duplicate dependencies",
                    "Delete duplicate entries for " + firstNonBlank(joinReadable(dependencies), "the duplicated dependencies") + ".",
                    "This blocks dependency resolution and the build cannot proceed until it is corrected."
            ));
            fixes.add(prioritizedFix(
                    2,
                    "Add dependency management guardrails",
                    "Introduce or tighten dependency management so each dependency is declared once.",
                    "This reduces the chance of the same misconfiguration recurring."
            ));
            fixes.add(prioritizedFix(
                    3,
                    "Clean unused or stray dependencies",
                    "Review the build file for unnecessary dependency declarations and remove them.",
                    "Cleanup lowers long-term config entropy but is secondary to restoring the build."
            ));
        } else {
            fixes.add(prioritizedFix(
                    1,
                    "Apply the primary corrective fix",
                    firstNonBlank(asString(safeMap(primaryFailureAnalysis.get("exactFixGuidance")).get("summary")), asString(primaryFailureAnalysis.get("fixRecommendation"))),
                    "This directly addresses the blocking root cause."
            ));

            int rank = 2;
            for (String prevention : limitList(mergeStringList(teamLearningMode.get("prevention")), 2)) {
                fixes.add(prioritizedFix(
                        rank++,
                        prevention,
                        prevention,
                        "This reduces repeat failures after the immediate issue is fixed."
                ));
            }
        }

        return fixes;
    }

    private Map<String, Object> buildNotificationPreview(
            Map<String, Object> executiveSummary,
            Map<String, Object> riskPropagation,
            Map<String, Object> fixConfidence,
            Map<String, Object> primaryFailureAnalysis,
            List<Map<String, Object>> prioritizedFixes
    ) {

        Map<String, Object> preview = new LinkedHashMap<>();
        String topFix = prioritizedFixes.isEmpty()
                ? firstNonBlank(asString(primaryFailureAnalysis.get("fixRecommendation")), "Review the primary failure.")
                : asString(prioritizedFixes.get(0).get("action"));
        boolean healthy = "Pipeline Success".equals(asString(primaryFailureAnalysis.get("category")));
        String message = (healthy ? "CI Pipeline Healthy\n" : "CI Failure Detected\n")
                + "Root cause: " + firstNonBlank(asString(executiveSummary.get("rootCauseOneLine")), "Unknown") + "\n"
                + "Impact: " + firstNonBlank(asString(riskPropagation.get("summary")), healthy ? "No blocking impact" : "Pipeline blocked") + "\n"
                + "Fix: " + topFix + "\n"
                + "Confidence: " + firstNonBlank(asString(fixConfidence.get("percent")), healthy ? "100" : "Unknown") + "%";

        preview.put("slackMessage", message);
        preview.put("teamsMessage", message);
        preview.put("topFix", topFix);
        return preview;
    }

    private String generateAutoFixPatch(
            String projectId,
            String pipelineRef,
            String pipelineCommit,
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis,
            List<String> dependencyCandidates
    ) {

        String errorMessage = lower(asString(primaryFailureAnalysis.get("errorMessage")));

        if (!containsAny(errorMessage, "must be unique", "duplicate dependency", "duplicate declaration")) {
            return null;
        }

        String targetFile = resolveTargetFilePath(primaryFailureAnalysis, commitAnalysis);

        if (targetFile == null) {
            return null;
        }

        String content = fetchFileContent(projectId, targetFile, pipelineCommit, pipelineRef);

        if (content == null || content.isBlank()) {
            return null;
        }

        Map<String, Object> duplicateBlock = findDuplicateDependencyBlock(content, dependencyCandidates);

        if (duplicateBlock.isEmpty()) {
            return null;
        }

        String block = asString(duplicateBlock.get("block"));
        String dependencyKey = asString(duplicateBlock.get("dependency"));

        if (block == null) {
            return null;
        }

        StringBuilder patch = new StringBuilder();
        patch.append("--- a/").append(targetFile).append("\n");
        patch.append("+++ b/").append(targetFile).append("\n");
        patch.append("@@\n");

        for (String line : block.split("\\R")) {
            patch.append("-").append(line).append("\n");
        }

        patch.append("+<!-- remove duplicate ")
                .append(firstNonBlank(dependencyKey, "dependency"))
                .append(" declaration; keep the canonical entry above -->\n");

        return patch.toString().trim();
    }

    private void enrichPrimaryFailureAnalysis(
            String projectId,
            String pipelineRef,
            String pipelineCommit,
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis,
            Map<String, Object> recentFailurePatterns,
            Map<String, Object> pipelineIntelligence
    ) {

        if (primaryFailureAnalysis == null || primaryFailureAnalysis.isEmpty()) {
            return;
        }

        primaryFailureAnalysis.put("signalRanking", buildSignalRanking(primaryFailureAnalysis));
        primaryFailureAnalysis.put(
                "confidenceExplanation",
                buildConfidenceExplanation(primaryFailureAnalysis, commitAnalysis, recentFailurePatterns)
        );
        primaryFailureAnalysis.put(
                "exactFixGuidance",
                buildExactFixGuidance(projectId, pipelineRef, pipelineCommit, primaryFailureAnalysis, commitAnalysis)
        );
        primaryFailureAnalysis.put("whyExplanation", buildWhyExplanation(primaryFailureAnalysis, commitAnalysis));
        primaryFailureAnalysis.put("triageUrgency", buildTriageUrgency(primaryFailureAnalysis, pipelineIntelligence));
    }

    private List<Map<String, Object>> buildFailureTimeline(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> pipelineIntelligence,
            Map<String, Object> commitAnalysis
    ) {

        if (primaryFailureAnalysis == null || primaryFailureAnalysis.isEmpty()) {
            return Collections.emptyList();
        }

        if ("Pipeline Success".equals(asString(primaryFailureAnalysis.get("category")))) {
            return List.of(
                    timelineStep(1, "TRIGGER", "Pipeline executed", "The requested pipeline ran to completion."),
                    timelineStep(2, "SUCCESS", "All blocking jobs passed", "No failed jobs or blocked stages were detected."),
                    timelineStep(3, "BASELINE", "Healthy baseline", "Use this successful pipeline as a reference point for future failures.")
            );
        }

        List<Map<String, Object>> timeline = new ArrayList<>();
        Map<String, Object> signalRanking = safeMap(primaryFailureAnalysis.get("signalRanking"));
        Map<String, Object> primaryFailure = safeMap(pipelineIntelligence.get("primaryFailure"));
        List<String> blockedStages = mergeStringList(pipelineIntelligence.get("blockedStages"));
        List<Object> downstreamImpactedJobs = safeList(pipelineIntelligence.get("downstreamImpactedJobs"));
        List<Object> secondaryFailures = safeList(pipelineIntelligence.get("secondaryFailures"));

        timeline.add(timelineStep(
                1,
                "CAUSE",
                "Root cause detected",
                firstNonBlank(asString(signalRanking.get("rootError")), asString(primaryFailureAnalysis.get("rootCause")))
        ));

        if (!primaryFailure.isEmpty()) {
            String jobName = asString(primaryFailure.get("jobName"));
            String stage = asString(primaryFailure.get("stage"));
            timeline.add(timelineStep(
                    2,
                    "EFFECT",
                    "Primary failing job",
                    firstNonBlank(
                            jobName == null && stage == null ? null : firstNonBlank(jobName, "Unknown job") + " failed in stage " + firstNonBlank(stage, "unknown") + ".",
                            "The first blocking job failure was identified."
                    )
            ));
        }

        if (!secondaryFailures.isEmpty() || !downstreamImpactedJobs.isEmpty()) {
            timeline.add(timelineStep(
                    3,
                    "CASCADE",
                    "Downstream impact",
                    "The initial failure led to "
                            + secondaryFailures.size()
                            + " secondary failure(s) and "
                            + downstreamImpactedJobs.size()
                            + " downstream impacted job(s)."
            ));
        }

        if (!blockedStages.isEmpty()) {
            timeline.add(timelineStep(
                    4,
                    "RELEASE",
                    "Blocked stages",
                    "Stages blocked by the failure: " + String.join(", ", blockedStages) + "."
            ));
        }

        String causalAssessment = asString(commitAnalysis.get("causalAssessment"));

        if (causalAssessment != null) {
            timeline.add(timelineStep(5, "ATTRIBUTION", "Commit correlation", causalAssessment));
        }

        return timeline;
    }

    private Map<String, Object> buildCausalCommitAnalysis(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> diffCorrelation,
            Map<String, Object> historicalCorrelation,
            List<Map<String, Object>> changedConfigurationFiles,
            List<Map<String, Object>> changedTestFiles,
            List<Map<String, Object>> changedSourceFiles,
            List<Map<String, Object>> likelyRelatedFiles
    ) {

        Map<String, Object> analysis = new LinkedHashMap<>();
        String failureFile = asString(primaryFailureAnalysis.get("file"));
        String category = asString(primaryFailureAnalysis.get("category"));
        String suspectedIntroducedInCommit = asString(historicalCorrelation.get("suspectedIntroducedInCommit"));
        String currentCommit = asString(historicalCorrelation.get("currentCommit"));
        boolean preExistingIssueSuspected =
                suspectedIntroducedInCommit != null && !suspectedIntroducedInCommit.equals(currentCommit);
        boolean directCommitMatch = Boolean.TRUE.equals(diffCorrelation.get("rootCauseCommitMatch"));
        boolean directFileOverlap = hasMatchedPrimaryFailureFile(likelyRelatedFiles);
        boolean configurationChanged = !changedConfigurationFiles.isEmpty();
        boolean testChanged = !changedTestFiles.isEmpty();
        boolean sourceChanged = !changedSourceFiles.isEmpty();
        List<String> reasoning = new ArrayList<>();
        String causationType;
        String confidence;
        String why;

        if (directCommitMatch || directFileOverlap) {
            causationType = "DIRECT";
            confidence = firstNonBlank(asString(diffCorrelation.get("confidenceLevel")), "VERY_HIGH");
            why = "The current commit directly modified the failing file or line, so the failure is strongly attributable to this change.";
            reasoning.add("Direct overlap was found between the commit diff and the extracted failure location.");
        } else if (failureFile != null && isBuildLikeFile(failureFile) && configurationChanged) {
            causationType = "CONFIG_CORRELATED";
            confidence = "HIGH";
            why = "The failure originates from " + failureFile + ", and this commit also changed build or CI configuration files, which strongly correlates with the break.";
            reasoning.add("Build or pipeline configuration files changed in the same commit as the failure.");
        } else if (failureFile != null && isBuildLikeFile(failureFile) && testChanged) {
            causationType = "SURFACED_BY_TEST_CHANGE";
            confidence = "MEDIUM";
            why = "The failing file is " + failureFile + ", but the commit changed test code rather than the build file itself. This suggests a pre-existing configuration problem surfaced when the new test path triggered dependency resolution or packaging.";
            reasoning.add("No direct file overlap was found between changed files and the failing build artifact.");
            reasoning.add("Test execution can trigger dependency resolution and expose latent build configuration defects.");
        } else if (preExistingIssueSuspected) {
            causationType = "SURFACED_PRE_EXISTING";
            confidence = "MEDIUM";
            why = "The current commit appears to have exposed an older defect. Historical analysis points to an earlier commit as the probable introduction point.";
            reasoning.add("Historical commit tracing indicates the failure signature may predate the current commit.");
        } else if (sourceChanged || testChanged || configurationChanged) {
            causationType = "INDIRECT";
            confidence = "LOW";
            why = "The current commit changed nearby code or configuration, but there is no direct proof that it touched the failing location. The causation is plausible but indirect.";
            reasoning.add("Changed files are adjacent to the failing workflow, but no direct overlap was extracted.");
        } else {
            causationType = "UNCERTAIN";
            confidence = "LOW";
            why = "The analyzer could not prove a causal link between the current commit and the extracted failure.";
            reasoning.add("No meaningful diff correlation was available for the failing location.");
        }

        if (preExistingIssueSuspected) {
            reasoning.add("Historical signal suggests commit " + suspectedIntroducedInCommit + " may have introduced the defect.");
        }

        if (testChanged) {
            reasoning.add("Changed test files: " + joinReadable(limitList(collectEntryPaths(changedTestFiles), 3)) + ".");
        }

        if (configurationChanged) {
            reasoning.add("Changed config files: " + joinReadable(limitList(collectEntryPaths(changedConfigurationFiles), 3)) + ".");
        }

        analysis.put("causationType", causationType);
        analysis.put("confidence", confidence);
        analysis.put("directFileOverlap", directFileOverlap);
        analysis.put("preExistingIssueSuspected", preExistingIssueSuspected);
        analysis.put("whyThisCommitCausedThisFailure", why);
        analysis.put("correlationReasoning", reasoning);
        analysis.put("smartFileCorrelation", buildSmartFileCorrelation(
                failureFile,
                changedConfigurationFiles,
                changedTestFiles,
                changedSourceFiles,
                preExistingIssueSuspected
        ));
        return analysis;
    }

    private Map<String, Object> buildSignalRanking(Map<String, Object> primaryFailureAnalysis) {
        Map<String, Object> ranking = new LinkedHashMap<>();
        List<String> candidates = new ArrayList<>();
        String errorMessage = asString(primaryFailureAnalysis.get("errorMessage"));

        if (errorMessage != null && !"No error signature extracted".equalsIgnoreCase(errorMessage)) {
            candidates.add(errorMessage);
        }

        candidates.addAll(mergeStringList(primaryFailureAnalysis.get("logHighlights")));
        String rootError = null;

        for (String candidate : candidates) {
            if (isRootErrorCandidate(candidate)) {
                rootError = candidate;
                break;
            }
        }

        if (rootError == null && !candidates.isEmpty()) {
            rootError = candidates.get(0);
        }

        List<String> relatedSignals = new ArrayList<>();
        List<String> noiseSignals = new ArrayList<>();

        for (String candidate : candidates) {
            if (candidate == null || candidate.equals(rootError)) {
                continue;
            }

            if (isNoiseSignal(candidate)) {
                noiseSignals.add(candidate);
            } else {
                relatedSignals.add(candidate);
            }
        }

        if (relatedSignals.isEmpty()) {
            relatedSignals.addAll(limitList(mergeStringList(primaryFailureAnalysis.get("signals")), 3));
        }

        ranking.put("rootError", rootError);
        ranking.put("relatedSignals", limitList(relatedSignals, 4));
        ranking.put("noiseSignals", limitList(noiseSignals, 3));
        return ranking;
    }

    private List<String> buildConfidenceExplanation(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis,
            Map<String, Object> recentFailurePatterns
    ) {

        List<String> reasons = new ArrayList<>();
        String signatureId = asString(primaryFailureAnalysis.get("signatureId"));
        String category = asString(primaryFailureAnalysis.get("category"));
        String issueClass = classifyIssueClass(category);
        Map<String, Object> diffCorrelation = safeMap(primaryFailureAnalysis.get("diffCorrelation"));
        String leadingSignal = asString(primaryFailureAnalysis.get("errorMessage"));

        if (signatureId != null) {
            reasons.add("A known signature-catalog match was found: " + signatureId + ".");
        }

        if (Boolean.TRUE.equals(diffCorrelation.get("rootCauseCommitMatch"))) {
            reasons.add("The current commit directly touched the failing file or line.");
        } else if ("BUILD_OR_CONFIGURATION".equals(issueClass)
                && !safeList(commitAnalysis.get("changedConfigurationFiles")).isEmpty()) {
            reasons.add("Recent configuration changes align with the failure class.");
        } else if ("TEST_OR_VERIFICATION".equals(issueClass)) {
            reasons.add("The dominant signals point to failing verification logic rather than runner instability.");
        } else if ("INFRASTRUCTURE".equals(issueClass) || "EXTERNAL_SYSTEM".equals(issueClass)) {
            reasons.add("The strongest signals come from operational or dependency failures, not source edits.");
        }

        if (leadingSignal != null && "BUILD_OR_CONFIGURATION".equals(issueClass)
                && !containsAny(leadingSignal.toLowerCase(Locale.ROOT), "assert", "expected", "segmentation", "nullpointer")) {
            reasons.add("No stronger runtime or assertion failure signal appeared ahead of the build/config error.");
        }

        if (!recentFailurePatterns.isEmpty()) {
            if (Boolean.TRUE.equals(recentFailurePatterns.get("recurringSignatureSignal"))) {
                reasons.add("Similar signature-backed failures were found in recent pipeline history.");
            } else {
                reasons.add("This signature did not recur in the recent history window.");
            }
        }

        if (Boolean.TRUE.equals(primaryFailureAnalysis.get("requiresHumanReview"))) {
            reasons.add("Some ambiguity remains, so the recommendation should be validated by an engineer.");
        }

        return limitList(reasons, 5);
    }

    private String buildConfidenceNarrative(String issueClass, int confidencePercent, List<String> reasons) {
        String readableIssueClass = readableIssueClass(issueClass).toLowerCase(Locale.ROOT);
        List<String> clauses = new ArrayList<>();

        for (String reason : limitList(reasons, 3)) {
            clauses.add(trimTrailingPeriod(reason));
        }

        if (clauses.isEmpty()) {
            return confidencePercent + "% confidence based on the currently available evidence.";
        }

        return confidencePercent
                + "% confidence this is a "
                + readableIssueClass
                + " issue because "
                + String.join("; ", clauses)
                + ".";
    }

    private String buildWhyExplanation(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis
    ) {

        List<String> explanation = new ArrayList<>();
        String rootCause = asString(primaryFailureAnalysis.get("rootCause"));
        String category = asString(primaryFailureAnalysis.get("category"));
        String errorMessage = asString(primaryFailureAnalysis.get("errorMessage"));
        String causalAssessment = asString(commitAnalysis.get("causalAssessment"));

        if (rootCause != null) {
            explanation.add(rootCause);
        } else if (category != null) {
            explanation.add(categoryDescription(category));
        }

        if (errorMessage != null && !"No error signature extracted".equalsIgnoreCase(errorMessage)) {
            explanation.add("The leading error signal was: " + errorMessage + ".");
        }

        if (causalAssessment != null) {
            explanation.add(causalAssessment);
        }

        return String.join(" ", limitList(explanation, 3));
    }

    private Map<String, Object> buildExactFixGuidance(
            String projectId,
            String pipelineRef,
            String pipelineCommit,
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis
    ) {

        Map<String, Object> guidance = new LinkedHashMap<>();
        String category = asString(primaryFailureAnalysis.get("category"));
        String file = asString(primaryFailureAnalysis.get("file"));
        String line = asString(primaryFailureAnalysis.get("line"));
        String errorMessage = firstNonBlank(asString(primaryFailureAnalysis.get("errorMessage")), "");
        String lowerError = errorMessage.toLowerCase(Locale.ROOT);
        String summary = asString(primaryFailureAnalysis.get("fixRecommendation"));
        String suggestedDiff = null;
        List<String> steps = new ArrayList<>();
        List<String> targetFiles = new ArrayList<>();
        List<String> detailLines = mergeStringList(primaryFailureAnalysis.get("details"));
        List<String> missingSymbols = mergeStringList(primaryFailureAnalysis.get("missingSymbols"));
        String locationPackage = null;

        if (file != null) {
            targetFiles.add(file);
        }

        targetFiles.addAll(limitList(collectEntryPaths(safeList(commitAnalysis.get("likelyRelatedFiles"))), 3));
        List<String> dependencyCandidates = extractDependencyCandidates(primaryFailureAnalysis);

        for (String detail : detailLines) {
            String lowerDetail = lower(detail);

            if (lowerDetail.startsWith("location package:")) {
                locationPackage = detail.substring(detail.indexOf(':') + 1).trim();
                break;
            }
        }

        if ("Pipeline Success".equals(category)) {
            summary = "No fix is required because the pipeline passed successfully.";
            steps.add("Keep this run as a healthy comparison baseline.");
        } else if ("Code Compilation Failure".equals(category)
                && containsAny(lowerError, "cannot find symbol", "package does not exist")) {
            String packageName = firstNonBlank(locationPackage, "com.cyhub.backend.dto.admin");
            String joinedSymbols = joinReadable(limitList(missingSymbols, 3));

            summary = "Missing DTO classes: " + firstNonBlank(joinedSymbols, "AdminConversationSummary, AdminReplyRequest") + ".";
            steps.add("If the classes do not exist, create them in package " + packageName + ".");
            steps.add("If the classes already exist, fix the import statements in " + firstNonBlank(file, "the controller or service") + ".");
            steps.add("If they were renamed or moved, update the package path in the controller/service references.");
            suggestedDiff = "Create the missing DTO classes in " + packageName + " or update the imports in the controller/service.";
        } else if ("Test Failure".equals(category)
                && (containsAny(lowerError, "duplicate entry", "sqlstate", "unique constraint", "error 1062", "duplicate key value")
                || containsAny(lower(asString(primaryFailureAnalysis.get("errorTypeDisplay"))), "database integrity"))) {
            summary = "Database constraint violation (Duplicate Entry).";
            steps.add("Ensure the test creates unique data, such as UUIDs or randomized IDs.");
            steps.add("Clean or rollback the database state between tests so prior rows do not collide.");
            steps.add("Verify the entity unique constraints and fixture data agree on allowed values.");
            steps.add("Avoid reusing the same input record or primary key in repeated test runs.");
            suggestedDiff = "Update the failing test or data generator so each insert uses unique identifiers and reset the database state between runs.";
        } else if (containsAny(lowerError, "must be unique", "duplicate dependency", "duplicate declaration")) {
            List<String> dependenciesToFix = dependencyCandidates.isEmpty()
                    ? List.of("the duplicated dependency declarations")
                    : dependencyCandidates;
            summary = "Remove duplicate dependency entries in "
                    + firstNonBlank(file, "the build file")
                    + " for "
                    + joinReadable(dependenciesToFix)
                    + ". Keep only one declaration per dependency.";
            steps.add("Open " + firstNonBlank(file, "the build file") + " and remove the extra dependency blocks.");
            steps.add("Keep exactly one versioned declaration for " + joinReadable(dependenciesToFix) + ".");
            steps.add("Rerun the failing Maven or Gradle job and confirm the dependency tree is clean.");
            suggestedDiff = "Delete the duplicate dependency entries for "
                    + joinReadable(dependenciesToFix)
                    + " and keep a single canonical declaration.";
        } else if ("Pipeline Configuration Failure".equals(category)) {
            summary = "Correct the CI configuration in " + firstNonBlank(file, ".gitlab-ci.yml") + " so stages, jobs, and YAML syntax are valid.";
            steps.add("Validate the YAML structure and confirm every job references an existing stage.");
            steps.add("Remove or fix invalid keys and rerun the pipeline validation stage first.");
            suggestedDiff = "Adjust the broken stage or job definition in the CI YAML before retrying the full pipeline.";
        } else if ("Test Failure".equals(category)
                && Boolean.TRUE.equals(safeMap(primaryFailureAnalysis.get("verificationEntities")).get("rvstestReferenced"))) {
            summary = "Align the failing test expectation with the verification asset and confirm rvstest and Python expectations match.";
            steps.add("Compare rvstest expectations with the Python test setup for the failing behavior.");
            steps.add("Fix any mismatched dd_ mapping, stub setup, or expected value before rerunning the suite.");
            suggestedDiff = "Update the test or verification asset so both rvstest and Python assert the same contract.";
        } else if ("Code Compilation Failure".equals(category)) {
            summary = "Repair the compiler-visible source defect in "
                    + firstNonBlank(file, "the changed source file")
                    + (line == null ? "" : " around line " + line)
                    + ".";
            steps.add("Open the failing source location and fix the missing type, symbol, include, or syntax issue.");
            steps.add("Rebuild locally or in CI with the same compiler flags before merging.");
        } else if ("Infrastructure Failure".equals(category) || "External System Failure".equals(category)) {
            summary = "Restore the failing platform or dependency service before retrying the pipeline.";
            steps.add("Confirm runner, network, artifact, or API health before rerunning.");
            steps.add("Avoid blaming the recent code commit until operational stability is restored.");
        }

        guidance.put("summary", summary);
        guidance.put("targetFiles", new ArrayList<>(new LinkedHashSet<>(targetFiles)));
        guidance.put("exactSteps", steps);
        guidance.put("suggestedDiff", suggestedDiff);
        String autoFixPatch = generateAutoFixPatch(
                projectId,
                pipelineRef,
                pipelineCommit,
                primaryFailureAnalysis,
                commitAnalysis,
                dependencyCandidates
        );
        guidance.put("patchAvailable", autoFixPatch != null);
        guidance.put("autoFixPatch", autoFixPatch);
        return guidance;
    }

    private Map<String, Object> buildTriageUrgency(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> pipelineIntelligence
    ) {

        Map<String, Object> urgency = new LinkedHashMap<>();
        String category = asString(primaryFailureAnalysis.get("category"));
        String severity = asString(primaryFailureAnalysis.get("severity"));
        int blastRadius = toInt(pipelineIntelligence.get("failureBlastRadius"));
        String level;
        String action;
        String reason;

        if ("CRITICAL".equalsIgnoreCase(severity)
                || blastRadius > 0
                || "Pipeline Configuration Failure".equals(category)
                || "Build Configuration Failure".equals(category)) {
            level = "RED";
            action = "Fix immediately (blocking release)";
            reason = "The failure blocks downstream execution or release flow and should be corrected before further merges.";
        } else if ("HIGH".equalsIgnoreCase(severity)
                || "Test Failure".equals(category)
                || Boolean.TRUE.equals(primaryFailureAnalysis.get("requiresHumanReview"))) {
            level = "YELLOW";
            action = "Fix before next merge";
            reason = "The issue is not safely ignorable and should be resolved before more changes accumulate.";
        } else {
            level = "GREEN";
            action = "Monitor";
            reason = "The current evidence suggests observation is reasonable while collecting more data.";
        }

        urgency.put("level", level);
        urgency.put("action", action);
        urgency.put("reason", reason);
        return urgency;
    }

    private String buildRecurrenceSummary(
            List<Map<String, Object>> similarFailures,
            int sameSignatureCount,
            int sameFingerprintCount
    ) {

        if (!similarFailures.isEmpty()) {
            Map<String, Object> mostRecentMatch = similarFailures.get(0);
            String pipelineId = asString(mostRecentMatch.get("pipelineId"));

            if (sameSignatureCount > 0) {
                return "Similar failure occurred in pipeline "
                        + pipelineId
                        + " with the same signature, so this is a known recurring pattern.";
            }

            if (sameFingerprintCount > 0) {
                return "A closely matching failure fingerprint appeared in pipeline "
                        + pipelineId
                        + ", which suggests this problem has happened before.";
            }
        }

        return "This pattern has not appeared in the recent history window.";
    }

    private Map<String, Object> findPrimaryFailureAnalysis(
            List<Map<String, Object>> jobAnalyses,
            Map<String, Object> primaryFailure
    ) {

        if (primaryFailure == null || primaryFailure.isEmpty()) {
            return Collections.emptyMap();
        }

        String primaryJobId = asString(primaryFailure.get("jobId"));

        for (Map<String, Object> jobAnalysis : jobAnalyses) {
            if (primaryJobId.equals(asString(jobAnalysis.get("jobId")))) {
                return safeMap(jobAnalysis.get("failureAnalysis"));
            }
        }

        return Collections.emptyMap();
    }

    private Map<String, Object> buildEvidence(Object rawEvidence, String logs) {
        Map<String, Object> evidence = new LinkedHashMap<>();

        if (rawEvidence instanceof Map<?, ?> rawMap) {
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                evidence.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }

        if (!evidence.containsKey("snippets")) {
            evidence.put("snippets", extractLogHighlights(logs));
        }

        return evidence;
    }

    private Map<String, Object> fallbackAnalysis(String logs) {
        String lower = logs == null ? "" : logs.toLowerCase(Locale.ROOT);
        Map<String, Object> analysis = new LinkedHashMap<>();

        if (containsAny(lower, "compilation error", "cannot find symbol", "package does not exist", "undefined reference", "symbol:")) {
            analysis.put("category", "Code Compilation Failure");
            analysis.put("failureType", "Code Compilation Failure");
            analysis.put("tool", detectTool(lower));
            analysis.put("confidence", "HIGH");
            analysis.put("rootCause", "A compiler-visible source or generated-code error stopped the build.");
            analysis.put("fixRecommendation", "Open the first compiler error, fix the missing class/import/signature, and rerun the build.");
        } else if (containsAny(lower, "sqlstate", "duplicate entry", "unique constraint", "error 1062", "duplicate key value violates unique constraint")) {
            analysis.put("category", "Test Failure");
            analysis.put("failureType", "Test Failure");
            analysis.put("tool", detectTool(lower));
            analysis.put("confidence", "HIGH");
            analysis.put("rootCause", "Database constraint violation (Duplicate Entry)");
            analysis.put("fixRecommendation", "Use unique test data, reset the database between tests, and verify the unique constraints.");
        } else if (containsAny(lower, "jobs config should contain", "chosen stage does not exist", ".gitlab-ci.yml", "mapping values are not allowed")) {
            analysis.put("category", "Pipeline Configuration Failure");
            analysis.put("failureType", "Pipeline Configuration Failure");
            analysis.put("tool", "GitLab CI");
            analysis.put("confidence", "HIGH");
            analysis.put("rootCause", "Pipeline configuration is invalid or references an unavailable stage or job definition.");
            analysis.put("fixRecommendation", "Review the recently changed CI configuration and validate stage names, script blocks, and job visibility.");
        } else if (containsAny(lower, "must be unique", "duplicate declaration", "duplicate dependency", "pom.xml", "build.gradle", "cmakelists.txt", "makefile")) {
            analysis.put("category", "Build Configuration Failure");
            analysis.put("failureType", "Build Configuration Failure");
            analysis.put("tool", detectTool(lower));
            analysis.put("confidence", "HIGH");
            analysis.put("rootCause", "Build configuration changed in a way that introduced dependency, script, or configuration conflicts.");
            analysis.put("fixRecommendation", "Compare the failing build file against the recent diff and revert or correct the conflicting configuration.");
        } else if (containsAny(lower, "there are test failures", "test failed", "assertionerror", "failed tests", "junit", "pytest")) {
            analysis.put("category", "Test Failure");
            analysis.put("failureType", "Test Failure");
            analysis.put("tool", detectTool(lower));
            analysis.put("confidence", "MEDIUM");
            analysis.put("rootCause", "Automated tests failed after the recent change set.");
            analysis.put("fixRecommendation", "Start with the first failing test, reproduce locally, and validate any changed production or test setup files.");
        } else if (containsAny(lower, "runner system failure", "no space left", "executor failed", "killed", "oom", "docker pull")) {
            analysis.put("category", "Infrastructure Failure");
            analysis.put("failureType", "Infrastructure Failure");
            analysis.put("tool", "CI Runner");
            analysis.put("confidence", "MEDIUM");
            analysis.put("rootCause", "Runner or execution infrastructure failed while the pipeline was running.");
            analysis.put("fixRecommendation", "Check runner health, resource pressure, container image availability, and retry only after infrastructure is stable.");
        } else if (containsAny(lower, "connection refused", "timed out", "could not resolve host", "service unavailable", "artifact", "nexus", "jfrog")) {
            analysis.put("category", "External System Failure");
            analysis.put("failureType", "External System Failure");
            analysis.put("tool", detectTool(lower));
            analysis.put("confidence", "MEDIUM");
            analysis.put("rootCause", "A required external dependency or service was unavailable during execution.");
            analysis.put("fixRecommendation", "Verify the external service status, credentials, and network connectivity before retrying the job.");
        } else if (containsAny(lower, "permission denied", "java home", "environment variable", "command not found")) {
            analysis.put("category", "Environment Failure");
            analysis.put("failureType", "Environment Failure");
            analysis.put("tool", detectTool(lower));
            analysis.put("confidence", "MEDIUM");
            analysis.put("rootCause", "The runtime environment is missing expected tools, permissions, or environment variables.");
            analysis.put("fixRecommendation", "Validate the runner image, environment variables, permissions, and required tooling versions.");
        } else {
            analysis.put("category", "Code Compilation Failure");
            analysis.put("failureType", "Code Compilation Failure");
            analysis.put("tool", detectTool(lower));
            analysis.put("confidence", "LOW");
            analysis.put("rootCause", "The failure most closely resembles a source or compilation issue, but more context is required.");
            analysis.put("fixRecommendation", "Inspect the first compiler-style error line and confirm whether the changed code or tests introduced the failure.");
        }

        analysis.put("errorMessage", firstNonBlank(firstSnippet(logs), "No error signature extracted"));
        return analysis;
    }

    private List<String> extractLogHighlights(String logs) {
        if (logs == null || logs.isBlank()) {
            return Collections.emptyList();
        }

        List<String> highlights = new ArrayList<>();

        for (String line : logs.split("\\R")) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            String lower = trimmed.toLowerCase(Locale.ROOT);

            if (containsAny(lower,
                    "error",
                    "fail",
                    "exception",
                    "timed out",
                    "connection refused",
                    "permission denied",
                    "not found",
                    "must be unique",
                    "runner system failure")
            ) {
                highlights.add(trimmed);
            }

            if (highlights.size() >= 6) {
                break;
            }
        }

        return highlights;
    }

    private List<String> inferSignals(String logs, String category) {
        List<String> signals = new ArrayList<>();
        List<String> highlights = extractLogHighlights(logs);

        if (!highlights.isEmpty()) {
            signals.add("high-signal error lines extracted");
        }

        if (category != null && category.contains("Configuration")) {
            signals.add("configuration artifact correlation required");
        }

        if (category != null && category.contains("Infrastructure")) {
            signals.add("runner health verification required");
        }

        if (category != null && category.contains("External")) {
            signals.add("service dependency verification required");
        }

        return signals;
    }

    private List<String> mergeSignals(List<String> derivedSignals, Object rawSignals) {
        Set<String> signals = new LinkedHashSet<>(derivedSignals);

        if (rawSignals instanceof List<?> list) {
            for (Object item : list) {
                String value = asString(item);

                if (value != null) {
                    signals.add(value);
                }
            }
        }

        return new ArrayList<>(signals);
    }

    private boolean supportsAutomatedRetry(Map<String, Object> analysis) {
        String category = asString(analysis.get("category"));
        return containsAny(
                category == null ? "" : category.toLowerCase(Locale.ROOT),
                "external",
                "infrastructure",
                "environment"
        );
    }

    private List<String> buildResolutionPlaybook(Map<String, Object> analysis, Map<String, Object> job) {
        List<String> playbook = new ArrayList<>();
        String category = asString(analysis.get("category"));
        String file = asString(analysis.get("file"));
        String line = asString(analysis.get("line"));

        playbook.add(firstNonBlank(asString(analysis.get("fixRecommendation")), "Start from the earliest high-signal failure line."));

        if (file != null) {
            playbook.add("Review the most recent diff touching " + file + (line == null ? "" : ":" + line) + ".");
        }

        playbook.add("Validate the failure in job '" + asString(job.get("name")) + "' before retrying downstream stages.");

        if (category != null && category.contains("Configuration")) {
            playbook.add("Confirm the changed build or pipeline configuration against the expected team baseline.");
        } else if (category != null && category.contains("External")) {
            playbook.add("Verify dependent service availability and credentials before rerunning the pipeline.");
        } else {
            playbook.add("Rerun the pipeline only after the corrective change has been committed and reviewed.");
        }

        return playbook;
    }

    private List<String> buildCommitHighlights(
            List<Map<String, Object>> changedConfigurationFiles,
            List<Map<String, Object>> likelyRelatedFiles,
            List<Map<String, Object>> jobAnalyses
    ) {

        List<String> highlights = new ArrayList<>();

        if (!changedConfigurationFiles.isEmpty()) {
            highlights.add("Recent configuration changes are present and should be reviewed before retrying.");
        }

        if (!likelyRelatedFiles.isEmpty()) {
            highlights.add("The current commit modifies files that are likely related to the primary failure signature.");
        }

        if (jobAnalyses.size() > 1) {
            highlights.add("Multiple failed jobs were detected; prioritize the earliest primary failure first.");
        }

        if (highlights.isEmpty()) {
            highlights.add("No obvious configuration correlation was detected in the current diff.");
        }

        return highlights;
    }

    private List<String> buildTriageChecklist(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis
    ) {

        List<String> checklist = new ArrayList<>();
        checklist.add("Confirm the earliest failed job and review its highest-confidence log evidence.");
        checklist.add("Inspect the current commit diff, prioritizing configuration files and the failure location.");
        checklist.add("Apply the recommended fix, rerun only the affected pipeline, and verify downstream recovery.");

        if (!safeList(commitAnalysis.get("changedConfigurationFiles")).isEmpty()) {
            checklist.add("Include build or pipeline configuration reviewers before promoting the fix.");
        }

        if (primaryFailureAnalysis != null && Boolean.TRUE.equals(primaryFailureAnalysis.get("supportsAutomatedRetry"))) {
            checklist.add("If the issue was environmental or external, verify system health before retrying.");
        }

        return checklist;
    }

    private List<String> buildEnterpriseRecommendations(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> pipelineSummary,
            Map<String, Object> commitAnalysis
    ) {

        List<String> recommendations = new ArrayList<>();
        recommendations.add("Use the generated report to align feature, build, and platform teams on a single root-cause narrative.");
        recommendations.add("Review recently changed configuration artifacts before retrying, even for code-origin failures.");

        if (toInt(pipelineSummary.get("failedJobs")) > 1) {
            recommendations.add("Treat secondary failures as likely symptoms until the primary failure is resolved.");
        }

        if (!safeList(commitAnalysis.get("changedConfigurationFiles")).isEmpty()) {
            recommendations.add("Add config-aware review gates for build and pipeline file changes to reduce future breakages.");
        }

        if (primaryFailureAnalysis != null && supportsAutomatedRetry(primaryFailureAnalysis)) {
            recommendations.add("Track runner and external dependency reliability to decide when safe automated retries can be introduced.");
        }

        return recommendations;
    }

    private List<String> collectRecommendedOwners(
            List<Map<String, Object>> failedJobs,
            Map<String, Object> primaryFailureAnalysis
    ) {

        Set<String> owners = new LinkedHashSet<>();

        if (!failedJobs.isEmpty()) {
            owners.add("Pipeline owner");
        }

        if (primaryFailureAnalysis != null) {
            owners.add(inferRecommendedOwner(primaryFailureAnalysis));
        }

        return new ArrayList<>(owners);
    }

    private String inferRecommendedOwner(Map<String, Object> analysis) {
        String category = asString(analysis.get("category"));

        if (category == null) {
            return "Engineering team";
        }

        return switch (category) {
            case "Build Configuration Failure" -> "Build engineering";
            case "Infrastructure Failure", "Environment Failure", "Pipeline Configuration Failure" -> "Platform engineering";
            case "External System Failure" -> "Platform engineering";
            case "Test Failure" -> "Feature team and QA";
            default -> "Feature development team";
        };
    }

    private String inferSeverity(Map<String, Object> analysis, Map<String, Object> job) {
        String category = asString(analysis.get("category"));

        if (Boolean.TRUE.equals(job.get("allow_failure"))) {
            return "MEDIUM";
        }

        if (category == null) {
            return "HIGH";
        }

        return switch (category) {
            case "Pipeline Configuration Failure", "Infrastructure Failure" -> "CRITICAL";
            case "Build Configuration Failure", "External System Failure" -> "HIGH";
            default -> "HIGH";
        };
    }

    private String inferRiskLevel(String category, int downstreamImpact, int failedCount) {
        if (failedCount == 0) {
            return "LOW";
        }

        if (downstreamImpact >= 5 || "Pipeline Configuration Failure".equals(category) || "Infrastructure Failure".equals(category)) {
            return "CRITICAL";
        }

        if (failedCount > 1 || "Build Configuration Failure".equals(category) || "External System Failure".equals(category)) {
            return "HIGH";
        }

        return "MEDIUM";
    }

    private Map<String, Object> buildDiffCorrelationPlaceholder() {
        Map<String, Object> placeholder = new LinkedHashMap<>();
        placeholder.put("rootCauseCommitMatch", false);
        placeholder.put("rootCausePrecision", "Commit diff unavailable");
        placeholder.put("confidenceLevel", "LOW");
        placeholder.put("modifiedFiles", Collections.emptyList());
        return placeholder;
    }

    private String categorizeFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "Unknown";
        }

        String lower = filePath.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".gitlab-ci.yml") || lower.contains(".github/workflows")) {
            return "Pipeline Configuration";
        }

        if (containsAny(lower,
                "pom.xml",
                "build.gradle",
                "build.gradle.kts",
                "settings.gradle",
                "cmakelists.txt",
                "makefile",
                "package.json",
                "pyproject.toml",
                "requirements.txt",
                "dockerfile",
                "docker-compose")
        ) {
            return "Build Configuration";
        }

        if (lower.contains("/test/") || lower.contains("/tests/") || lower.contains("test_") || lower.contains(".spec.") || lower.contains(".test.")) {
            return "Test Code";
        }

        if (containsAny(lower, ".java", ".kt", ".groovy", ".py", ".ts", ".js", ".go", ".rs", ".cpp", ".c", ".hpp", ".h")) {
            return "Source Code";
        }

        if (containsAny(lower, ".tf", "helm/", "k8s/", "ops/", "scripts/")) {
            return "Operations";
        }

        return "Unknown";
    }

    private String inferChangeType(String diffText) {
        if (diffText == null || diffText.isBlank()) {
            return "Unknown";
        }

        if (diffText.contains("\n+")) {
            return diffText.contains("\n-") ? "Modified" : "Added";
        }

        if (diffText.contains("\n-")) {
            return "Removed";
        }

        return "Modified";
    }

    private String categoryDescription(String category) {
        if (category == null) {
            return "Failure category could not be determined with confidence.";
        }

        return switch (category) {
            case "Pipeline Configuration Failure" -> "The CI orchestration definition likely broke job wiring, stage ordering, or YAML validity.";
            case "Build Configuration Failure" -> "A build or dependency artifact was modified in a way that broke compilation or packaging.";
            case "Test Failure" -> "Application changes likely regressed a validated behavior or test environment expectation.";
            case "Infrastructure Failure" -> "Runner or execution infrastructure prevented reliable job execution.";
            case "External System Failure" -> "A dependent external service was unavailable or unreachable during execution.";
            case "Environment Failure" -> "The runtime environment was missing a required tool, permission, or configuration input.";
            default -> "The failure most closely resembles a code-level issue and should be investigated at the source change level.";
        };
    }

    private String resolutionType(String category) {
        if (category == null) {
            return "Manual investigation";
        }

        return switch (category) {
            case "Pipeline Configuration Failure", "Build Configuration Failure" -> "Configuration correction";
            case "Infrastructure Failure", "Environment Failure", "External System Failure" -> "Operational remediation";
            case "Test Failure" -> "Code or test correction";
            default -> "Code correction";
        };
    }

    private double confidenceScore(String confidence) {
        if (confidence == null) {
            return 0.35;
        }

        return switch (confidence.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 0.98;
            case "VERY HIGH" -> 0.92;
            case "HIGH" -> 0.84;
            case "MEDIUM" -> 0.63;
            default -> 0.38;
        };
    }

    private String detectTool(String lowerLog) {
        if (containsAny(lowerLog, "sqlstate", "duplicate entry", "unique constraint", "integrity constraint", "sql error", "error 1062", "duplicate key value")) {
            return "Database";
        }

        if (containsAny(lowerLog, "maven", "pom.xml", "mvn ")) {
            return "Maven";
        }

        if (containsAny(lowerLog, "gradle", "build.gradle")) {
            return "Gradle";
        }

        if (containsAny(lowerLog, "pytest", "python", ".py")) {
            return "Python";
        }

        if (containsAny(lowerLog, "npm", "node", "yarn")) {
            return "Node";
        }

        if (containsAny(lowerLog, ".gitlab-ci.yml", "gitlab")) {
            return "GitLab CI";
        }

        return "Unknown";
    }

    private String extractDependencyName(Map<String, Object> analysis, String logs) {
        String errorMessage = asString(analysis.get("errorMessage"));

        if (errorMessage != null) {
            Matcher matcher = DEPENDENCY_NAME_PATTERN.matcher(errorMessage);

            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        if (logs != null) {
            Matcher matcher = DEPENDENCY_NAME_PATTERN.matcher(logs);

            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        return null;
    }

    private String classifyIssueClass(String category) {
        if (category == null) {
            return "UNKNOWN";
        }

        return switch (category) {
            case "Pipeline Success" -> "HEALTHY";
            case "Code Compilation Failure" -> "CODE";
            case "Build Configuration Failure", "Pipeline Configuration Failure" -> "BUILD_OR_CONFIGURATION";
            case "Test Failure" -> "TEST_OR_VERIFICATION";
            case "Infrastructure Failure" -> "INFRASTRUCTURE";
            case "External System Failure" -> "EXTERNAL_SYSTEM";
            case "Environment Failure" -> "ENVIRONMENT";
            default -> "UNKNOWN";
        };
    }

    private String likelyOwner(String issueClass, String commitAuthor, Map<String, Object> primaryFailureAnalysis) {
        if ("HEALTHY".equals(issueClass)) {
            return "No immediate owner action required";
        }

        if ("INFRASTRUCTURE".equals(issueClass) || "ENVIRONMENT".equals(issueClass)) {
            return "Platform engineering";
        }

        if ("EXTERNAL_SYSTEM".equals(issueClass)) {
            return "External dependency or platform owner";
        }

        if ("TEST_OR_VERIFICATION".equals(issueClass)) {
            return firstNonBlank(commitAuthor, asString(primaryFailureAnalysis.get("recommendedOwner")), "Feature team and QA");
        }

        return firstNonBlank(commitAuthor, asString(primaryFailureAnalysis.get("recommendedOwner")), "Engineering team");
    }

    private String decisionReasoning(String issueClass, String category) {
        if (issueClass == null) {
            return "No issue-class reasoning available.";
        }

        return switch (issueClass) {
            case "HEALTHY" -> "The pipeline completed successfully and no blocking failure class applies to this run.";
            case "CODE" -> "The strongest signals point to source-level compiler or runtime defects.";
            case "BUILD_OR_CONFIGURATION" -> "The strongest signals point to build, CI, toolchain, or configuration changes.";
            case "TEST_OR_VERIFICATION" -> "The strongest signals point to failing verification logic, expectations, coverage, or test setup.";
            case "INFRASTRUCTURE" -> "The strongest signals point to runner, execution, or resource instability rather than developer logic.";
            case "EXTERNAL_SYSTEM" -> "The strongest signals point to dependencies outside the immediate codebase.";
            case "ENVIRONMENT" -> "The strongest signals point to missing runtime configuration, credentials, or permissions.";
            default -> "The analyzer could not place this failure cleanly into a single root issue class.";
        };
    }

    private List<String> buildVerificationFocusAreas(
            List<String> ddVariables,
            boolean rvstestReferenced,
            boolean stubReferenced,
            boolean coverageReferenced
    ) {

        List<String> areas = new ArrayList<>();

        if (!ddVariables.isEmpty()) {
            areas.add("data dictionary variable mapping review");
        }

        if (rvstestReferenced) {
            areas.add("rvstest versus Python expectation review");
        }

        if (stubReferenced) {
            areas.add("stub or mock contract review");
        }

        if (coverageReferenced) {
            areas.add("coverage threshold and uncovered logic review");
        }

        if (areas.isEmpty()) {
            areas.add("No specialized verification mismatch signal was detected from the available logs.");
        }

        return areas;
    }

    private Map<String, Object> findRecentPipelineSnapshot(List<Object> snapshots, String currentPipelineId, String status) {
        for (Object snapshot : snapshots) {
            Map<String, Object> pipeline = safeMap(snapshot);
            String snapshotPipelineId = asString(pipeline.get("pipelineId"));

            if (snapshotPipelineId == null || snapshotPipelineId.equals(currentPipelineId)) {
                continue;
            }

            if (status.equalsIgnoreCase(asString(pipeline.get("status")))) {
                return pipeline;
            }
        }

        return Collections.emptyMap();
    }

    private Map<String, Object> buildPipelineComparisonSnapshot(
            String projectId,
            String pipelineId,
            String preferredJobName,
            String preferredStage,
            Map<String, Object> primaryFailureAnalysis
    ) {

        try {
            List<Map<String, Object>> jobs = gitLabService.getPipelineJobsAsList(projectId, pipelineId);
            Map<String, Object> comparableJob = findComparableJob(jobs, preferredJobName, preferredStage);

            if (comparableJob.isEmpty()) {
                return Collections.emptyMap();
            }

            String jobId = asString(comparableJob.get("id"));
            String logs = gitLabService.getJobLogs(projectId, jobId);
            Map<String, Object> logEvidence = compareLogsToCurrentFailure(logs, primaryFailureAnalysis);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("pipelineId", pipelineId);
            snapshot.put("jobId", jobId);
            snapshot.put("jobName", asString(comparableJob.get("name")));
            snapshot.put("jobStatus", asString(comparableJob.get("status")));
            snapshot.put("signalDetected", logEvidence.get("signalDetected"));
            snapshot.put("matchedLines", logEvidence.get("matchedLines"));
            snapshot.put("matchedOn", logEvidence.get("matchedOn"));
            snapshot.put("summary", logEvidence.get("summary"));
            return snapshot;
        } catch (Exception exception) {
            return Collections.emptyMap();
        }
    }

    private Map<String, Object> findComparableJob(
            List<Map<String, Object>> jobs,
            String preferredJobName,
            String preferredStage
    ) {

        if (jobs == null || jobs.isEmpty()) {
            return Collections.emptyMap();
        }

        if (preferredJobName != null) {
            for (Map<String, Object> job : jobs) {
                if (preferredJobName.equals(asString(job.get("name")))) {
                    return job;
                }
            }
        }

        if (preferredStage != null) {
            for (Map<String, Object> job : jobs) {
                if (preferredStage.equals(asString(job.get("stage")))) {
                    return job;
                }
            }
        }

        Map<String, Object> failed = gitLabService.findFirstFailedJob(jobs);

        if (failed != null && !failed.isEmpty()) {
            return failed;
        }

        return jobs.get(0);
    }

    private Map<String, Object> compareLogsToCurrentFailure(
            String logs,
            Map<String, Object> primaryFailureAnalysis
    ) {

        Map<String, Object> comparison = new LinkedHashMap<>();
        Map<String, Object> analyzed = pythonAnalysisService.analyzeLogs(logs);
        String currentSignature = asString(primaryFailureAnalysis.get("signatureId"));
        String currentFingerprint = asString(primaryFailureAnalysis.get("failureFingerprint"));
        boolean signatureMatch = currentSignature != null && currentSignature.equals(asString(analyzed.get("signatureId")));
        boolean fingerprintMatch = currentFingerprint != null && currentFingerprint.equals(asString(analyzed.get("failureFingerprint")));
        List<String> matchedLines = extractEvidenceLines(logs, primaryFailureAnalysis);
        boolean signalDetected = signatureMatch || fingerprintMatch || !matchedLines.isEmpty();

        comparison.put("signalDetected", signalDetected);
        comparison.put("matchedLines", matchedLines);
        comparison.put("matchedOn",
                signatureMatch ? "signature" : fingerprintMatch ? "fingerprint" : matchedLines.isEmpty() ? "none" : "log evidence");
        comparison.put(
                "summary",
                signalDetected
                        ? "Comparable evidence was detected in historical logs."
                        : "No comparable evidence was found in the historical logs."
        );
        return comparison;
    }

    private List<String> extractEvidenceLines(String logs, Map<String, Object> primaryFailureAnalysis) {
        if (logs == null || logs.isBlank()) {
            return Collections.emptyList();
        }

        List<String> tokens = extractEvidenceTokens(primaryFailureAnalysis);
        List<String> matches = new ArrayList<>();

        for (String line : logs.split("\\R")) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            String lower = trimmed.toLowerCase(Locale.ROOT);

            for (String token : tokens) {
                if (lower.contains(token)) {
                    matches.add(trimmed);
                    break;
                }
            }

            if (matches.size() >= 3) {
                break;
            }
        }

        return matches;
    }

    private List<String> extractEvidenceTokens(Map<String, Object> primaryFailureAnalysis) {
        Set<String> tokens = new LinkedHashSet<>();
        String errorMessage = lower(asString(primaryFailureAnalysis.get("errorMessage")));
        String matchedPattern = lower(asString(primaryFailureAnalysis.get("matchedPattern")));
        String file = lower(asString(primaryFailureAnalysis.get("file")));

        if (containsAny(errorMessage, "must be unique", "duplicate")) {
            tokens.add("must be unique");
            tokens.add("duplicate");
        }

        if (containsAny(errorMessage, "undefined reference")) {
            tokens.add("undefined reference");
        }

        if (containsAny(errorMessage, "segmentation fault", "segfault")) {
            tokens.add("segmentation fault");
            tokens.add("segfault");
        }

        if (containsAny(errorMessage, "coverage")) {
            tokens.add("coverage");
        }

        if (matchedPattern != null && matchedPattern.length() > 3) {
            tokens.add(matchedPattern);
        }

        if (file != null) {
            tokens.add(fileName(file));
        }

        for (String dependency : extractDependencyCandidates(primaryFailureAnalysis)) {
            String lowerDependency = dependency.toLowerCase(Locale.ROOT);
            tokens.add(lowerDependency);

            if (lowerDependency.contains(":")) {
                for (String part : lowerDependency.split(":")) {
                    if (!part.isBlank()) {
                        tokens.add(part);
                    }
                }
            }
        }

        return new ArrayList<>(tokens);
    }

    private int estimateAffectedModules(Map<String, Object> commitAnalysis) {
        Set<String> modules = new LinkedHashSet<>();

        for (String path : collectEntryPaths(safeList(commitAnalysis.get("changedFiles")))) {
            String module = inferModuleName(path);

            if (module != null) {
                modules.add(module);
            }
        }

        return Math.max(1, modules.size());
    }

    private String inferModuleName(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String normalized = path.replace('\\', '/');

        if (!normalized.contains("/")) {
            return normalized;
        }

        String[] parts = normalized.split("/");

        for (String part : parts) {
            if (!part.isBlank()
                    && !List.of("src", "main", "test", "tests", "java", "resources", "backend", "frontend").contains(part)) {
                return part;
            }
        }

        return parts[0];
    }

    private String inferPatternName(
            String signatureId,
            String category,
            Map<String, Object> primaryFailureAnalysis
    ) {

        String lowerSignature = lower(signatureId);
        String lowerError = lower(asString(primaryFailureAnalysis.get("errorMessage")));

        if (containsAny(lowerSignature, "duplicate", "dependency")
                || containsAny(lowerError, "must be unique", "duplicate dependency")) {
            return "Recurring Maven dependency misconfiguration";
        }

        if (containsAny(lowerSignature, "coverage")) {
            return "Coverage threshold regression";
        }

        if (containsAny(lowerSignature, "rvstest", "data-dictionary", "stub")) {
            return "Verification asset mismatch";
        }

        return firstNonBlank(category, "Recurring pipeline failure pattern");
    }

    private List<String> inferPatternBehaviors(
            String signatureId,
            String category,
            Map<String, Object> primaryFailureAnalysis
    ) {

        List<String> behaviors = new ArrayList<>();
        String lowerSignature = lower(signatureId);
        String lowerError = lower(asString(primaryFailureAnalysis.get("errorMessage")));

        if (containsAny(lowerSignature, "duplicate", "dependency")
                || containsAny(lowerError, "must be unique", "duplicate dependency")) {
            behaviors.add("manual dependency edits without cleanup");
            behaviors.add("missing dependency management centralization");
            behaviors.add("dependency review happening after breakage instead of before merge");
        } else if (containsAny(lowerSignature, "rvstest", "data-dictionary", "stub")) {
            behaviors.add("verification assets drifting from implementation");
            behaviors.add("test and requirement expectations not being validated together");
        } else if ("Pipeline Configuration Failure".equals(category)) {
            behaviors.add("pipeline logic changes merged without preflight validation");
            behaviors.add("stage or dependency wiring drifting from the team baseline");
        } else {
            behaviors.add("the current failure pattern needs more history to infer a stable team behavior");
        }

        return behaviors;
    }

    private Map<String, Object> confidenceFactor(String label, int delta, String reason) {
        Map<String, Object> factor = new LinkedHashMap<>();
        factor.put("label", label);
        factor.put("delta", delta);
        factor.put("reason", reason);
        return factor;
    }

    private Map<String, Object> healthFactor(String label, int delta, String reason) {
        Map<String, Object> factor = new LinkedHashMap<>();
        factor.put("label", label);
        factor.put("delta", delta);
        factor.put("reason", reason);
        return factor;
    }

    private Map<String, Object> prioritizedFix(int priority, String title, String action, String rationale) {
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("priority", priority);
        fix.put("title", title);
        fix.put("action", action);
        fix.put("rationale", rationale);
        return fix;
    }

    private Map<String, Object> graphNode(String id, String type, String label, String detail) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("label", label);
        node.put("detail", detail);
        return node;
    }

    private String buildGraphSummary(List<Map<String, Object>> nodes) {
        List<String> labels = new ArrayList<>();

        for (Map<String, Object> node : nodes) {
            String label = asString(node.get("label"));

            if (label != null) {
                labels.add(label);
            }
        }

        return String.join(" -> ", labels);
    }

    private String resolveTargetFilePath(
            Map<String, Object> primaryFailureAnalysis,
            Map<String, Object> commitAnalysis
    ) {

        String failureFile = asString(primaryFailureAnalysis.get("file"));

        if (failureFile != null && failureFile.contains("/")) {
            return failureFile;
        }

        for (String path : collectEntryPaths(safeList(commitAnalysis.get("changedConfigurationFiles")))) {
            if (failureFile != null && path.endsWith(failureFile)) {
                return path;
            }
        }

        if (failureFile != null) {
            return failureFile;
        }

        List<String> changedConfigFiles = collectEntryPaths(safeList(commitAnalysis.get("changedConfigurationFiles")));
        return changedConfigFiles.isEmpty() ? null : changedConfigFiles.get(0);
    }

    private String fetchFileContent(String projectId, String targetFile, String pipelineCommit, String pipelineRef) {
        String content = null;

        if (pipelineCommit != null) {
            content = gitLabService.getFileContentAtCommit(projectId, targetFile, pipelineCommit);
        }

        if ((content == null || content.isBlank()) && pipelineRef != null) {
            content = gitLabService.getFileContentAtRef(projectId, targetFile, pipelineRef);
        }

        return content;
    }

    private Map<String, Object> findDuplicateDependencyBlock(String content, List<String> dependencyCandidates) {
        Pattern dependencyPattern = Pattern.compile(
                "<dependency>\\s*.*?<groupId>\\s*([^<]+)\\s*</groupId>\\s*.*?<artifactId>\\s*([^<]+)\\s*</artifactId>\\s*.*?</dependency>",
                Pattern.DOTALL
        );
        Matcher matcher = dependencyPattern.matcher(content);
        Map<String, String> firstBlockByDependency = new LinkedHashMap<>();

        while (matcher.find()) {
            String dependencyKey = matcher.group(1).trim() + ":" + matcher.group(2).trim();
            String block = matcher.group();

            if (!dependencyCandidates.isEmpty() && !dependencyCandidates.contains(dependencyKey)) {
                continue;
            }

            if (firstBlockByDependency.containsKey(dependencyKey)) {
                Map<String, Object> duplicate = new LinkedHashMap<>();
                duplicate.put("dependency", dependencyKey);
                duplicate.put("block", block);
                return duplicate;
            }

            firstBlockByDependency.put(dependencyKey, block);
        }

        return Collections.emptyMap();
    }

    private Map<String, Object> timelineStep(int step, String type, String title, String detail) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", step);
        entry.put("type", type);
        entry.put("title", title);
        entry.put("detail", detail);
        return entry;
    }

    private boolean hasMatchedPrimaryFailureFile(List<Map<String, Object>> entries) {
        for (Map<String, Object> entry : entries) {
            if (Boolean.TRUE.equals(entry.get("matchedPrimaryFailureFile"))) {
                return true;
            }
        }

        return false;
    }

    private String buildSmartFileCorrelation(
            String failureFile,
            List<Map<String, Object>> changedConfigurationFiles,
            List<Map<String, Object>> changedTestFiles,
            List<Map<String, Object>> changedSourceFiles,
            boolean preExistingIssueSuspected
    ) {

        String changedConfigSummary = joinReadable(limitList(collectEntryPaths(changedConfigurationFiles), 2));
        String changedTestSummary = joinReadable(limitList(collectEntryPaths(changedTestFiles), 2));
        String changedSourceSummary = joinReadable(limitList(collectEntryPaths(changedSourceFiles), 2));

        if (failureFile != null && changedConfigSummary != null) {
            return "The failing file is "
                    + failureFile
                    + ", and the commit also changed "
                    + changedConfigSummary
                    + ", which creates a strong configuration correlation.";
        }

        if (failureFile != null && changedTestSummary != null) {
            return "The failing file is "
                    + failureFile
                    + ", but the commit changed "
                    + changedTestSummary
                    + ". This suggests a latent configuration issue surfaced during new test execution or dependency resolution.";
        }

        if (failureFile != null && changedSourceSummary != null) {
            return "There is no direct file overlap with "
                    + failureFile
                    + ". However, the new source changes in "
                    + changedSourceSummary
                    + " may have exercised a code path that exposed the failure.";
        }

        if (preExistingIssueSuspected) {
            return "No direct file overlap was found. The current commit likely exposed an older defect rather than introducing it outright.";
        }

        return "No direct file overlap was found between the changed files and the extracted failure location.";
    }

    private boolean isBuildLikeFile(String filePath) {
        String category = categorizeFile(filePath);
        return category.contains("Configuration");
    }

    private boolean isRootErrorCandidate(String line) {
        if (line == null) {
            return false;
        }

        String lower = line.toLowerCase(Locale.ROOT);
        return containsAny(
                lower,
                "error",
                "exception",
                "failed",
                "failure",
                "fatal",
                "undefined reference",
                "segmentation fault",
                "nullpointer",
                "must be unique",
                "permission denied",
                "connection refused",
                "timed out"
        );
    }

    private boolean isNoiseSignal(String line) {
        if (line == null) {
            return false;
        }

        String lower = line.toLowerCase(Locale.ROOT);

        if (containsAny(lower, "warning", "deprecated", "deprecation", "notice", "spring security")) {
            return !isRootErrorCandidate(line);
        }

        return containsAny(lower, "downloading", "downloaded", "info", "started", "preparing environment");
    }

    private String readableIssueClass(String issueClass) {
        if (issueClass == null) {
            return "unknown";
        }

        return switch (issueClass) {
            case "HEALTHY" -> "healthy";
            case "BUILD_OR_CONFIGURATION" -> "build or configuration";
            case "TEST_OR_VERIFICATION" -> "test or verification";
            case "EXTERNAL_SYSTEM" -> "external system";
            default -> issueClass.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private String trimTrailingPeriod(String value) {
        if (value == null) {
            return null;
        }

        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    private String fileName(String path) {
        if (path == null) {
            return null;
        }

        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private List<String> collectEntryPaths(List<?> entries) {
        List<String> paths = new ArrayList<>();

        for (Object entry : entries) {
            String path = asString(safeMap(entry).get("path"));

            if (path != null) {
                paths.add(path);
            }
        }

        return paths;
    }

    private List<String> limitList(List<String> items, int limit) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        int upperBound = Math.min(limit, items.size());
        return new ArrayList<>(items.subList(0, upperBound));
    }

    private List<String> extractDependencyCandidates(Map<String, Object> primaryFailureAnalysis) {
        Set<String> dependencies = new LinkedHashSet<>();
        List<String> texts = new ArrayList<>();
        String errorMessage = asString(primaryFailureAnalysis.get("errorMessage"));

        if (errorMessage != null) {
            texts.add(errorMessage);
        }

        texts.addAll(mergeStringList(primaryFailureAnalysis.get("logHighlights")));

        for (String text : texts) {
            Matcher matcher = DEPENDENCY_NAME_PATTERN.matcher(text);

            while (matcher.find()) {
                dependencies.add(matcher.group(1));
            }
        }

        return new ArrayList<>(dependencies);
    }

    private List<Map<String, Object>> castMapList(Object rawValues) {
        List<Map<String, Object>> values = new ArrayList<>();

        if (rawValues instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = safeMap(item);

                if (!map.isEmpty()) {
                    values.add(map);
                }
            }
        }

        return values;
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> domain(String name, String status, String note) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("status", status);
        entry.put("note", note);
        return entry;
    }

    private String joinReadable(List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }

        return String.join(", ", items);
    }

    private String firstSnippet(String logs) {
        if (logs == null || logs.isBlank()) {
            return null;
        }

        String firstGenericError = null;

        for (String raw : logs.split("\\R")) {
            String trimmed = raw.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            String lower = trimmed.toLowerCase(Locale.ROOT);

            if (containsAny(lower, "cannot find symbol", "package does not exist", "undefined reference", "symbol:")) {
                return trimmed;
            }

            if (firstGenericError == null && containsAny(lower, "compilation error", "failed to execute goal")) {
                firstGenericError = trimmed;
                continue;
            }

            if (firstGenericError == null && lower.contains("[error]")) {
                firstGenericError = trimmed;
            }
        }

        if (firstGenericError != null) {
            return firstGenericError;
        }

        List<String> snippets = extractLogHighlights(logs);
        return snippets.isEmpty() ? null : snippets.get(0);
    }

    private boolean containsAny(String value, String... parts) {
        if (value == null) {
            return false;
        }

        for (String part : parts) {
            if (value.contains(part)) {
                return true;
            }
        }

        return false;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }

    private List<String> mergeStringList(Object rawValues) {
        Set<String> values = new LinkedHashSet<>();

        if (rawValues instanceof List<?> list) {
            for (Object item : list) {
                String value = asString(item);

                if (value != null) {
                    values.add(value);
                }
            }
        }

        return new ArrayList<>(values);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }

        String out = value.toString().trim();
        return out.isEmpty() ? null : out;
    }

    private int toInt(Object value) {
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (Exception exception) {
            return 0;
        }
    }

    private List<Object> safeList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }

        return Collections.emptyList();
    }

    private Map<String, Object> safeMap(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> converted = new LinkedHashMap<>();

            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                converted.put(String.valueOf(entry.getKey()), entry.getValue());
            }

            return converted;
        }

        return Collections.emptyMap();
    }

    private Map<String, Object> asMap(Object value) {
        return safeMap(value);
    }
}
