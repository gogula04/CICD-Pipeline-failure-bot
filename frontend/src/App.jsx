import { useMemo, useState } from "react";
import AICopilot from "./AICopilot";

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
  const operationalInsights = report?.operationalInsights || {};
  const primaryFailure = report?.primaryFailureAnalysis || findPrimaryFailureAnalysis(report);
  const commitAnalysis = report?.commitAnalysis || {};
  const executiveSummary = report?.executiveSummary || {};
  const prioritizedFixes = asArray(report?.prioritizedFixes).slice(0, 3);
  const analysisLayers = asArray(metadata.analysisLayers);
  const changedFiles = asArray(commitAnalysis.changedFiles)
    .slice(0, 5)
    .map((file) => (typeof file === "string" ? { path: file } : file));
  const logHighlights = asArray(primaryFailure?.logHighlights).slice(0, 5);

  const analysisEngine = metadata.analysisEngine || "Python signatures only";
  const aiProvider = metadata.aiProvider || "Groq AI";
  const aiModel = metadata.aiModel || "";
  const aiStatus = metadata.aiEnabled
    ? `Groq AI active${aiModel ? ` (${aiModel})` : ""}`
    : "Set GROQ_API_KEY to enable Groq AI";
  const aiProviderLabel = metadata.aiEnabled
    ? `${aiProvider}${aiModel ? ` / ${aiModel}` : ""}`
    : "Disabled";

  const analysisMode = report?.analysisMode || "Auto-selected failed job";
  const analysisSourceJobId = report?.analysisSourceJobId || "";
  const analysisSourceJobName = report?.analysisSourceJobName || "";
  const primaryTitle =
    primaryFailure?.errorTypeDisplay ||
    primaryFailure?.errorType ||
    primaryFailure?.failureType ||
    "No primary failure detected";
  const primaryMeta = primaryFailure?.category || "Pipeline healthy";
  const humanSummary =
    executiveSummary.managerSummary ||
    executiveSummary.rootCauseOneLine ||
    primaryFailure?.rootCause ||
    "No summary was generated yet.";
  const rootCause = primaryFailure?.rootCause || primaryFailure?.whyExplanation || "No root cause was extracted.";
  const recommendedFix =
    primaryFailure?.fixRecommendation ||
    prioritizedFixes[0]?.action ||
    "No recommendation was generated.";
  const nextBestAction =
    primaryFailure?.nextBestAction ||
    operationalInsights.actionPriority ||
    "No next best action was generated.";
  const failureSource = analysisSourceJobId
    ? `${analysisSourceJobName || "Job"} (#${analysisSourceJobId})`
    : "No failed job detected";

  const healthScore =
    report?.pipelineHealthScore?.score !== undefined
      ? `${report.pipelineHealthScore.score} / 100`
      : "Unknown";
  const releaseReadiness = operationalInsights.releaseReadiness || overview.releaseReadiness || "Unknown";
  const riskLevel = operationalInsights.riskLevel || "Unknown";
  const failedJobs = summary.failedJobs ?? 0;
  const changedFilesCount = commitAnalysis.totalChangedFiles ?? changedFiles.length ?? 0;
  const copilotAnalysis = useMemo(() => buildCopilotAnalysis(report), [report]);

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
    setCopyState("Copied JSON");
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
              Python Engine detects and classifies the failure. Spring Boot combines logs, commits,
              and pipeline context. Groq AI explains the result and improves the fix. The frontend
              keeps the report human-friendly.
            </p>

            <div className="pill-row">
              <Chip text="Read-only GitLab access" tone="teal" />
              <Chip text="Groq AI reasoning" tone="rose" />
              <Chip text="Clean human-level report" tone="gold" />
            </div>

            <div className="ai-callout">
              <div className="ai-callout-copy">
                <p className="ai-callout-label">Workflow</p>
                <h3>Python Engine → Spring Boot → Groq AI → Frontend</h3>
                <p>
                  {analysisLayers.length
                    ? analysisLayers.join(" → ")
                    : "Layer 1: Python signatures → Layer 2: Groq AI reasoning"}
                </p>
                <p>
                  Groq runs when <span className="mono">GROQ_API_KEY</span> is set in{" "}
                  <span className="mono">backend/intelligence-bot/.env</span>.
                </p>
              </div>
              <div className="ai-callout-side">
                <Chip text={aiStatus} tone="rose" />
                <Chip text={analysisEngine} tone="teal" />
              </div>
            </div>

            <div className="capability-block">
              <div className="capability-title">How the tool works</div>
              <div className="capability-grid">
                <Capability
                  label="Python Engine"
                  value="Detect + Classify + Extract facts"
                />
                <Capability
                  label="Spring Boot"
                  value="Combine context (logs + commits + pipeline)"
                />
                <Capability
                  label="Groq AI"
                  value="Explain + Reason + Improve fixes"
                />
                <Capability
                  label="Frontend"
                  value="Show clean, human-level report"
                />
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
                <span>Failed Job ID (optional)</span>
                <input
                  value={failedJobId}
                  onChange={(event) => setFailedJobId(event.target.value)}
                  placeholder="Enter failed job ID"
                />
              </label>
            </div>

            <div className="button-row">
              <button className="button-primary" onClick={analyzePipeline} disabled={loading}>
                {loading ? "Analyzing pipeline..." : "Analyze pipeline"}
              </button>
            </div>

            <p className="helper-text">
              The backend fetches GitLab data, Python signatures classify the failure, and Groq AI
              adds context only when the API key is present.
            </p>
          </div>
        </section>

        {error ? <div className="banner error">{error}</div> : null}

        {report ? (
          <>
            <section className="report-strip">
              <StripCard label="Report ID" value={metadata.reportId || "n/a"} mono />
              <StripCard label="Generated" value={formatDate(metadata.generatedAt)} />
              <StripCard label="AI provider" value={aiProviderLabel} />
              <StripCard label="Analysis mode" value={analysisMode} />
              <StripCard
                label="Target"
                value={`${report.projectId || projectId} / pipeline ${report.pipelineId || pipelineId}`}
              />
            </section>

            <section className="metric-grid">
              <MetricCard label="Pipeline health" value={healthScore} tone="teal" />
              <MetricCard label="Release readiness" value={releaseReadiness} tone="dark" />
              <MetricCard label="Risk level" value={riskLevel} tone="gold" />
              <MetricCard label="Failed jobs" value={failedJobs} tone="rose" />
            </section>

            <section className="content-grid">
              <article className="panel panel-feature">
                <PanelHeader label="What happened" title={primaryTitle} meta={primaryMeta} />

                <div className="pill-row">
                  <Chip text={`Severity: ${primaryFailure?.severity || "n/a"}`} tone="rose" />
                  <Chip text={`Confidence: ${primaryFailure?.confidence || "n/a"}`} tone="slate" />
                  <Chip
                    text={`Owner: ${primaryFailure?.recommendedOwner || "Engineering team"}`}
                    tone="gold"
                  />
                  <Chip text={`Urgency: ${primaryFailure?.triageUrgency?.level || "n/a"}`} tone="teal" />
                </div>

                <TextBlock title="One-line summary" body={humanSummary} />

                <DefinitionGrid
                  items={[
                    ["Error type", primaryFailure?.errorTypeDisplay || primaryFailure?.errorType || "Unknown"],
                    ["File", buildFileLocation(primaryFailure)],
                    ["Tool", primaryFailure?.tool || "Unknown"],
                    ["Commit", report.pipelineCommit || "Unavailable"],
                    ["Failure source", failureSource],
                    ["Analysis source", primaryFailure?.analysisSource || "Unknown"],
                  ]}
                />

                <TextBlock title="Root cause" body={rootCause} />
                <TextBlock title="Recommended fix" body={recommendedFix} />
                <TextBlock title="Next best action" body={nextBestAction} />
                <ListBlock
                  title="Log highlights"
                  items={logHighlights}
                  emptyText="No strong log highlights were extracted."
                  mono
                />
              </article>

              <article className="panel">
                <PanelHeader
                  label="What changed"
                  title={commitAnalysis.commitTitle || "Commit context"}
                  meta={commitAnalysis.commitAuthor || "Unknown author"}
                />

                <TextBlock
                  title="Commit summary"
                  body={
                    commitAnalysis.causalAssessment ||
                    commitAnalysis.smartFileCorrelation ||
                    "No commit summary was generated."
                  }
                />

                <DefinitionGrid
                  items={[
                    ["Changed files", changedFilesCount],
                    ["Config files", commitAnalysis.configurationFileCount ?? 0],
                    ["Causation", commitAnalysis.causationType || "Unknown"],
                    ["Confidence", commitAnalysis.causationConfidence || "Unknown"],
                  ]}
                />

                <FileList
                  title="Changed files"
                  files={changedFiles}
                  emptyText="No changed files were returned by GitLab."
                  compact
                />
              </article>
            </section>

            <section className="panel empty-panel">
              <PanelHeader
                label="Recommended next steps"
                title="Top fixes"
                meta={`${prioritizedFixes.length} suggestion(s)`}
              />
              <PriorityFixList fixes={prioritizedFixes} />
            </section>

            {copilotAnalysis ? (
              <AICopilot
                analysis={copilotAnalysis}
                analysisKey={metadata.reportId || `${projectId}-${pipelineId}`}
              />
            ) : null}

            <section className="panel empty-panel">
              <div className="panel-heading panel-heading-inline">
                <div>
                  <p className="panel-label">Technical details</p>
                  <h2>Raw report JSON</h2>
                </div>
                <button className="button-secondary" onClick={() => setShowJson((value) => !value)}>
                  {showJson ? "Hide JSON" : "Show JSON"}
                </button>
              </div>

              <div className="button-row" style={{ marginTop: "1rem" }}>
                <button className="button-secondary" onClick={copyReport} disabled={!report}>
                  {copyState || "Copy JSON"}
                </button>
                <button className="button-secondary" onClick={downloadReport} disabled={!report}>
                  Download report
                </button>
              </div>

              {showJson ? (
                <pre className="json-block">{JSON.stringify(report, null, 2)}</pre>
              ) : (
                <p className="empty-copy">
                  Hidden by default so the report stays easy to read.
                </p>
              )}
            </section>
          </>
        ) : (
          <section className="panel empty-panel">
            <PanelHeader
              label="Ready for analysis"
              title="Run a pipeline investigation"
              meta="The report will combine Python classification, Spring Boot context, Groq reasoning, and a clean frontend summary."
            />
            <p className="empty-copy">
              Enter a project ID and pipeline ID to get a human-readable failure report with the
              root cause, fix guidance, and the changed files that matter.
            </p>
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

function StripCard({ label, value, mono }) {
  return (
    <div className="strip-card">
      <span className="strip-label">{label}</span>
      <span className={`strip-value ${mono ? "mono" : ""}`}>{value}</span>
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
            <div className="file-row" key={`${file.path}-${file.category || "file"}`}>
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

function PriorityFixList({ fixes }) {
  return fixes.length ? (
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

function formatDate(value) {
  if (!value) {
    return "Unavailable";
  }

  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function buildCopilotAnalysis(report) {
  if (!report) {
    return null;
  }

  const metadata = safeMap(report.reportMetadata);
  const primaryFailure = safeMap(report.primaryFailureAnalysis || findPrimaryFailureAnalysis(report));
  const commitAnalysis = safeMap(report.commitAnalysis);
  const pipelineSummary = safeMap(report.pipelineSummary);
  const pipelineOverview = safeMap(report.pipelineOverview);
  const operationalInsights = safeMap(report.operationalInsights);
  const recentPatterns = safeMap(report.recentFailurePatterns);
  const preprocessedLogs = safeMap(primaryFailure.preprocessedLogs);

  return {
    reportMetadata: {
      reportId: metadata.reportId || report.reportId,
      generatedAt: metadata.generatedAt,
      analysisEngine: metadata.analysisEngine,
      aiProvider: metadata.aiProvider,
      aiModel: metadata.aiModel,
      aiEnabled: metadata.aiEnabled,
    },
    pipeline: {
      projectId: report.projectId,
      pipelineId: report.pipelineId,
      analysisMode: report.analysisMode,
      summary: {
        totalJobs: pipelineSummary.totalJobs,
        failedJobs: pipelineSummary.failedJobs,
        passedJobs: pipelineSummary.passedJobs,
        failureRatePercent: pipelineSummary.failureRatePercent,
      },
      overview: {
        projectPath: pipelineOverview.projectPath,
        pipelineRef: pipelineOverview.pipelineRef,
        pipelineStatus: pipelineOverview.pipelineStatus,
        pipelineSource: pipelineOverview.pipelineSource,
        pipelineDuration: pipelineOverview.pipelineDuration,
        failureBlastRadius: pipelineOverview.failureBlastRadius,
      },
      operations: {
        releaseReadiness: operationalInsights.releaseReadiness,
        riskLevel: operationalInsights.riskLevel,
        actionPriority: operationalInsights.actionPriority,
      },
    },
    failure: {
      failureType:
        primaryFailure.failureType ||
        primaryFailure.errorTypeDisplay ||
        primaryFailure.errorType ||
        primaryFailure.category,
      category: primaryFailure.category,
      rootCause: primaryFailure.rootCause,
      whatIsWrong: primaryFailure.whatIsWrong,
      fixRecommendation: primaryFailure.fixRecommendation,
      nextBestAction: primaryFailure.nextBestAction,
      errorMessage: primaryFailure.errorMessage,
      file: primaryFailure.file,
      line: primaryFailure.line,
      column: primaryFailure.column,
      tool: primaryFailure.tool,
      confidence: primaryFailure.confidence,
      severity: primaryFailure.severity,
      recommendedOwner: primaryFailure.recommendedOwner,
      signals: asArray(primaryFailure.signals).slice(0, 8),
      details: asArray(primaryFailure.details).slice(0, 8),
      logHighlights: asArray(primaryFailure.logHighlights).slice(0, 8),
      preprocessedLogs: {
        summary: preprocessedLogs.summary,
        firstSignalLine: preprocessedLogs.firstSignalLine,
        signalCount: preprocessedLogs.signalCount,
        highSignalLines: asArray(preprocessedLogs.highSignalLines).slice(0, 8),
        contextWindow: asArray(preprocessedLogs.contextWindow).slice(0, 8),
        cleanedLogExcerpt: truncateText(asString(preprocessedLogs.cleanedLogExcerpt), 4000),
      },
    },
    commit: {
      commitSha: report.pipelineCommit || commitAnalysis.commitSha,
      commitTitle: commitAnalysis.commitTitle,
      commitAuthor: commitAnalysis.commitAuthor,
      causalAssessment: commitAnalysis.causalAssessment,
      smartFileCorrelation: commitAnalysis.smartFileCorrelation,
      causationType: commitAnalysis.causationType,
      causationConfidence: commitAnalysis.causationConfidence,
      changedFiles: asArray(commitAnalysis.changedFiles).slice(0, 8).map((file) => ({
        path: typeof file === "string" ? file : file.path,
        category: typeof file === "string" ? "" : file.category,
        changeType: typeof file === "string" ? "" : file.changeType,
      })),
      likelyRelatedFiles: asArray(commitAnalysis.likelyRelatedFiles).slice(0, 8).map((file) => ({
        path: typeof file === "string" ? file : file.path,
        category: typeof file === "string" ? "" : file.category,
        changeType: typeof file === "string" ? "" : file.changeType,
      })),
    },
    trend: {
      recurrenceSummary: recentPatterns.recurrenceSummary,
      recentFailureRatePercent: recentPatterns.recentFailureRatePercent,
      recurringSignal: recentPatterns.recurringSignal,
      sameSignatureCount: recentPatterns.sameSignatureCount,
      sameFingerprintCount: recentPatterns.sameFingerprintCount,
    },
  };
}

function safeMap(value) {
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return value;
  }

  return {};
}

function asString(value) {
  if (value === null || value === undefined) {
    return "";
  }

  return String(value);
}

function truncateText(value, maxChars) {
  if (!value || value.length <= maxChars) {
    return value || "";
  }

  return `${value.slice(0, maxChars - 1)}…`;
}
