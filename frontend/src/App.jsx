import { useState } from "react";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

export default function App() {
  const [projectId, setProjectId] = useState("3367");
  const [pipelineId, setPipelineId] = useState("72684");
  const [failedJobId, setFailedJobId] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [report, setReport] = useState(null);
  const [showJson, setShowJson] = useState(false);
  const [copyState, setCopyState] = useState("");

  const metadata = report?.reportMetadata || {};
  const summary = report?.pipelineSummary || {};
  const overview = report?.pipelineOverview || {};
  const intelligence = report?.pipelineIntelligence || {};
  const analysisMode = report?.analysisMode || "";
  const analysisSourceJobId = report?.analysisSourceJobId || "";
  const analysisSourceJobName = report?.analysisSourceJobName || "";
  const commitAnalysis = report?.commitAnalysis || {};
  const recentPatterns = report?.recentFailurePatterns || {};
  const operationalInsights = report?.operationalInsights || {};
  const governance = report?.governance || {};
  const analysisCoverage = report?.analysisCoverage || {};
  const enterpriseReadiness = report?.enterpriseReadiness || {};
  const decisionEngine = report?.decisionEngine || {};
  const verificationIntelligence = report?.verificationIntelligence || {};
  const traceabilityIntelligence = report?.traceabilityIntelligence || {};
  const advancedSignals = report?.advancedSignals || {};
  const smartAnswers = report?.smartAnswers || {};
  const failureTimeline = asArray(report?.failureTimeline);
  const proofEngine = report?.proofEngine || {};
  const beforeAfterCommitAnalysis = report?.beforeAfterCommitAnalysis || {};
  const dependencyImpact = report?.dependencyImpact || {};
  const riskPropagation = report?.riskPropagation || {};
  const testImpactAnalysis = report?.testImpactAnalysis || {};
  const failurePatternIntelligence = report?.failurePatternIntelligence || {};
  const fixConfidence = report?.fixConfidence || {};
  const teamLearningMode = report?.teamLearningMode || {};
  const executiveSummary = report?.executiveSummary || {};
  const rootCauseGraph = report?.rootCauseGraph || {};
  const pipelineHealthScore = report?.pipelineHealthScore || {};
  const notificationPreview = report?.notificationPreview || {};
  const primaryFailure = report?.primaryFailureAnalysis || findPrimaryFailureAnalysis(report);
  const jobAnalyses = asArray(report?.jobAnalyses);
  const domainCoverage = asArray(report?.domainCoverage);
  const prioritizedFixes = asArray(report?.prioritizedFixes);
  const changedFiles = asArray(commitAnalysis.changedFiles);
  const changedConfigFiles = asArray(commitAnalysis.changedConfigurationFiles);
  const likelyRelatedFiles = asArray(commitAnalysis.likelyRelatedFiles);
  const recentPipelines = asArray(recentPatterns.recentPipelines);
  const similarFailures = asArray(recentPatterns.similarFailures);
  const supportedCategories = asArray(enterpriseReadiness.supportedFailureCategories);
  const stageHealth = asArray(intelligence.stageHealth);
  const downstreamImpact = asArray(intelligence.downstreamImpactedJobs);
  const coPrimaryFailures = asArray(intelligence.coPrimaryFailures);
  const secondaryFailures = asArray(intelligence.secondaryFailures);
  const independentFailures = asArray(intelligence.independentFailures);
  const failureSignals = asArray(primaryFailure?.signals);
  const resolutionPlaybook = asArray(primaryFailure?.resolutionPlaybook);
  const logHighlights = asArray(primaryFailure?.logHighlights);
  const secondaryIssues = asArray(primaryFailure?.secondaryIssues);
  const details = asArray(primaryFailure?.details);
  const missingSymbols = asArray(primaryFailure?.missingSymbols);
  const fixOptions = asArray(primaryFailure?.fixOptions);
  const signalRanking = primaryFailure?.signalRanking || {};
  const relatedSignals = asArray(signalRanking.relatedSignals);
  const noiseSignals = asArray(signalRanking.noiseSignals);
  const confidenceExplanation = asArray(primaryFailure?.confidenceExplanation);
  const exactFixGuidance = primaryFailure?.exactFixGuidance || {};
  const exactFixSteps = asArray(exactFixGuidance.exactSteps);
  const autoFixPatch = exactFixGuidance.autoFixPatch || "";
  const triageUrgency = primaryFailure?.triageUrgency || operationalInsights?.triageUrgency || {};
  const triageChecklist = asArray(operationalInsights.triageChecklist);
  const enterpriseRecommendations = asArray(operationalInsights.enterpriseRecommendations);
  const securityControls = asArray(governance.securityControls);
  const limitations = asArray(analysisCoverage.limitations);
  const verificationFocusAreas = asArray(verificationIntelligence.verificationFocusAreas);
  const requirementIds = asArray(traceabilityIntelligence.requirementIdsDetected);
  const proofEvidence = asArray(proofEngine.evidence);
  const beforeAfterDifferences = asArray(beforeAfterCommitAnalysis.differences);
  const dependencyReasoning = asArray(dependencyImpact.reasoning);
  const propagationRisks = asArray(riskPropagation.ifNotFixed);
  const triggeringTests = asArray(testImpactAnalysis.triggeringTests);
  const testImpactReasoning = asArray(testImpactAnalysis.reasoning);
  const patternBehaviors = asArray(failurePatternIntelligence.likelyRootBehavior);
  const fixConfidenceBreakdown = asArray(fixConfidence.breakdown);
  const preventionItems = asArray(teamLearningMode.prevention);
  const processImprovements = asArray(teamLearningMode.processImprovements);
  const rootCauseNodes = asArray(rootCauseGraph.nodes);
  const healthBreakdown = asArray(pipelineHealthScore.breakdown);

  async function analyzePipeline() {
    setLoading(true);
    setError("");
    setReport(null);

    try {
      const response = await fetch(`${API_BASE_URL}/api/gitlab/analyze`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          projectId: projectId.trim(),
          pipelineId: pipelineId.trim(),
          failedJobId: failedJobId.trim() || null,
        }),
      });

      const payload = response.headers.get("content-type")?.includes("application/json")
        ? await response.json()
        : { message: await response.text() };

      if (!response.ok) {
        throw new Error(payload?.message || payload?.error || `Request failed with ${response.status}`);
      }

      setReport(payload);
    } catch (requestError) {
      setError(requestError?.message || "Unable to analyze the pipeline.");
    } finally {
      setLoading(false);
    }
  }

  async function copyReport() {
    if (!report) {
      return;
    }

    await navigator.clipboard.writeText(JSON.stringify(report, null, 2));
    setCopyState("Copied report JSON");
    window.setTimeout(() => setCopyState(""), 2000);
  }

  function downloadReport() {
    if (!report) {
      return;
    }

    const blob = new Blob([JSON.stringify(report, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `pipeline-analysis-${projectId}-${pipelineId}.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  return (
    <div className="app-shell">
      <div className="ambient ambient-left" />
      <div className="ambient ambient-right" />

      <main className="app-frame">
        <section className="hero">
          <div className="hero-copy">
            <p className="eyebrow">Enterprise Investigation Console</p>
            <h1>CI/CD Pipeline Failure Intelligence Bot</h1>
            <p className="hero-text">
              Analyze GitLab pipeline failures with root-cause focus, commit intelligence,
              multi-job impact mapping, and governance-safe recommendations for larger teams.
            </p>

            <div className="pill-row">
              <Chip text="Read-only GitLab access" tone="teal" />
              <Chip text="Primary vs secondary failure detection" tone="gold" />
              <Chip text="Commit and config correlation" tone="slate" />
            </div>

            <div className="capability-block">
              <div className="capability-title">Coverage from the product spec</div>
              <div className="capability-grid">
                <Capability label="Failure classes" value="Code, build, test, env, infra, external, pipeline config" />
                <Capability label="Pipeline context" value="Jobs, stages, blast radius, downstream impact" />
                <Capability label="Recent change intelligence" value="Commit metadata, changed files, likely related config" />
                <Capability label="Enterprise controls" value="Read-only mode, recommendations, no auto-remediation" />
              </div>
            </div>
          </div>

          <div className="control-card">
            <div className="panel-heading">
              <div>
                <p className="panel-label">Pipeline Input</p>
                <h2>Run Analysis</h2>
              </div>
              <span className={`status-chip ${loading ? "loading" : "idle"}`}>
                {loading ? "Analyzing" : "Ready"}
              </span>
            </div>

            <div className="form-grid">
              <label className="field">
                <span>Project ID or path</span>
                <input
                  value={projectId}
                  onChange={(event) => setProjectId(event.target.value)}
                  placeholder="e.g. group/project or 3367"
                />
              </label>

              <label className="field">
                <span>Pipeline ID</span>
                <input
                  value={pipelineId}
                  onChange={(event) => setPipelineId(event.target.value)}
                  placeholder="e.g. 72684"
                />
              </label>

              <label className="field">
                <span>Failed Job ID (optional but recommended)</span>
                <input
                  value={failedJobId}
                  onChange={(event) => setFailedJobId(event.target.value)}
                  placeholder="Enter failed job ID (e.g., 266748)"
                />
              </label>
            </div>

            <div className="button-row">
              <button className="button-primary" onClick={analyzePipeline} disabled={loading}>
                {loading ? "Analyzing pipeline..." : "Analyze pipeline"}
              </button>
              <button className="button-secondary" onClick={copyReport} disabled={!report}>
                {copyState || "Copy JSON"}
              </button>
              <button className="button-secondary" onClick={downloadReport} disabled={!report}>
                Download report
              </button>
            </div>

            <p className="helper-text">
              The backend analyzes the pipeline in read-only mode and returns a structured report
              with targeted remediation instead of automatic code changes. If you do not provide a
              failed job ID, the system automatically analyzes the first failed job in the pipeline.
            </p>
          </div>
        </section>

        {error ? <div className="banner error">{error}</div> : null}

        {report ? (
          <>
            <section className="report-strip">
              <div className="strip-card">
                <span className="strip-label">Report ID</span>
                <span className="strip-value mono">{metadata.reportId || "n/a"}</span>
              </div>
              <div className="strip-card">
                <span className="strip-label">Generated</span>
                <span className="strip-value">{formatDate(metadata.generatedAt)}</span>
              </div>
              <div className="strip-card">
                <span className="strip-label">Mode</span>
                <span className="strip-value">{metadata.readOnlyMode ? "Read-only enterprise mode" : "Mutable mode"}</span>
              </div>
              <div className="strip-card">
                <span className="strip-label">Target</span>
                <span className="strip-value">
                  {report.projectId} / pipeline {report.pipelineId}
                </span>
              </div>
              <div className="strip-card">
                <span className="strip-label">Analysis mode</span>
                <span className="strip-value">{analysisMode || "Auto-selected failed job"}</span>
              </div>
              <div className="strip-card">
                <span className="strip-label">Failure source</span>
                <span className="strip-value">
                  {analysisSourceJobId
                    ? `${analysisSourceJobName || "Job"} (#${analysisSourceJobId})`
                    : "No failed job detected"}
                </span>
              </div>
            </section>

            <section className="metric-grid">
              <MetricCard
                label="Pipeline health"
                value={pipelineHealthScore.score !== undefined ? `${pipelineHealthScore.score} / 100` : "Unknown"}
                tone="teal"
              />
              <MetricCard label="Release readiness" value={operationalInsights.releaseReadiness || overview.releaseReadiness || "Unknown"} tone="dark" />
              <MetricCard label="Risk level" value={operationalInsights.riskLevel || "Unknown"} tone="gold" />
              <MetricCard label="Failed jobs" value={summary.failedJobs ?? 0} tone="rose" />
              <MetricCard label="Failure rate" value={formatPercent(summary.failureRatePercent)} tone="slate" />
              <MetricCard label="Blast radius" value={intelligence.failureBlastRadius ?? 0} tone="teal" />
              <MetricCard label="Changed files" value={commitAnalysis.totalChangedFiles ?? 0} tone="cream" />
            </section>

            <section className="panel">
              <PanelHeader
                label="Executive summary"
                title={executiveSummary.rootCauseOneLine || "No executive summary generated"}
                meta={proofEngine.verdict || "No proof verdict"}
              />

              <TextBlock
                title="Manager one-line root cause"
                body={executiveSummary.managerSummary || "No one-line root cause was generated."}
              />
            </section>

            <section className="content-grid">
              <article className="panel panel-feature">
                <PanelHeader
                  label="Primary failure"
                  title={primaryFailure?.errorTypeDisplay || primaryFailure?.errorType || primaryFailure?.failureType || "No primary failure detected"}
                  meta={primaryFailure?.category || "Pipeline healthy"}
                />

                <div className="pill-row">
                  <Chip text={`Severity: ${primaryFailure?.severity || "n/a"}`} tone="rose" />
                  <Chip text={`Confidence: ${primaryFailure?.confidence || "n/a"}`} tone="slate" />
                  <Chip text={`Owner: ${primaryFailure?.recommendedOwner || "Engineering team"}`} tone="gold" />
                  <Chip text={`Urgency: ${triageUrgency.level || "n/a"}`} tone="teal" />
                  <Chip
                    text={primaryFailure?.requiresHumanReview ? "Human review required" : primaryFailure?.recommendationPolicy || "Guided triage"}
                    tone={primaryFailure?.requiresHumanReview ? "gold" : "teal"}
                  />
                </div>

                <DefinitionGrid
                  items={[
                    ["Error type", primaryFailure?.errorTypeDisplay || primaryFailure?.errorType || primaryFailure?.failureType || "Unknown"],
                    ["Tool", primaryFailure?.tool || "Unknown"],
                    ["File", buildFileLocation(primaryFailure)],
                    ["Class / function", primaryFailure?.classOrFunction || "Unknown"],
                    ["Missing symbols", joinItems(missingSymbols) || primaryFailure?.symbolName || "Not extracted"],
                    ["Commit", report.pipelineCommit || "Unavailable"],
                    ["Pipeline ref", overview.pipelineRef || "Unknown"],
                    ["Signature", primaryFailure?.signatureId || "No exact catalog match"],
                    ["Fingerprint", primaryFailure?.failureFingerprint || "Unavailable"],
                  ]}
                />

                <TextBlock title="Root failure" body={primaryFailure?.rootFailureStatement || "No root-failure statement was generated."} />
                <TextBlock title="Root cause" body={primaryFailure?.rootCause || "No root cause was extracted."} />
                <TextBlock title="What is wrong" body={primaryFailure?.whatIsWrong || "No exact problem statement was generated."} />
                <TextBlock title="Meaning" body={primaryFailure?.meaning || "No meaning was generated."} />
                <TextBlock title="Likely cause" body={primaryFailure?.likelyCause || "No likely cause was generated."} />
                <TextBlock title="Why this failed" body={primaryFailure?.whyExplanation || "No explanation was generated."} />
                <TextBlock title="Recommended fix" body={primaryFailure?.fixRecommendation || "No recommendation was generated."} />
                <TextBlock title="Fix diff suggestion" body={exactFixGuidance?.suggestedDiff || "No direct diff-style suggestion was generated."} />
                <TextBlock title="Next best action" body={primaryFailure?.nextBestAction || "No next-step guidance was generated."} />
                <TextBlock title="Leading error signal" body={primaryFailure?.errorMessage || "No highlighted error line available."} mono />
                <ListBlock title="Details" items={details} emptyText="No extra details were extracted." mono />
                <ListBlock title="Fix options" items={fixOptions} emptyText="No fix options were generated." />
                {primaryFailure?.knowledgeGap ? (
                  <TextBlock title="Knowledge gap" body={primaryFailure.knowledgeGap} />
                ) : null}

                <ListBlock title="Resolution playbook" items={resolutionPlaybook} emptyText="No playbook steps generated." />
                <ListBlock title="Exact fix steps" items={exactFixSteps} emptyText="No exact step sequence was generated." />
                <DiffBlock title="Auto fix patch" body={autoFixPatch} emptyText="No exact patch could be generated for this failure yet." />
                <ListBlock title="Secondary issues" items={secondaryIssues} emptyText="No secondary issues were detected." mono />
                <ListBlock title="Confidence reasoning" items={confidenceExplanation} emptyText="No confidence explanation was generated." />
                <TextBlock title="Root error" body={signalRanking.rootError || "No root error was extracted."} mono />
                <ListBlock title="Related signals" items={relatedSignals} emptyText="No related signals were ranked." mono />
                <ListBlock title="Noise signals" items={noiseSignals} emptyText="No likely noise signals were separated." mono />
                <ListBlock title="Failure signals" items={failureSignals} emptyText="No additional failure signals captured." />
                <ListBlock title="Log highlights" items={logHighlights} emptyText="No high-signal lines extracted." mono />
              </article>

              <article className="panel">
                <PanelHeader
                  label="Operational guidance"
                  title={operationalInsights.actionPriority || "Operational context"}
                  meta={operationalInsights.riskLevel || "Unknown risk"}
                />

                <DefinitionGrid
                  items={[
                    ["Project", overview.projectPath || report.projectId || "Unknown"],
                    ["Pipeline status", overview.pipelineStatus || summary.pipelineStatus || "Unknown"],
                    ["Blocked stages", joinItems(asArray(operationalInsights.affectedStages)) || "None"],
                    ["Urgency", triageUrgency.action || "Unknown"],
                    ["Recommended owners", joinItems(asArray(operationalInsights.recommendedOwners)) || "Not assigned"],
                  ]}
                />

                <TextBlock title="Urgency reason" body={triageUrgency.reason || "No urgency explanation available."} />
                <ListBlock title="Triage checklist" items={triageChecklist} emptyText="No triage checklist generated." />
                <ListBlock title="Enterprise recommendations" items={enterpriseRecommendations} emptyText="No enterprise recommendations generated." />
                <ListBlock title="Security controls" items={securityControls} emptyText="No governance controls listed." />
              </article>
            </section>

            <section className="content-grid">
              <article className="panel">
                <PanelHeader
                  label="Health and priorities"
                  title={pipelineHealthScore.summary || "No pipeline-health score generated"}
                  meta={pipelineHealthScore.grade || "Unknown"}
                />

                <TableBlock
                  title="Health breakdown"
                  headers={["Factor", "Delta", "Reason"]}
                  rows={healthBreakdown.map((item) => [
                    item.label,
                    formatSignedNumber(item.delta),
                    item.reason,
                  ])}
                  emptyText="No health-score breakdown was generated."
                />

                <PriorityFixList fixes={prioritizedFixes} />
              </article>

              <article className="panel">
                <PanelHeader
                  label="Proof engine"
                  title={proofEngine.conclusion || "No proof conclusion"}
                  meta={proofEngine.proofStrength || "No proof strength"}
                />

                <DefinitionGrid
                  items={[
                    ["Verdict", proofEngine.verdict || "Unknown"],
                    ["Proof strength", proofEngine.proofStrength || "Unknown"],
                    ["Previous success", proofEngine.previousSuccessfulPipeline?.pipelineId || "Unavailable"],
                    ["Historical match", proofEngine.historicalMatch?.pipelineId || "Unavailable"],
                  ]}
                />

                <ListBlock title="Evidence" items={proofEvidence} emptyText="No proof evidence was collected." />
                <TextBlock title="Before" body={beforeAfterCommitAnalysis.beforeState || "No before-state comparison available."} />
                <TextBlock title="After" body={beforeAfterCommitAnalysis.afterState || "No after-state comparison available."} />
                <ListBlock title="Differences" items={beforeAfterDifferences} emptyText="No before-vs-after differences were generated." />
              </article>

              <article className="panel">
                <PanelHeader
                  label="Fix confidence"
                  title={fixConfidence.percent ? `${fixConfidence.percent}%` : "No fix confidence"}
                  meta={fixConfidence.band || "Unknown"}
                />

                <TextBlock title="Narrative" body={fixConfidence.narrative || "No fix-confidence narrative was generated."} />
                <TableBlock
                  title="Confidence breakdown"
                  headers={["Factor", "Delta", "Reason"]}
                  rows={fixConfidenceBreakdown.map((item) => [
                    item.label,
                    formatSignedNumber(item.delta),
                    item.reason,
                  ])}
                  emptyText="No confidence breakdown was generated."
                />
                <TextBlock title="Pattern" body={failurePatternIntelligence.pattern || "No pattern meaning was generated."} />
                <TextBlock title="Pattern meaning" body={failurePatternIntelligence.patternMeaning || "No pattern-meaning summary was generated."} />
                <ListBlock title="Likely root behavior" items={patternBehaviors} emptyText="No team-behavior pattern was generated." />
              </article>
            </section>

            <section className="content-grid">
              <article className="panel">
                <PanelHeader
                  label="Decision engine"
                  title={decisionEngine.issueClass || "No issue-class decision"}
                  meta={decisionEngine.whoLikelyOwnsIt || "Owner unknown"}
                />

                <DefinitionGrid
                  items={[
                    ["Issue class", decisionEngine.issueClass || "Unknown"],
                    ["Likely owner", decisionEngine.whoLikelyOwnsIt || "Unknown"],
                    ["Confidence", decisionEngine.confidencePercent ? `${decisionEngine.confidencePercent}%` : "Unknown"],
                    ["Code issue", booleanLabel(decisionEngine.isCodeIssue)],
                    ["Infra issue", booleanLabel(decisionEngine.isInfraIssue)],
                    ["External issue", booleanLabel(decisionEngine.isExternalIssue)],
                    ["Test issue", booleanLabel(decisionEngine.isTestIssue)],
                  ]}
                />

                <TextBlock title="Reasoning" body={decisionEngine.reasoning || "No reasoning generated."} />
                <TextBlock title="Confidence narrative" body={decisionEngine.confidenceNarrative || "No confidence narrative generated."} />
                <ListBlock title="Confidence reasons" items={asArray(decisionEngine.confidenceReasons)} emptyText="No confidence reasons generated." />
                <ListBlock title="Verification focus areas" items={verificationFocusAreas} emptyText="No verification-specific focus areas detected." />
                <TextBlock
                  title="Requirement impact"
                  body={requirementIds.length ? requirementIds.join(", ") : "No authoritative requirement IDs were detected from the current evidence."}
                />
              </article>

              <article className="panel">
                <PanelHeader
                  label="Smart answers"
                  title="Fast triage answers"
                  meta={smartAnswers.hasThisHappenedBefore || "No history answer"}
                />

                <TextBlock title="Why did pipeline fail?" body={smartAnswers.whyDidPipelineFail || "No answer available."} />
                <TextBlock title="Who caused it?" body={smartAnswers.whoCausedIt || "No answer available."} />
                <TextBlock title="Is it code or infra?" body={smartAnswers.isItCodeOrInfra || "No answer available."} />
                <TextBlock title="Which requirement is affected?" body={smartAnswers.whichRequirementIsAffected || "No answer available."} />
                <TextBlock title="What is the exact fix?" body={smartAnswers.whatIsTheExactFix || "No answer available."} />
                <TextBlock title="How urgent is it?" body={smartAnswers.howUrgentIsIt || "No answer available."} />
                <TextBlock title="Verification concern" body={smartAnswers.verificationConcern || "No answer available."} />
              </article>
            </section>

            <section className="content-grid">
              <article className="panel">
                <PanelHeader
                  label="Root cause graph"
                  title={rootCauseGraph.graphSummary || "No graph summary generated"}
                  meta={`${rootCauseNodes.length} node(s)`}
                />

                <RootCauseGraph nodes={rootCauseNodes} />
              </article>

              <article className="panel">
                <PanelHeader
                  label="Notification preview"
                  title="Slack / Teams message"
                  meta={fixConfidence.percent ? `${fixConfidence.percent}% confidence` : "No confidence"}
                />

                <DiffBlock
                  title="Message preview"
                  body={notificationPreview.slackMessage || notificationPreview.teamsMessage || ""}
                  emptyText="No notification preview was generated."
                />
              </article>
            </section>

            <section className="content-grid">
              <article className="panel">
                <PanelHeader label="Pipeline overview" title="Execution topology" meta={`${overview.totalJobs || 0} jobs across ${overview.totalStages || 0} stages`} />

                <DefinitionGrid
                  items={[
                    ["Project", overview.projectName || "Unknown"],
                    ["Ref", overview.pipelineRef || "Unknown"],
                    ["Source", overview.pipelineSource || "Unknown"],
                    ["Duration", formatDuration(overview.pipelineDuration)],
                  ]}
                />

                <div className="subgrid">
                  <ListBlock title="Co-primary failures" items={formatJobCollection(coPrimaryFailures)} emptyText="No co-primary failures detected." />
                  <ListBlock title="Secondary failures" items={formatJobCollection(secondaryFailures)} emptyText="No secondary failures detected." />
                  <ListBlock title="Independent failures" items={formatJobCollection(independentFailures)} emptyText="No independent failures detected." />
                  <ListBlock title="Downstream impact" items={formatJobCollection(downstreamImpact)} emptyText="No downstream impacted jobs detected." />
                </div>

                <TableBlock
                  title="Stage health"
                  headers={["Stage", "Total", "Failed", "Success", "Skipped"]}
                  rows={stageHealth.map((stage) => [
                    stage.stage,
                    stage.totalJobs,
                    stage.failedJobs,
                    stage.successJobs,
                    stage.skippedJobs,
                  ])}
                  emptyText="No stage health data available."
                />
              </article>

              <article className="panel">
                <PanelHeader label="Commit intelligence" title={commitAnalysis.commitTitle || "No commit metadata"} meta={commitAnalysis.commitAuthor || "Unknown author"} />

                <DefinitionGrid
                  items={[
                    ["Commit SHA", report.pipelineCommit || "Unavailable"],
                    ["Authored at", formatDate(commitAnalysis.commitAuthoredAt)],
                    ["Changed files", commitAnalysis.totalChangedFiles ?? 0],
                    ["Config files", commitAnalysis.configurationFileCount ?? 0],
                    ["Causation", commitAnalysis.causationType || "Unknown"],
                    ["Confidence", commitAnalysis.causationConfidence || "Unknown"],
                  ]}
                />

                <TextBlock title="Why this commit caused this failure" body={commitAnalysis.causalAssessment || "No causal assessment generated."} />
                <TextBlock title="Smart file correlation" body={commitAnalysis.smartFileCorrelation || "No smart file correlation generated."} />
                <ListBlock title="Commit highlights" items={asArray(commitAnalysis.analysisHighlights)} emptyText="No commit highlights generated." />
                <ListBlock title="Correlation reasoning" items={asArray(commitAnalysis.correlationReasoning)} emptyText="No correlation reasoning generated." />
                <TimelineBlock steps={failureTimeline} />
                <FileList title="Likely related files" files={likelyRelatedFiles} emptyText="No likely related files were identified." />
                <FileList title="Changed configuration files" files={changedConfigFiles} emptyText="No changed configuration files were found in the current diff." />
                <FileList title="All changed files" files={changedFiles} emptyText="No changed files were returned by GitLab." compact />
              </article>
            </section>

            <section className="content-grid">
              <article className="panel">
                <PanelHeader
                  label="Dependency and test impact"
                  title={dependencyImpact.summary || "No dependency-impact summary"}
                  meta={testImpactAnalysis.summary || "No test-impact summary"}
                />

                <DefinitionGrid
                  items={[
                    ["Affected stages", joinItems(asArray(dependencyImpact.affectedStages)) || "Unknown"],
                    ["Affected modules", dependencyImpact.affectedModulesEstimate ?? "Unknown"],
                    ["Future risk", riskPropagation.futurePipelineRisk || "Unknown"],
                    ["Deployment blocked", booleanLabel(riskPropagation.deploymentBlocked)],
                  ]}
                />

                <ListBlock title="Dependency reasoning" items={dependencyReasoning} emptyText="No dependency-impact reasoning was generated." />
                <ListBlock title="Triggering tests" items={triggeringTests} emptyText="No triggering tests were identified." />
                <ListBlock title="Test-impact reasoning" items={testImpactReasoning} emptyText="No test-impact reasoning was generated." />
                <ListBlock title="If not fixed" items={propagationRisks} emptyText="No risk-propagation forecast was generated." />
              </article>

              <article className="panel">
                <PanelHeader
                  label="Team learning mode"
                  title={teamLearningMode.pattern || "No learning pattern"}
                  meta="Prevention guidance"
                />

                <ListBlock title="Prevention" items={preventionItems} emptyText="No prevention guidance was generated." />
                <ListBlock title="Process improvements" items={processImprovements} emptyText="No process-improvement guidance was generated." />
              </article>
            </section>

            <section className="content-grid">
              <article className="panel">
                <PanelHeader label="Job analyses" title={`${jobAnalyses.length} failed jobs analyzed`} meta="Per-job failure details" />

                <div className="job-grid">
                  {jobAnalyses.length ? (
                    jobAnalyses.map((job) => {
                      const failure = job.failureAnalysis || {};
                      return (
                        <div className="job-card" key={job.jobId}>
                          <div className="job-topline">
                            <div>
                              <h3>{job.jobName}</h3>
                              <p>
                                #{job.jobId} • {job.stage || "Unknown stage"}
                              </p>
                            </div>
                            <span className="mini-pill">{failure.severity || failure.confidence || "Info"}</span>
                          </div>
                          <p className="job-category">{failure.category || "Unknown failure category"}</p>
                          <p className="job-copy">{failure.rootCause || "No root cause details available."}</p>
                          <div className="job-meta">
                            <span>{failure.recommendedOwner || "Engineering team"}</span>
                            <span>{failure.tool || "Unknown tool"}</span>
                            <span>{failure.signatureId || "No exact match"}</span>
                          </div>
                          {failure.requiresHumanReview ? (
                            <p className="job-review-flag">Human review required for this recommendation.</p>
                          ) : null}
                        </div>
                      );
                    })
                  ) : (
                    <p className="empty-copy">No failed jobs were detected in this pipeline.</p>
                  )}
                </div>
              </article>

              <article className="panel">
                <PanelHeader label="Recurrence and governance" title={recentPatterns.historyWindow || "Recent pipeline trend context"} meta={formatPercent(recentPatterns.recentFailureRatePercent)} />

                <TextBlock title="Recurrence summary" body={recentPatterns.recurrenceSummary || "No recurrence summary was generated."} />
                <ListBlock
                  title="Recurring failed jobs"
                  items={formatRecurringJobs(recentPatterns.recurringFailedJobs)}
                  emptyText="No recurring failed jobs were detected in the recent history window."
                />
                <TableBlock
                  title="Similar failures"
                  headers={["Pipeline", "Job", "Matched on", "Signature"]}
                  rows={similarFailures.map((failure) => [
                    failure.pipelineId,
                    failure.jobName,
                    failure.matchedOn,
                    failure.signatureId || "Unknown",
                  ])}
                  emptyText="No similar signature or fingerprint matches were found."
                />

                <TableBlock
                  title="Recent pipelines"
                  headers={["Pipeline", "Status", "Ref", "Created"]}
                  rows={recentPipelines.map((pipeline) => [
                    pipeline.pipelineId,
                    pipeline.status,
                    pipeline.ref,
                    formatDate(pipeline.createdAt),
                  ])}
                  emptyText="No recent pipelines were returned."
                />

                <ListBlock title="Coverage limitations" items={limitations} emptyText="No current limitations reported." />
                <ListBlock title="Supported failure categories" items={supportedCategories} emptyText="No supported categories listed." />
                <TableBlock
                  title="Problem domain coverage"
                  headers={["Domain", "Status", "Note"]}
                  rows={domainCoverage.map((domain) => [domain.name, domain.status, domain.note])}
                  emptyText="No domain coverage matrix was generated."
                />
                <ListBlock
                  title="Advanced signals"
                  items={Object.entries(advancedSignals).map(([key, value]) => `${humanizeKey(key)}: ${String(value)}`)}
                  emptyText="No advanced signals available."
                />
              </article>
            </section>

            <section className="panel">
              <div className="panel-heading panel-heading-inline">
                <div>
                  <p className="panel-label">Raw output</p>
                  <h2>Enterprise report JSON</h2>
                </div>
                <button className="button-secondary" onClick={() => setShowJson((value) => !value)}>
                  {showJson ? "Hide JSON" : "Show JSON"}
                </button>
              </div>

              {showJson ? <pre className="json-block">{JSON.stringify(report, null, 2)}</pre> : null}
            </section>
          </>
        ) : (
          <section className="panel empty-panel">
            <PanelHeader
              label="Ready for analysis"
              title="Run a pipeline investigation"
              meta="The report will combine log analysis, diff correlation, multi-job intelligence, and governance-safe recommendations."
            />

            <div className="category-grid">
              {[
                "Code compilation failures",
                "Build configuration failures",
                "Test failures",
                "Environment failures",
                "Infrastructure failures",
                "External system failures",
                "Pipeline configuration failures",
              ].map((item) => (
                <div className="category-card" key={item}>
                  {item}
                </div>
              ))}
            </div>
          </section>
        )}
      </main>
    </div>
  );
}

function MetricCard({ label, value, tone }) {
  return (
    <div className={`metric-card tone-${tone || "slate"}`}>
      <span className="metric-label">{label}</span>
      <strong className="metric-value">{value}</strong>
    </div>
  );
}

function PanelHeader({ label, title, meta }) {
  return (
    <div className="panel-heading">
      <div>
        <p className="panel-label">{label}</p>
        <h2>{title}</h2>
      </div>
      <span className="panel-meta">{meta}</span>
    </div>
  );
}

function Chip({ text, tone }) {
  return <span className={`chip chip-${tone || "slate"}`}>{text}</span>;
}

function Capability({ label, value }) {
  return (
    <div className="capability-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function DefinitionGrid({ items }) {
  return (
    <div className="definition-grid">
      {items.map(([label, value]) => (
        <div className="definition-item" key={label}>
          <span>{label}</span>
          <strong>{value || "Unavailable"}</strong>
        </div>
      ))}
    </div>
  );
}

function TextBlock({ title, body, mono }) {
  return (
    <div className="text-block">
      <span className="text-block-title">{title}</span>
      <p className={mono ? "mono" : ""}>{body}</p>
    </div>
  );
}

function DiffBlock({ title, body, emptyText }) {
  return (
    <div className="list-block">
      <span className="text-block-title">{title}</span>
      {body ? <pre className="json-block diff-block">{body}</pre> : <p className="empty-copy">{emptyText}</p>}
    </div>
  );
}

function ListBlock({ title, items, emptyText, mono }) {
  return (
    <div className="list-block">
      <span className="text-block-title">{title}</span>
      {items.length ? (
        <ul className={mono ? "mono" : ""}>
          {items.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      ) : (
        <p className="empty-copy">{emptyText}</p>
      )}
    </div>
  );
}

function FileList({ title, files, emptyText, compact }) {
  return (
    <div className="list-block">
      <span className="text-block-title">{title}</span>
      {files.length ? (
        <div className={`file-list ${compact ? "compact" : ""}`}>
          {files.map((file) => (
            <div className="file-row" key={`${file.path}-${file.category}`}>
              <div>
                <strong className="mono">{file.path}</strong>
                <p>{file.category || "Unknown category"}</p>
              </div>
              <span className="mini-pill">{file.changeType || "Changed"}</span>
            </div>
          ))}
        </div>
      ) : (
        <p className="empty-copy">{emptyText}</p>
      )}
    </div>
  );
}

function TableBlock({ title, headers, rows, emptyText }) {
  return (
    <div className="list-block">
      <span className="text-block-title">{title}</span>
      {rows.length ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                {headers.map((header) => (
                  <th key={header}>{header}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.join("-")}>
                  {row.map((cell, index) => (
                    <td key={`${row[0]}-${headers[index]}`}>{cell || "—"}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="empty-copy">{emptyText}</p>
      )}
    </div>
  );
}

function TimelineBlock({ steps }) {
  return (
    <div className="list-block">
      <span className="text-block-title">Failure timeline</span>
      {steps.length ? (
        <ol>
          {steps.map((step) => (
            <li key={`${step.step}-${step.title}`}>
              <strong>{step.title}:</strong> {step.detail || "No detail available."}
            </li>
          ))}
        </ol>
      ) : (
        <p className="empty-copy">No failure timeline was generated.</p>
      )}
    </div>
  );
}

function PriorityFixList({ fixes }) {
  return (
    <div className="list-block">
      <span className="text-block-title">What to fix first</span>
      {fixes.length ? (
        <div className="priority-fix-list">
          {fixes.map((fix) => (
            <div className="priority-fix" key={`${fix.priority}-${fix.title}`}>
              <div className="priority-fix-topline">
                <strong>Priority {fix.priority}</strong>
                <span className="mini-pill">{fix.title}</span>
              </div>
              <p>{fix.action}</p>
              <p className="empty-copy">{fix.rationale}</p>
            </div>
          ))}
        </div>
      ) : (
        <p className="empty-copy">No prioritized fix queue was generated.</p>
      )}
    </div>
  );
}

function RootCauseGraph({ nodes }) {
  const [activeNodeId, setActiveNodeId] = useState(nodes[0]?.id || "");
  const activeNode = nodes.find((node) => node.id === activeNodeId) || nodes[0];

  return (
    <div className="list-block">
      <span className="text-block-title">Interactive flow</span>
      {nodes.length ? (
        <>
          <div className="graph-flow">
            {nodes.map((node, index) => (
              <div className="graph-segment" key={node.id}>
                <button
                  className={`graph-node ${activeNode?.id === node.id ? "active" : ""}`}
                  onClick={() => setActiveNodeId(node.id)}
                  type="button"
                >
                  <span>{node.type}</span>
                  <strong>{node.label}</strong>
                </button>
                {index < nodes.length - 1 ? <span className="graph-arrow">↓</span> : null}
              </div>
            ))}
          </div>
          <div className="graph-detail">
            <strong>{activeNode?.label || "Selected node"}</strong>
            <p>{activeNode?.detail || "No detail available."}</p>
          </div>
        </>
      ) : (
        <p className="empty-copy">No root-cause graph was generated.</p>
      )}
    </div>
  );
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function findPrimaryFailureAnalysis(report) {
  const primaryFailure = report?.pipelineIntelligence?.primaryFailure;
  const jobAnalyses = asArray(report?.jobAnalyses);

  if (!primaryFailure?.jobId) {
    return {};
  }

  const match = jobAnalyses.find((job) => job.jobId === primaryFailure.jobId);
  return match?.failureAnalysis || {};
}

function buildFileLocation(failure) {
  if (!failure?.file) {
    return "Unknown location";
  }

  return [failure.file, failure.line, failure.column].filter(Boolean).join(":");
}

function formatJobCollection(items) {
  return items.map((item) => {
    const reason = item.impactReason ? ` - ${item.impactReason}` : "";
    return `${item.jobName || "Unknown job"} (#${item.jobId || "?"})${reason}`;
  });
}

function formatRecurringJobs(recurringJobs) {
  if (!recurringJobs || typeof recurringJobs !== "object") {
    return [];
  }

  return Object.entries(recurringJobs).map(([job, count]) => `${job} repeated in ${count} recent failed pipeline(s)`);
}

function joinItems(items) {
  return items.filter(Boolean).join(", ");
}

function formatDate(value) {
  if (!value) {
    return "Unavailable";
  }

  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function formatPercent(value) {
  const number = Number(value);
  return Number.isFinite(number) ? `${number.toFixed(1)}%` : "Unavailable";
}

function formatDuration(value) {
  const seconds = Number(value);

  if (!Number.isFinite(seconds)) {
    return "Unavailable";
  }

  if (seconds < 60) {
    return `${seconds.toFixed(0)}s`;
  }

  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = Math.round(seconds % 60);
  return `${minutes}m ${remainingSeconds}s`;
}

function formatSignedNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? `+${number}` : String(value ?? "0");
}

function booleanLabel(value) {
  return value ? "Yes" : "No";
}

function humanizeKey(value) {
  return value
    .replace(/([A-Z])/g, " $1")
    .replace(/^./, (char) => char.toUpperCase());
}
