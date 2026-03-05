from flask import Flask, request, jsonify
import re

app = Flask(__name__)

# -----------------------------
# Helpers: extraction functions
# -----------------------------

ANSI_ESCAPE = re.compile(r"\x1B\[[0-?]*[ -/]*[@-~]")

def strip_ansi(text: str) -> str:
    return ANSI_ESCAPE.sub("", text or "")

def pick_best(matches):
    """Pick the most likely 'root-cause' match (first strong match)."""
    return matches[0] if matches else None

def extract_file_line_col(log: str):
    """
    Tries multiple patterns and returns best guess:
    file, line, column, evidenceLine
    """
    lines = log.splitlines()

    candidates = []

    # Pattern A: Maven duplicate decl: "... @ line 145, column 21"
    pat_mvn_line_col = re.compile(r"@\s*line\s*(\d+)\s*,\s*column\s*(\d+)", re.IGNORECASE)

    # Pattern B: Java compiler format: "SomeFile.java:[23,10]"
    pat_java_brackets = re.compile(r"([A-Za-z0-9_./\\-]+\.java):\[(\d+),(\d+)\]")

    # Pattern C: GCC/Clang style: "file.c:123:45: error:"
    pat_colon_style = re.compile(r"([A-Za-z0-9_./\\-]+\.(c|cc|cpp|h|hpp|java|kt|py|js|ts|go|rs|xml|yml|yaml|gradle)):(\d+):(\d+)")

    # Pattern D: Python traceback: 'File "/path/x.py", line 17'
    pat_py_trace = re.compile(r'File\s+"([^"]+)",\s+line\s+(\d+)', re.IGNORECASE)

    # Pattern E: Generic "line X" mention with file in same line
    pat_generic_file_line = re.compile(r"([A-Za-z0-9_./\\-]+\.(xml|yml|yaml|gradle|java|py|js|ts|c|cpp|hpp|h))\D+line\D+(\d+)", re.IGNORECASE)

    # Pattern F: Java compiler error
    pat_java_error = re.compile(r"([A-Za-z0-9_./\\-]+\.java):(\d+):\s*error:", re.IGNORECASE)

    # Pattern G: C/C++ compiler error
    pat_cpp_error = re.compile(r"([A-Za-z0-9_./\\-]+\.(c|cpp|cc|hpp|h)):(\d+):(\d+):\s*error:", re.IGNORECASE)

    # Pattern H: Python traceback file and line
    pat_py_error = re.compile(r'File\s+"([^"]+\.py)",\s+line\s+(\d+)', re.IGNORECASE)

    # Pattern I: Gradle test failure: "at com.example.MyTest.testSomething(MyTest.java:45)"
    pat_gradle_test = re.compile(r"at\s+[A-Za-z0-9_./\\-]+\(([^:]+):(\d+)\)", re.IGNORECASE)
    # pytest: "E   AssertionError: assert 1 == 2 at /path/test_file.py:17"
    pat_pytest_error = re.compile(r"at\s+([A-Za-z0-9_./\\-]+\.py):(\d+)", re.IGNORECASE)
    #python compile error: "SyntaxError: invalid syntax in /path/file.py at line 10"
    pat_py_syntax_error = re.compile(r"SyntaxError:.*in\s+([A-Za-z0-9_./\\-]+\.py)\s+at\s+line\s+(\d+)", re.IGNORECASE)
    # CMake error: "CMakeLists.txt:10:5: error: ..."
    pat_cmake_error = re.compile(r"([A-Za-z0-9_./\\-]+\.txt):(\d+):(\d+):\s*error:", re.IGNORECASE)
    # Add more patterns as needed for different tools and languages


    for i, raw in enumerate(lines):
        line = raw.strip()

        m = pat_java_brackets.search(line)
        if m:
            candidates.append({
                "file": m.group(1),
                "line": m.group(2),
                "column": m.group(2),
                "evidence": raw
            })
            continue

        m = pat_colon_style.search(line)
        if m:
            candidates.append({
                "file": m.group(1),
                "line": m.group(2),
                "column": m.group(4),
                "evidence": raw
            })
            continue

        m = pat_py_trace.search(line)
        if m:
            candidates.append({
                "file": m.group(1),
                "line": m.group(2),
                "column": None,
                "evidence": raw
            })
            continue

        m = pat_generic_file_line.search(line)
        if m:
            candidates.append({
                "file": m.group(1),
                "line": m.group(2),
                "column": None,
                "evidence": raw
            })
            continue

        # Maven @ line/column needs file separately (often pom.xml appears elsewhere)
        m = pat_mvn_line_col.search(line)
        if m:
            candidates.append({
                "file": None,  # we'll infer later if we see pom.xml
                "line": m.group(1),
                "column": m.group(2),
                "evidence": raw
            })
            continue
        m = pat_java_error.search(line)
        if m:
            candidates.append({
                "file": m.group(1),
                "line": m.group(2),
                "column": None,
                "evidence": raw
            })
            continue
        m = pat_cpp_error.search(line)
        if m:
            candidates.append({
                "file": m.group(1),
                "line": m.group(2),
                "column": m.group(4),
                "evidence": raw
            })
            continue
        m = pat_py_error.search(line)
        if m:
            candidates.append({
                "file": m.group(1),
                "line": m.group(2),
                "column": None,
                "evidence": raw
            })
            continue
        m = pat_gradle_test.search(line)
        if m:
            candidates.append({
                "file": m.group(1),
                "line": m.group(2),
                "column": None,
                "evidence": raw
            })
            continue
        m = pat_pytest_error.search(line)
        if m:
            candidates.append({
                "file": m.group(1),
                "line": m.group(2),
                "column": None,
                "evidence": raw
            })
            continue
        m = pat_py_syntax_error.search(line)
        if m:
            candidates.append({
                "file": m.group(1),
                "line": m.group(2),
                "column": None,
                "evidence": raw
            })
            continue
        m = pat_cmake_error.search(line)
        if m:
            candidates.append({
                "file": m.group(1),
                "line": m.group(2),
                "column": m.group(2),
                "evidence": raw
            })
            continue        

    best = pick_best(candidates)

    if not best:
        return None, None, None, None

    # Infer file for Maven dependency issues if pom.xml appears anywhere
    if best["file"] is None:
        log_lower = log.lower()
        if "pom.xml" in log_lower or "maven" in log_lower or "dependency" in log_lower:
            best["file"] = "pom.xml"
            best["path"] = "/pom.xml"
        elif "build.gradle" in log_lower or "gradle" in log_lower:
            best["file"] = "build.gradle"
        elif ".gitlab-ci.yml" in log_lower:
             best["file"] = ".gitlab-ci.yml"
        elif "cmakelists.txt" in log_lower or "cmake" in log_lower:
            best["file"] = "CMakeLists.txt"
        elif "makefile" in log_lower:
            best["file"] = "Makefile"
        elif "build.gradle" in log:
            best["file"] = "build.gradle"
        elif ".gitlab-ci.yml" in log:
            best["file"] = ".gitlab-ci.yml"

    return best["file"], best["line"], best["column"], best["evidence"]

def extract_error_snippets(log: str, max_lines=6):
    """
    Returns a small list of the most relevant error lines.
    """
    lines = log.splitlines()
    hits = []

    patterns = [
        re.compile(r"\bERROR\b", re.IGNORECASE),
        re.compile(r"\bFAIL(?:ED|URE|URES)?\b", re.IGNORECASE),
        re.compile(r"\bexception\b", re.IGNORECASE),
        re.compile(r"\bnot found\b", re.IGNORECASE),
        re.compile(r"\bcannot find symbol\b", re.IGNORECASE),
        re.compile(r"\bcompilation failure\b", re.IGNORECASE),
        re.compile(r"\bduplicate declaration\b", re.IGNORECASE),
        re.compile(r"\bmust be unique\b", re.IGNORECASE),
        re.compile(r"\bpermission denied\b", re.IGNORECASE),
        re.compile(r"\bconnection (refused|timed out)\b", re.IGNORECASE),
    ]

    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        if any(p.search(line) for p in patterns):
            hits.append(raw)
        if len(hits) >= max_lines:
            break

    return hits

# -----------------------------
# Classification + Fix templates
# -----------------------------

def classify_and_fix(log: str):
    """
    Classify failure using patterns and generate fix using extracted file/line/col.
    """
    file, line, col, locationEvidence = extract_file_line_col(log)
    snippets = extract_error_snippets(log)

    text = log.lower()

    # Tool detection
    tool = "Unknown"
    if "mvn " in text or "maven" in text or "pom.xml" in text:
        tool = "Maven"
    elif "gradle" in text or "build.gradle" in text:
        tool = "Gradle"
    elif "cmake" in text or "cmakelists.txt" in text:
        tool = "CMake"
    elif ".gitlab-ci.yml" in text:
        tool = "GitLab CI"
    elif "pytest" in text:
        tool = "PyTest"
    elif "npm" in text or "node" in text:
        tool = "Node/NPM"

    # 1) Dependency / build config failures
    if ("duplicate declaration" in text) or ("must be unique" in text) or ("dependency" in text and "duplicate" in text):
        failureType = "Dependency Conflict"
        category = "Build Configuration Failure"
        rootCause = "Duplicate or conflicting dependency declaration detected"

        where = ""
        if file or line:
            where = f" (at {file or 'unknown file'}{':' + str(line) if line else ''}{':' + str(col) if col else ''})"

        fix = "Remove duplicate dependency declaration"
        if file and line:
            fix += f" near line {line} in {file}"
        elif file:
            fix += f" in {file}"
        fix += ". Ensure only one version of each dependency is declared."

        return {
            "failureType": failureType,
            "category": category,
            "tool": tool,
            "file": file,
            "line": line,
            "column": col,
            "errorMessage": snippets[0].strip() if snippets else "Dependency conflict detected",
            "rootCause": rootCause + where,
            "fixRecommendation": fix,
            "confidence": "HIGH" if file or line else "MEDIUM",
            "evidence": {
                "locationLine": locationEvidence,
                "snippets": snippets
            }
        }

    # 2) Compilation failures
    if ("compilation failure" in text) or ("cannot find symbol" in text) or re.search(r"\berror:\b", text):
        failureType = "Compilation Failure"
        category = "Code Failure"
        rootCause = "Compiler error detected (syntax, missing symbol, or type mismatch)"

        fix = "Open the first compiler error and fix it; later errors often cascade."
        if file and line:
            fix = f"Fix compilation error in {file} at/near line {line}. Start with the first 'error:' line."
        elif file:
            fix = f"Fix compilation error in {file}. Start with the first 'error:' line."

        return {
            "failureType": failureType,
            "category": category,
            "tool": tool,
            "file": file,
            "line": line,
            "column": col,
            "errorMessage": snippets[0].strip() if snippets else "Compilation error detected",
            "rootCause": rootCause,
            "fixRecommendation": fix,
            "confidence": "HIGH" if snippets else "MEDIUM",
            "evidence": {
                "locationLine": locationEvidence,
                "snippets": snippets
            }
        }

    # 3) Test failures
    if ("there are test failures" in text) or ("failures!!!" in text) or ("test failed" in text) or ("failed tests" in text):
        failureType = "Test Failure"
        category = "Test Failure"
        rootCause = "One or more automated tests failed"

        fix = "Find the first failing test name in the log and run it locally to reproduce."
        return {
            "failureType": failureType,
            "category": category,
            "tool": tool,
            "file": file,
            "line": line,
            "column": col,
            "errorMessage": snippets[0].strip() if snippets else "Test failures detected",
            "rootCause": rootCause,
            "fixRecommendation": fix,
            "confidence": "MEDIUM",
            "evidence": {
                "locationLine": locationEvidence,
                "snippets": snippets
            }
        }

    # 4) Environment / infra failures
    if ("connection refused" in text) or ("timed out" in text) or ("runner system failure" in text) or ("no space left" in text):
        return {
            "failureType": "Infrastructure/Environment Failure",
            "category": "Environment / Infrastructure",
            "tool": tool,
            "file": file,
            "line": line,
            "column": col,
            "errorMessage": snippets[0].strip() if snippets else "Infra/env error detected",
            "rootCause": "CI runner/environment issue (network, disk, permissions, or runner instability)",
            "fixRecommendation": "Retry pipeline; if repeatable, check runner health, disk space, network access, and permissions.",
            "confidence": "MEDIUM",
            "evidence": {
                "locationLine": locationEvidence,
                "snippets": snippets
            }
        }
    
    if re.search(r"\berror:\b", text) and file:
        failureType = "Compilation Failure"
        category = "Code Failure"
        where = f"{file}"
        if line:
            where += f":{line}"
        if col:
            where += f":{col}"
        fix = f"Open {file}"
        if line:
            fix += f" at line {line}"
        fix += " and fix the compiler error. Start with the first 'error:' in log."
        return {
            "failureType": failureType,
            "category": category,
            "tool": tool,
            "file": file,
            "line": line,
            "column": col,
            "errorMessage": snippets[0].strip() if snippets else f"Compilation error in {where}",
            "rootCause": "Compiler error detected (syntax, missing symbol, or type mismatch)",
            "fixRecommendation": fix,
            "confidence": "HIGH",
            "evidence": {
                "locationLine": locationEvidence,
                "snippets": snippets
            }
        }

    # Default
    return {
        "failureType": "Unknown",
        "category": "Unknown",
        "tool": tool,
        "file": file,
        "line": line,
        "column": col,
        "errorMessage": snippets[0].strip() if snippets else "No strong failure pattern matched",
        "rootCause": "Could not confidently classify from log patterns",
        "fixRecommendation": "Provide more log context or add new pattern rules for this failure signature.",
        "confidence": "LOW",
        "evidence": {
            "locationLine": locationEvidence,
            "snippets": snippets
        }
    }
    
    


# -----------------------------
# Flask endpoint
# -----------------------------

@app.route("/analyze", methods=["POST"])
def analyze():
    data = request.get_json(force=True) or {}
    raw_log = data.get("log", "")
    log = strip_ansi(raw_log)

    result = classify_and_fix(log)
    return jsonify(result)

if __name__ == "__main__":
    app.run(port=5000)

@app.route("/analyze", methods=["POST"])
def analyze():
    data = request.get_json(force=True) or {}

    raw_log = data.get("log", "")

    print("LOG LENGTH:", len(raw_log))
    print("LOG SAMPLE:", raw_log[:500])

    log = strip_ansi(raw_log)

    result = classify_and_fix(log)
    return jsonify(result)

@app.route("/analyze", methods=["POST"])
def analyze():

    data = request.get_json(force=True)

    log = data.get("log", "")

    print("LOG RECEIVED LENGTH:", len(log))
    print("FIRST 300 CHARS:")
    print(log[:300])

    result = classify_and_fix(log)

    return jsonify(result)