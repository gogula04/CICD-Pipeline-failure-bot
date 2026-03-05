import { useMemo, useState } from "react";

export default function App() {
  const [projectId, setProjectId] = useState("3367");
  const [pipelineId, setPipelineId] = useState("72684");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [data, setData] = useState(null);
  const [showJson, setShowJson] = useState(false);

  const summary = data?.pipelineSummary;
  const intel = data?.pipelineIntelligence;
  const primary = intel?.primaryFailure;
  const downstream = intel?.downstreamImpactedJobs || [];
  const chain = intel?.dependencyChain || [];
  const jobAnalyses = data?.jobAnalyses || [];

  const primaryFailureAnalysis = useMemo(() => {
    // your backend puts full analysis inside jobAnalyses[0].failureAnalysis
    // but we’ll find the analysis matching the primary jobId if possible
    if (!primary?.jobId) return null;

    const match = jobAnalyses.find((j) => j.jobId === primary.jobId);
    return match?.failureAnalysis || null;
  }, [primary?.jobId, jobAnalyses]);

  async function analyze() {
    setError("");
    setData(null);
    setLoading(true);

    try {
      const url = new URL("http://localhost:8080/api/gitlab/analyzePipelineFully");
      url.searchParams.set("projectId", projectId.trim());
      url.searchParams.set("pipelineId", pipelineId.trim());

      const res = await fetch(url.toString());
      if (!res.ok) {
        const text = await res.text();
        throw new Error(`API failed (${res.status}): ${text}`);
      }

      const json = await res.json();
      if (json?.error) throw new Error(json.error);

      setData(json);
    } catch (e) {
      setError(e?.message || "Unknown error");
    } finally {
      setLoading(false);
    }
  }

  async function copyJson() {
    if (!data) return;
    await navigator.clipboard.writeText(JSON.stringify(data, null, 2));
    alert("Copied JSON to clipboard");
  }

  return (
    <div style={styles.page}>
      <div style={styles.header}>
        <div>
          <h1 style={styles.title}>CI/CD Pipeline Failure Intelligence</h1>
          <p style={styles.subtitle}>
            Enter a GitLab Project ID + Pipeline ID to generate a structured failure report.
          </p>
        </div>
      </div>

      <div style={styles.card}>
        <div style={styles.formRow}>
          <div style={styles.inputGroup}>
            <label style={styles.label}>Project ID</label>
            <input
              style={styles.input}
              value={projectId}
              onChange={(e) => setProjectId(e.target.value)}
              placeholder="e.g. 3367"
            />
          </div>

          <div style={styles.inputGroup}>
            <label style={styles.label}>Pipeline ID</label>
            <input
              style={styles.input}
              value={pipelineId}
              onChange={(e) => setPipelineId(e.target.value)}
              placeholder="e.g. 72684"
            />
          </div>

          <button
            style={{ ...styles.button, opacity: loading ? 0.7 : 1 }}
            onClick={analyze}
            disabled={loading}
          >
            {loading ? "Analyzing..." : "Analyze"}
          </button>

          <button
            style={{ ...styles.buttonSecondary, opacity: data ? 1 : 0.6 }}
            onClick={copyJson}
            disabled={!data}
          >
            Copy JSON
          </button>

          <button
            style={{ ...styles.buttonSecondary, opacity: data ? 1 : 0.6 }}
            onClick={() => setShowJson((v) => !v)}
            disabled={!data}
          >
            {showJson ? "Hide JSON" : "Show JSON"}
          </button>
        </div>

        {error ? <div style={styles.error}>❌ {error}</div> : null}
      </div>

      {data ? (
        <>
          {/* Summary */}
          <div style={styles.grid}>
            <Stat label="Total Jobs" value={summary?.totalJobs ?? "-"} />
            <Stat label="Failed Jobs" value={summary?.failedJobs ?? "-"} />
            <Stat label="Skipped Jobs" value={summary?.skippedJobs ?? "-"} />
            <Stat label="Failure Rate %" value={fmt(summary?.failureRatePercent)} />
          </div>

          {/* Primary Failure */}
          <div style={styles.card}>
            <h2 style={styles.sectionTitle}>Primary Failure</h2>

            <div style={styles.kvRow}>
              <KV k="Job" v={`${primary?.jobName ?? "-"} (#${primary?.jobId ?? "-"})`} />
              <KV k="Stage" v={primary?.stage ?? "-"} />
              <KV k="Status" v={primary?.status ?? "-"} />
              <KV k="Commit" v={data?.pipelineCommit ?? "-"} />
            </div>

            {primaryFailureAnalysis ? (
              <div style={{ marginTop: 12 }}>
                <div style={styles.badgeRow}>
                  <Badge text={primaryFailureAnalysis.category || "Unknown Category"} />
                  <Badge text={`Confidence: ${primaryFailureAnalysis.confidence || "-"}`} />
                  <Badge text={`Tool: ${primaryFailureAnalysis.tool || "-"}`} />
                </div>

                <div style={styles.block}>
                  <div style={styles.blockTitle}>Root Cause</div>
                  <div style={styles.blockBody}>{primaryFailureAnalysis.rootCause || "-"}</div>
                </div>

                <div style={styles.block}>
                  <div style={styles.blockTitle}>Error Message</div>
                  <div style={styles.mono}>{primaryFailureAnalysis.errorMessage || "-"}</div>
                </div>

                <div style={styles.block}>
                  <div style={styles.blockTitle}>Fix Recommendation</div>
                  <div style={styles.blockBody}>{primaryFailureAnalysis.fixRecommendation || "-"}</div>
                </div>

                <div style={styles.kvRow}>
                  <KV
                    k="File:Line"
                    v={
                      primaryFailureAnalysis.file
                        ? `${primaryFailureAnalysis.file}:${primaryFailureAnalysis.line || "?"}`
                        : "-"
                    }
                  />
                  <KV k="Column" v={primaryFailureAnalysis.column || "-"} />
                  <KV k="Failure Type" v={primaryFailureAnalysis.failureType || "-"} />
                </div>
              </div>
            ) : (
              <div style={{ marginTop: 12, color: "#666" }}>
                No failureAnalysis found for primary failure job.
              </div>
            )}
          </div>

          {/* Dependency / Cascade */}
          <div style={styles.card}>
            <h2 style={styles.sectionTitle}>Cascading Impact</h2>

            <div style={styles.kvRow}>
              <KV k="Earliest failing stage" v={intel?.earliestFailingStageName ?? "-"} />
              <KV k="Stage order" v={(intel?.stageOrder || []).join(" → ") || "-"} />
            </div>

            <div style={styles.block}>
              <div style={styles.blockTitle}>Dependency Chain</div>
              {chain.length === 0 ? (
                <div style={styles.blockBody}>No chain detected.</div>
              ) : (
                <ul style={styles.list}>
                  {chain.map((c, idx) => (
                    <li key={idx} style={styles.listItem}>
                      {c.fromStage} (#{c.fromJobId}) → {c.toStage} (#{c.toJobId})
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div style={styles.block}>
              <div style={styles.blockTitle}>Downstream Impacted Jobs</div>
              {downstream.length === 0 ? (
                <div style={styles.blockBody}>None.</div>
              ) : (
                <table style={styles.table}>
                  <thead>
                    <tr>
                      <th style={styles.th}>Job</th>
                      <th style={styles.th}>Stage</th>
                      <th style={styles.th}>Status</th>
                      <th style={styles.th}>Reason</th>
                    </tr>
                  </thead>
                  <tbody>
                    {downstream.map((j) => (
                      <tr key={j.jobId}>
                        <td style={styles.td}>
                          {j.jobName} (#{j.jobId})
                        </td>
                        <td style={styles.td}>{j.stage}</td>
                        <td style={styles.td}>{j.status}</td>
                        <td style={styles.td}>{j.impactReason}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>

          {/* Job Analyses */}
          <div style={styles.card}>
            <h2 style={styles.sectionTitle}>Job Analyses</h2>

            <table style={styles.table}>
              <thead>
                <tr>
                  <th style={styles.th}>Job</th>
                  <th style={styles.th}>Stage</th>
                  <th style={styles.th}>Status</th>
                  <th style={styles.th}>Category</th>
                  <th style={styles.th}>Confidence</th>
                </tr>
              </thead>
              <tbody>
                {jobAnalyses.map((j) => (
                  <tr key={j.jobId}>
                    <td style={styles.td}>
                      {j.jobName} (#{j.jobId})
                    </td>
                    <td style={styles.td}>{j.stage}</td>
                    <td style={styles.td}>
                      {j.failureAnalysis?.status || (j.jobId === primary?.jobId ? "failed" : "-")}
                    </td>
                    <td style={styles.td}>{j.failureAnalysis?.category || "-"}</td>
                    <td style={styles.td}>{j.failureAnalysis?.confidence || "-"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Raw JSON */}
          {showJson ? (
            <div style={styles.card}>
              <h2 style={styles.sectionTitle}>Raw JSON</h2>
              <pre style={styles.pre}>{JSON.stringify(data, null, 2)}</pre>
            </div>
          ) : null}
        </>
      ) : null}
    </div>
  );
}

function Stat({ label, value }) {
  return (
    <div style={styles.statCard}>
      <div style={styles.statLabel}>{label}</div>
      <div style={styles.statValue}>{value}</div>
    </div>
  );
}

function KV({ k, v }) {
  return (
    <div style={styles.kv}>
      <div style={styles.k}>{k}</div>
      <div style={styles.v}>{v}</div>
    </div>
  );
}

function Badge({ text }) {
  return <span style={styles.badge}>{text}</span>;
}

function fmt(n) {
  if (n === null || n === undefined) return "-";
  const num = Number(n);
  if (Number.isNaN(num)) return "-";
  return num.toFixed(2);
}

const styles = {
  page: { fontFamily: "Arial, sans-serif", padding: 20, maxWidth: 1100, margin: "0 auto" },
  header: { display: "flex", alignItems: "flex-end", justifyContent: "space-between", marginBottom: 16 },
  title: { margin: 0, fontSize: 24 },
  subtitle: { margin: "6px 0 0", color: "#555" },

  card: { background: "#fff", border: "1px solid #e6e6e6", borderRadius: 10, padding: 16, marginBottom: 14 },
  sectionTitle: { margin: "0 0 12px", fontSize: 18 },

  formRow: { display: "flex", gap: 12, flexWrap: "wrap", alignItems: "end" },
  inputGroup: { display: "flex", flexDirection: "column", gap: 6 },
  label: { fontSize: 12, color: "#333" },
  input: { padding: "10px 12px", borderRadius: 8, border: "1px solid #ccc", minWidth: 180 },

  button: { padding: "10px 14px", borderRadius: 8, border: "1px solid #111", background: "#111", color: "#fff", cursor: "pointer" },
  buttonSecondary: { padding: "10px 14px", borderRadius: 8, border: "1px solid #ccc", background: "#f7f7f7", cursor: "pointer" },

  error: { marginTop: 12, color: "#b00020", fontWeight: 600 },

  grid: { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))", gap: 12, marginBottom: 14 },
  statCard: { background: "#fff", border: "1px solid #e6e6e6", borderRadius: 10, padding: 14 },
  statLabel: { fontSize: 12, color: "#666" },
  statValue: { fontSize: 22, fontWeight: 700, marginTop: 6 },

  kvRow: { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))", gap: 12 },
  kv: { border: "1px solid #eee", borderRadius: 10, padding: 12, background: "#fafafa" },
  k: { fontSize: 12, color: "#666" },
  v: { marginTop: 6, fontWeight: 700, wordBreak: "break-word" },

  badgeRow: { display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 10 },
  badge: { fontSize: 12, padding: "6px 10px", borderRadius: 999, background: "#f1f1f1", border: "1px solid #ddd" },

  block: { marginTop: 12, border: "1px solid #eee", borderRadius: 10, padding: 12 },
  blockTitle: { fontSize: 12, color: "#666", marginBottom: 6, fontWeight: 700 },
  blockBody: { color: "#222", lineHeight: 1.4 },
  mono: { fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace", whiteSpace: "pre-wrap" },

  table: { width: "100%", borderCollapse: "collapse", marginTop: 10 },
  th: { textAlign: "left", borderBottom: "1px solid #eee", padding: "10px 8px", fontSize: 12, color: "#666" },
  td: { borderBottom: "1px solid #f0f0f0", padding: "10px 8px", verticalAlign: "top" },

  list: { margin: 0, paddingLeft: 18 },
  listItem: { marginBottom: 6 },

  pre: { background: "#0b1020", color: "#e7e7e7", padding: 12, borderRadius: 10, overflowX: "auto", fontSize: 12 },
};