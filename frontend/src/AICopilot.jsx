import { useEffect, useMemo, useRef, useState } from "react";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

const QUICK_ACTIONS = [
  {
    label: "Explain Failure",
    question: "Explain this failure in simple terms. Why did the pipeline fail?",
  },
  {
    label: "Fix This Error",
    question: "What is the fastest safe fix for this pipeline failure?",
  },
  {
    label: "Is This My Commit?",
    question: "Is this commit likely responsible for the failure? Explain why or why not.",
  },
  {
    label: "Show Fix Code",
    question: "Show me a practical code change or patch to fix this failure.",
  },
];

export default function AICopilot({ analysis, analysisKey }) {
  const starterMessage = useMemo(() => buildStarterMessage(analysis), [analysis]);
  const [messages, setMessages] = useState(() => [starterMessage]);
  const [question, setQuestion] = useState("");
  const [loading, setLoading] = useState(false);
  const streamRef = useRef(null);

  useEffect(() => {
    setMessages([starterMessage]);
    setQuestion("");
    setLoading(false);
  }, [analysisKey, starterMessage]);

  useEffect(() => {
    if (streamRef.current) {
      streamRef.current.scrollTop = streamRef.current.scrollHeight;
    }
  }, [messages, loading]);

  async function sendQuestion(rawQuestion) {
    const nextQuestion = rawQuestion.trim();

    if (!nextQuestion || loading) {
      return;
    }

    setLoading(true);
    setQuestion("");
    setMessages((current) => [
      ...current,
      {
        id: createMessageId(),
        role: "user",
        content: nextQuestion,
      },
    ]);

    try {
      const response = await fetch(`${API_BASE_URL}/api/chat`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          question: nextQuestion,
          analysis,
        }),
      });

      const payload = response.headers.get("content-type")?.includes("application/json")
        ? await response.json()
        : { message: await response.text() };

      if (!response.ok) {
        throw new Error(payload?.message || payload?.error || `Request failed with ${response.status}`);
      }

      setMessages((current) => [
        ...current,
        {
          id: createMessageId(),
          role: "assistant",
          content: payload.answer || "No response was returned.",
          codeSnippet: payload.codeSnippet || "",
          followUp: payload.followUp || "",
          confidence: payload.confidence || "",
        },
      ]);
    } catch (error) {
      setMessages((current) => [
        ...current,
        {
          id: createMessageId(),
          role: "assistant",
          content:
            error?.message ||
            "I could not reach the AI assistant right now. Please try again in a moment.",
          codeSnippet: "",
          followUp: "",
          confidence: "",
        },
      ]);
    } finally {
      setLoading(false);
    }
  }

  function handleSubmit(event) {
    event.preventDefault();
    void sendQuestion(question);
  }

  const failureType = analysis?.failure?.failureType || analysis?.failure?.category || "pipeline failure";
  const commitLabel = analysis?.commit?.commitTitle || analysis?.commit?.commitSha || "current commit";
  const fileLabel = analysis?.failure?.file
    ? `${analysis.failure.file}${analysis.failure.line ? `:${analysis.failure.line}` : ""}`
    : "unknown file";

  return (
    <section className="panel copilot-panel">
      <div className="panel-heading">
        <div>
          <p className="panel-label">AI Assistant</p>
          <h2>🤖 AI Copilot</h2>
        </div>
        <span className="panel-meta">Groq-powered Q&A for the repo, pipeline, and general questions</span>
      </div>

      <p className="copilot-context">
        Ask about <strong>{failureType}</strong>, <strong>{commitLabel}</strong>,{" "}
        <strong>{fileLabel}</strong>, or anything else you want to know.
      </p>

      <div className="copilot-quick-actions">
        {QUICK_ACTIONS.map((action) => (
          <button
            className="button-secondary copilot-quick-button"
            key={action.label}
            onClick={() => void sendQuestion(action.question)}
            disabled={loading}
            type="button"
          >
            {action.label}
          </button>
        ))}
      </div>

      <div className="copilot-stream" ref={streamRef} aria-live="polite">
        {messages.map((message) => (
          <div className={`copilot-message ${message.role}`} key={message.id}>
            <div className="copilot-meta-row">
              <span className="copilot-role">{message.role === "user" ? "You" : "Groq AI"}</span>
              {message.confidence ? <span className="mini-pill">{message.confidence}</span> : null}
            </div>
            <div className="copilot-bubble">
              <p className="copilot-text">{message.content}</p>
              {message.codeSnippet ? <pre className="copilot-code">{message.codeSnippet}</pre> : null}
              {message.followUp ? <p className="copilot-followup">{message.followUp}</p> : null}
            </div>
          </div>
        ))}

        {loading ? (
          <div className="copilot-message assistant">
            <div className="copilot-meta-row">
              <span className="copilot-role">Groq AI</span>
              <span className="mini-pill">Thinking</span>
            </div>
            <div className="copilot-bubble">
              <p className="copilot-text">
                AI is thinking...
                <span className="copilot-dots" aria-hidden="true">
                  <span />
                  <span />
                  <span />
                </span>
              </p>
            </div>
          </div>
        ) : null}
      </div>

      <form className="copilot-composer" onSubmit={handleSubmit}>
        <label className="copilot-input-wrap">
          <span className="sr-only">Ask the AI copilot</span>
          <input
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            placeholder="Ask anything about the repo, pipeline, or project..."
            disabled={loading}
          />
        </label>
        <button className="button-primary copilot-send" type="submit" disabled={loading || !question.trim()}>
          {loading ? "Sending..." : "Send"}
        </button>
      </form>
    </section>
  );
}

function buildStarterMessage(analysis) {
  const failureType = analysis?.failure?.failureType || analysis?.failure?.category || "pipeline failure";
  const rootCause = analysis?.failure?.rootCause || "the root cause is still being refined";

  return {
    id: createMessageId(),
    role: "assistant",
    content:
      `I’m ready to help with ${failureType}. ` +
      `Ask me to explain why it failed, whether the commit is responsible, how to fix it, or any other question. ` +
      `Current summary: ${rootCause}.`,
    codeSnippet: "",
    followUp: "",
    confidence: "",
  };
}

function createMessageId() {
  return `msg-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
