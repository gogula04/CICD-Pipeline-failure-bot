from flask import Flask, jsonify, request
from functools import lru_cache
from pathlib import Path
import hashlib
import json
import re

app = Flask(__name__)

ANSI_ESCAPE = re.compile(r"\x1B\[[0-?]*[ -/]*[@-~]")
SIGNATURE_CATALOG_PATH = Path(__file__).with_name("signatures.json")
REQUIREMENT_ID_PATTERN = re.compile(r"\b[A-Z]{2,10}-[A-Z]{2,10}-\d+\b")
DD_TOKEN_PATTERN = re.compile(r"\bdd_[A-Za-z0-9_]+\b")
SQL_ERROR_CODE_PATTERN = re.compile(r"\b(?:sql\s*error|error)\s*[:#]?\s*(\d{3,5})\b", re.IGNORECASE)
SQL_STATE_PATTERN = re.compile(r"\bsqlstate\s*[:=]?\s*([0-9A-Z]{5})\b", re.IGNORECASE)
DUPLICATE_ENTRY_PATTERN = re.compile(
    r"duplicate entry\s+['\"]?([^'\"]+)['\"]?\s+for key\s+['\"]?([^'\"]+)['\"]?",
    re.IGNORECASE,
)
UNIQUE_CONSTRAINT_PATTERN = re.compile(
    r"violates unique constraint\s+['\"]?([^'\"]+)['\"]?",
    re.IGNORECASE,
)

HIGHLIGHT_PATTERNS = [
    re.compile(r"\berror\b", re.IGNORECASE),
    re.compile(r"\bfail(?:ed|ure|ures)?\b", re.IGNORECASE),
    re.compile(r"\bexception\b", re.IGNORECASE),
    re.compile(r"\bnot found\b", re.IGNORECASE),
    re.compile(r"\bpermission denied\b", re.IGNORECASE),
    re.compile(r"\bconnection (refused|timed out|reset)\b", re.IGNORECASE),
    re.compile(r"\btimeout\b", re.IGNORECASE),
    re.compile(r"\bmust be unique\b", re.IGNORECASE),
    re.compile(r"\bduplicate declaration\b", re.IGNORECASE),
    re.compile(r"\brunner system failure\b", re.IGNORECASE),
    re.compile(r"\bsegmentation fault\b", re.IGNORECASE),
    re.compile(r"\bno space left on device\b", re.IGNORECASE),
    re.compile(r"\bimagepullbackoff\b", re.IGNORECASE),
]

WARNING_PATTERN = re.compile(r"^\s*\[warning\]|\bwarning:", re.IGNORECASE)
ROOT_STOP_PATTERN = re.compile(
    r"\[error\]|build failure|compilation failure|cannot find symbol|undefined reference|exception|test failed|there are test failures|assertion(?:error| failed)?|nullpointer|segmentation fault|failed to execute goal",
    re.IGNORECASE,
)


ERROR_TYPE_TO_CATEGORY = {
    "COMPILATION_ERROR": "Code Compilation Failure",
    "TEST_FAILURE": "Test Failure",
    "RUNTIME_ERROR": "Code Compilation Failure",
    "BUILD_CONFIG_ERROR": "Build Configuration Failure",
    "ENVIRONMENT_ERROR": "Environment Failure",
}


@lru_cache(maxsize=1)
def load_signatures():
    with SIGNATURE_CATALOG_PATH.open("r", encoding="utf-8") as handle:
        signatures = json.load(handle)

    return sorted(signatures, key=lambda item: item.get("priority", 0), reverse=True)


def strip_ansi(text: str) -> str:
    return ANSI_ESCAPE.sub("", text or "")


def extract_file_line_col(log: str):
    lines = log.splitlines()
    candidates = []

    patterns = [
        re.compile(r"([A-Za-z0-9_./\\-]+\.java):\[(\d+),(\d+)\]"),
        re.compile(r"([A-Za-z0-9_./\\-]+\.(?:c|cc|cpp|h|hpp|java|kt|py|js|ts|tsx|go|rs|xml|yml|yaml|gradle|toml|txt|json|sh)):(\d+):(\d+)"),
        re.compile(r"([A-Za-z0-9_./\\-]+\.(?:java|kt|py|js|ts|tsx|go|rs|xml|yml|yaml|gradle|toml|txt|json|sh)):(\d+):\s*error", re.IGNORECASE),
        re.compile(r'File\s+"([^"]+\.py)",\s+line\s+(\d+)', re.IGNORECASE),
        re.compile(r"([A-Za-z0-9_./\\-]+\.(?:xml|yml|yaml|gradle|java|py|js|ts|tsx|c|cpp|hpp|h|txt|json|toml|sh))\D+line\D+(\d+)", re.IGNORECASE),
        re.compile(r"@\s*line\s*(\d+)\s*,\s*column\s*(\d+)", re.IGNORECASE),
        re.compile(r"at\s+[A-Za-z0-9_.$/\\-]+\(([^:]+):(\d+)\)", re.IGNORECASE),
        re.compile(r"([A-Za-z0-9_./\\-]+\.(?:xml|ya?ml|json|toml))\s*[:@]\s*line\s*(\d+)", re.IGNORECASE),
    ]

    for raw in lines:
        line = raw.strip()
        if not line:
            continue

        for pattern in patterns:
            match = pattern.search(line)
            if not match:
                continue

            groups = match.groups()

            if len(groups) == 3:
                candidates.append(
                    {
                        "file": groups[0],
                        "line": groups[1],
                        "column": groups[2],
                        "evidence": raw.strip(),
                    }
                )
            elif len(groups) == 2 and groups[0].isdigit():
                candidates.append(
                    {
                        "file": None,
                        "line": groups[0],
                        "column": groups[1],
                        "evidence": raw.strip(),
                    }
                )
            elif len(groups) == 2:
                candidates.append(
                    {
                        "file": groups[0],
                        "line": groups[1],
                        "column": None,
                        "evidence": raw.strip(),
                    }
                )

            break

    best = candidates[0] if candidates else {"file": None, "line": None, "column": None, "evidence": None}

    if best["file"] is None:
        lower = log.lower()

        if ".gitlab-ci.yml" in lower:
            best["file"] = ".gitlab-ci.yml"
        elif "pom.xml" in lower or "maven" in lower:
            best["file"] = "pom.xml"
        elif "build.gradle" in lower or "gradle" in lower:
            best["file"] = "build.gradle"
        elif "cmakelists.txt" in lower or "cmake" in lower:
            best["file"] = "CMakeLists.txt"
        elif "makefile" in lower:
            best["file"] = "Makefile"
        elif "dockerfile" in lower:
            best["file"] = "Dockerfile"
        elif "package.json" in lower:
            best["file"] = "package.json"
        elif "requirements.txt" in lower:
            best["file"] = "requirements.txt"
        elif "pyproject.toml" in lower:
            best["file"] = "pyproject.toml"

    return best["file"], best["line"], best["column"], best["evidence"]


def extract_error_snippets(log: str, max_lines: int = 8):
    hits = []

    for raw in log.splitlines():
        line = raw.strip()

        if not line:
            continue

        if any(pattern.search(line) for pattern in HIGHLIGHT_PATTERNS):
            hits.append(line)

        if len(hits) >= max_lines:
            break

    return hits


def is_warning_line(line: str) -> bool:
    return bool(WARNING_PATTERN.search(line or ""))


def failure_specificity_score(line: str) -> int:
    lower = (line or "").lower()

    if not lower or is_warning_line(line):
        return 0

    score = 0

    if "[error]" in lower:
        score += 25

    if "cannot find symbol" in lower or "package " in lower and "does not exist" in lower:
        return score + 100
    if "undefined reference" in lower:
        return score + 95
    if "compilation failure" in lower or " error:" in lower:
        return score + 90
    if "assertionerror" in lower or "assertion failed" in lower or "there are test failures" in lower or "test failed" in lower:
        return score + 88
    if "nullpointerexception" in lower or "segmentation fault" in lower or "exception" in lower:
        return score + 84
    if "build failure" in lower or "failed to execute goal" in lower:
        return score + 75
    if "permission denied" in lower or "command not found" in lower or "environment variable" in lower:
        return score + 72
    if "[error]" in lower:
        return score + 60

    return 0


def find_root_failure(log: str):
    lines = log.splitlines()

    for idx, raw in enumerate(lines):
        line = raw.strip()

        if not line or is_warning_line(line):
            continue

        if not ROOT_STOP_PATTERN.search(line):
            continue

        best_idx = idx
        best_line = line
        best_score = failure_specificity_score(line)

        for candidate_idx in range(idx, min(len(lines), idx + 8)):
            candidate = lines[candidate_idx].strip()

            if not candidate or is_warning_line(candidate):
                continue

            candidate_score = failure_specificity_score(candidate)

            if candidate_score > best_score:
                best_idx = candidate_idx
                best_line = candidate
                best_score = candidate_score

        context_lines = [
            lines[candidate_idx].strip()
            for candidate_idx in range(idx, min(len(lines), best_idx + 6))
            if lines[candidate_idx].strip()
        ]

        return {
            "startIndex": idx,
            "rootIndex": best_idx,
            "starterLine": line,
            "rootLine": best_line,
            "contextLines": context_lines,
        }

    fallback_line = next((line.strip() for line in lines if line.strip()), "")
    return {
        "startIndex": 0,
        "rootIndex": 0,
        "starterLine": fallback_line,
        "rootLine": fallback_line,
        "contextLines": [fallback_line] if fallback_line else [],
    }


def detect_tool(text: str, matched_signature: dict | None) -> str:
    if matched_signature and matched_signature.get("tool"):
        return matched_signature["tool"]

    if any(token in text for token in ["sqlstate", "duplicate entry", "unique constraint", "integrity constraint", "jdbc", "sql error"]):
        return "Database"
    if "maven" in text or "pom.xml" in text or "mvn " in text:
        return "Maven"
    if "gradle" in text or "build.gradle" in text:
        return "Gradle"
    if "pytest" in text or ".py" in text or "python" in text:
        return "Python"
    if "npm" in text or "node" in text or "yarn" in text or "pnpm" in text:
        return "Node"
    if ".gitlab-ci.yml" in text or "gitlab" in text:
        return "GitLab CI"
    if "cmake" in text or "cmakelists.txt" in text:
        return "CMake"
    if "terraform" in text:
        return "Terraform"
    if "helm" in text:
        return "Helm"
    if "docker" in text:
        return "Docker"
    if "kubernetes" in text or "kubectl" in text:
        return "Kubernetes"
    return "Unknown"


def extract_class_or_function(text: str):
    patterns = [
        re.compile(r"location:\s+class\s+([A-Za-z0-9_$.]+)", re.IGNORECASE),
        re.compile(r"location:\s+interface\s+([A-Za-z0-9_$.]+)", re.IGNORECASE),
        re.compile(r"symbol:\s+method\s+([A-Za-z0-9_$.<>]+)", re.IGNORECASE),
        re.compile(r"symbol:\s+class\s+([A-Za-z0-9_$.<>]+)", re.IGNORECASE),
        re.compile(r"at\s+([A-Za-z0-9_$.]+)\(", re.IGNORECASE),
        re.compile(r"\bfunction\s+([A-Za-z0-9_$.]+)", re.IGNORECASE),
    ]

    for pattern in patterns:
        match = pattern.search(text or "")
        if match:
            return match.group(1)

    return None


def extract_symbol_details(text: str):
    details = extract_missing_symbol_details(text)
    return details[0] if details else None


def extract_missing_symbol_details(text: str):
    patterns = [
        re.compile(r"cannot find symbol\s*:?\s*(class|method|variable)\s+([A-Za-z0-9_$.<>]+)", re.IGNORECASE),
        re.compile(r"symbol:\s+(class|method|variable)\s+([A-Za-z0-9_$.<>]+)", re.IGNORECASE),
        re.compile(r"location:\s+package\s+([A-Za-z0-9_.]+)", re.IGNORECASE),
        re.compile(r"package\s+([A-Za-z0-9_.]+)\s+does not exist", re.IGNORECASE),
        re.compile(r"undefined reference to [`']?([A-Za-z0-9_$.<>:]+)", re.IGNORECASE),
        re.compile(r"No module named ['\"]?([A-Za-z0-9_$.]+)", re.IGNORECASE),
        re.compile(r"dependency\s+([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)", re.IGNORECASE),
    ]

    details = []
    seen = set()

    for pattern in patterns:
        for match in pattern.finditer(text or ""):
            groups = match.groups()

            if len(groups) == 2:
                kind, name = groups
            elif "location:\s+package" in pattern.pattern:
                kind, name = "package", groups[0]
            elif "package" in pattern.pattern:
                kind, name = "package", groups[0]
            elif "undefined reference" in pattern.pattern:
                kind, name = "symbol", groups[0]
            elif "No module named" in pattern.pattern:
                kind, name = "module", groups[0]
            elif "dependency" in pattern.pattern:
                kind, name = "dependency", groups[0]
            else:
                continue

            key = (kind.lower(), name)
            if key in seen:
                continue

            seen.add(key)
            details.append({"kind": kind, "name": name})

    return details


def extract_missing_symbols(text: str):
    return [detail["name"] for detail in extract_missing_symbol_details(text)]


def extract_database_integrity_details(text: str):
    details = {}
    source = text or ""

    sql_error = SQL_ERROR_CODE_PATTERN.search(source)
    if sql_error:
        details["sqlError"] = sql_error.group(1)

    sql_state = SQL_STATE_PATTERN.search(source)
    if sql_state:
        details["sqlState"] = sql_state.group(1)

    duplicate_entry = DUPLICATE_ENTRY_PATTERN.search(source)
    if duplicate_entry:
        details["duplicateValue"] = duplicate_entry.group(1)
        details["constraintName"] = duplicate_entry.group(2)

    unique_constraint = UNIQUE_CONSTRAINT_PATTERN.search(source)
    if unique_constraint:
        details["constraintName"] = unique_constraint.group(1)

    if "duplicate entry" in source.lower() or "unique constraint" in source.lower() or "sqlstate" in source.lower() or "integrity constraint" in source.lower():
        details["databaseIntegrity"] = True

    return details


def is_database_integrity_issue(text: str) -> bool:
    lower = (text or "").lower()
    return any(
        token in lower
        for token in [
            "duplicate entry",
            "sqlstate",
            "sql error: 1062",
            "error 1062",
            "unique constraint",
            "integrity constraint",
            "duplicate key value violates unique constraint",
            "duplicate key violates unique constraint",
        ]
    )


def infer_error_type(root_line: str, context_text: str, matched_signature: dict | None):
    lower = (context_text or root_line or "").lower()

    if matched_signature:
        category = matched_signature.get("category")

        if category == "Code Compilation Failure":
            return "COMPILATION_ERROR"
        if category == "Test Failure":
            return "TEST_FAILURE"
        if category in {"Build Configuration Failure", "Pipeline Configuration Failure"}:
            return "BUILD_CONFIG_ERROR"
        if category in {"Environment Failure", "Infrastructure Failure", "External System Failure"}:
            return "ENVIRONMENT_ERROR"

    if any(token in lower for token in ["cannot find symbol", "undefined reference", "package ", "compilation failure", " error:"]):
        return "COMPILATION_ERROR"
    if any(token in lower for token in ["assertionerror", "assertion failed", "there are test failures", "test failed", "tests run:"]):
        return "TEST_FAILURE"
    if is_database_integrity_issue(lower):
        return "TEST_FAILURE"
    if any(token in lower for token in ["nullpointerexception", "segmentation fault", "exception"]):
        return "RUNTIME_ERROR"
    if any(token in lower for token in ["must be unique", "duplicate declaration", "build failure", "failed to execute goal", ".gitlab-ci.yml", "yaml"]):
        return "BUILD_CONFIG_ERROR"
    if any(token in lower for token in ["permission denied", "command not found", "environment variable", "no such file or directory"]):
        return "ENVIRONMENT_ERROR"

    return "BUILD_CONFIG_ERROR" if "[error]" in lower else "ENVIRONMENT_ERROR"


def describe_error_type(error_type: str, context_text: str, missing_symbol_details=None):
    lower = (context_text or "").lower()
    details = missing_symbol_details or extract_missing_symbol_details(context_text)

    if error_type == "COMPILATION_ERROR":
        if "cannot find symbol" in lower and details:
            class_count = sum(1 for detail in details if detail["kind"].lower() == "class")
            if class_count > 1:
                return "Compilation Failure - Missing Classes"
            if any(detail["kind"].lower() == "class" for detail in details):
                return "Compilation Failure - Missing Class"
            if any(detail["kind"].lower() == "method" for detail in details):
                return "Compilation Failure - Missing Method"
            if any(detail["kind"].lower() == "variable" for detail in details):
                return "Compilation Failure - Missing Variable"
        if "package " in lower and "does not exist" in lower:
            return "Compilation Failure - Missing Package"
        if "undefined reference" in lower:
            return "Compilation Failure - Undefined Reference"
        return "Compilation Failure"

    if error_type == "TEST_FAILURE":
        if is_database_integrity_issue(lower):
            return "Test Failure - Database Integrity Error"
        return "Test Failure - Assertion Failure" if "assert" in lower else "Test Failure"

    if error_type == "RUNTIME_ERROR":
        return "Runtime Failure - Null Pointer" if "nullpointer" in lower else "Runtime Failure"

    if error_type == "BUILD_CONFIG_ERROR":
        return "Build Configuration Failure - Duplicate Dependency" if "duplicate" in lower or "must be unique" in lower else "Build Configuration Failure"

    if error_type == "ENVIRONMENT_ERROR":
        return "Environment Failure - Missing Tool" if "command not found" in lower else "Environment Failure"

    return error_type


def infer_what_is_wrong(error_type: str, root_line: str, context_text: str, matched_signature: dict | None, missing_symbol_details=None):
    lower = (context_text or root_line or "").lower()
    symbol = extract_symbol_details(context_text)
    details = missing_symbol_details or extract_missing_symbol_details(context_text)
    db_details = extract_database_integrity_details(context_text or root_line or "")

    if error_type == "COMPILATION_ERROR":
        if "cannot find symbol" in lower and details:
            class_names = [detail["name"] for detail in details if detail["kind"].lower() == "class"]
            method_names = [detail["name"] for detail in details if detail["kind"].lower() == "method"]
            variable_names = [detail["name"] for detail in details if detail["kind"].lower() == "variable"]

            if class_names:
                if len(class_names) == 1:
                    return f"Missing class '{class_names[0]}' is referenced but not available on the compile classpath."
                return f"Missing classes {', '.join(class_names)} are referenced but not available on the compile classpath."
            if method_names:
                if len(method_names) == 1:
                    return f"Missing method '{method_names[0]}' is referenced but not defined with the expected signature."
                return f"Missing methods {', '.join(method_names)} are referenced but not defined with the expected signature."
            if variable_names:
                if len(variable_names) == 1:
                    return f"Missing variable '{variable_names[0]}' is referenced but not defined in scope."
                return f"Missing variables {', '.join(variable_names)} are referenced but not defined in scope."
        if "package " in lower and "does not exist" in lower and symbol:
            return f"Package '{symbol['name']}' is imported or referenced, but it is not available on the compile classpath."
        if "undefined reference" in lower and symbol:
            return f"Symbol '{symbol['name']}' is declared or used, but no matching definition is available at link time."
        return "Source code or generated code contains a compile-time error that stopped the build."

    if error_type == "TEST_FAILURE":
        if is_database_integrity_issue(lower):
            sql_bits = []
            if db_details.get("sqlError"):
                sql_bits.append(f"SQL Error {db_details['sqlError']}")
            if db_details.get("sqlState"):
                sql_bits.append(f"SQLState {db_details['sqlState']}")

            suffix = f" ({', '.join(sql_bits)})" if sql_bits else ""
            duplicate_value = db_details.get("duplicateValue")
            if duplicate_value:
                return (
                    f"Database constraint violation{suffix}: duplicate value '{duplicate_value}'"
                    " was inserted into a unique column or index."
                )
            return (
                f"Database constraint violation{suffix}: a duplicate value was inserted into a unique column or index."
            )
        if "assertion" in lower:
            return "A test assertion failed because actual behavior does not match the expected result."
        return "A test case failed and caused the build to stop."

    if error_type == "RUNTIME_ERROR":
        if "nullpointerexception" in lower:
            return "A null reference was dereferenced during execution."
        if "segmentation fault" in lower:
            return "The process crashed due to invalid memory access."
        return "A runtime exception stopped execution."

    if error_type == "BUILD_CONFIG_ERROR":
        if "must be unique" in lower or "duplicate declaration" in lower:
            return "The build configuration declares the same dependency or setting more than once."
        if ".gitlab-ci.yml" in lower or "yaml" in lower:
            return "The pipeline or build configuration file is invalid."
        return "A build or configuration problem stopped dependency resolution or task execution."

    if error_type == "ENVIRONMENT_ERROR":
        if "permission denied" in lower:
            return "The job does not have permission to access a required file, tool, or resource."
        if "command not found" in lower:
            return "A required tool or executable is missing from the environment."
        return "The environment is missing a required dependency, configuration, or permission."

    if matched_signature:
        return matched_signature.get("rootCause", "The build stopped on a blocking error.")

    return "The build stopped on a blocking error."


def infer_exact_fix(error_type: str, what_is_wrong: str, file_name: str | None, line: str | None, context_text: str, matched_signature: dict | None, missing_symbol_details=None):
    location = f"{file_name}:{line}" if file_name and line else file_name
    symbol = extract_symbol_details(context_text)
    details = missing_symbol_details or extract_missing_symbol_details(context_text)
    db_details = extract_database_integrity_details(context_text)

    if error_type == "COMPILATION_ERROR":
        class_names = [detail["name"] for detail in details if detail["kind"].lower() == "class"]
        method_names = [detail["name"] for detail in details if detail["kind"].lower() == "method"]
        package_names = [detail["name"] for detail in details if detail["kind"].lower() == "package"]

        if class_names:
            if len(class_names) == 1:
                return f"Create, restore, or import class '{class_names[0]}' in the correct package, and make sure it is committed and on the compile classpath."
            joined = ", ".join(class_names)
            return f"Create the missing classes {joined} in the correct package, or fix the imports if they already exist."
        if method_names:
            if len(method_names) == 1:
                return f"Define method '{method_names[0]}' or update the call site to the correct method name and signature."
            return f"Define the missing methods {', '.join(method_names)} or update their call sites to the correct signatures."
        if package_names:
            if len(package_names) == 1:
                return f"Fix the import for package '{package_names[0]}' or add the missing dependency that provides it."
            return f"Fix the imports for packages {', '.join(package_names)} or add the dependencies that provide them."
        return f"Open {location or 'the first compiler error location'}, fix the missing symbol/import/syntax issue, and rerun the build."

    if error_type == "TEST_FAILURE" and is_database_integrity_issue(context_text):
        sql_bits = []
        if db_details.get("sqlError"):
            sql_bits.append(f"SQL Error {db_details['sqlError']}")
        if db_details.get("sqlState"):
            sql_bits.append(f"SQLState {db_details['sqlState']}")
        details_text = f" ({', '.join(sql_bits)})" if sql_bits else ""
        return (
            f"Use unique test data, reset the database state between tests, and verify the unique constraint that triggered the duplicate insert{details_text}."
        )

    if matched_signature and matched_signature.get("fixRecommendation"):
        fix = matched_signature["fixRecommendation"]
        return f"{fix} Start with {location}." if location else fix

    if error_type == "TEST_FAILURE":
        return f"Open {location or 'the first failing test'}, correct the failing assertion or the code under test, and rerun only the failing test suite."

    if error_type == "RUNTIME_ERROR":
        return f"Inspect {location or 'the failing stack frame'}, add the required null/validity guard or fix the crashing logic, and rerun the affected job."

    if error_type == "BUILD_CONFIG_ERROR":
        if "must be unique" in context_text.lower() or "duplicate declaration" in context_text.lower():
            return f"Remove the duplicate dependency or configuration entry in {location or 'the build file'} so only one canonical declaration remains."
        return f"Correct the invalid build or pipeline configuration in {location or 'the configuration file'} and rerun the pipeline."

    return f"Fix the missing environment/tooling prerequisite at {location or 'the failing step'} and rerun the job."


def build_root_failure_statement(error_type: str, what_is_wrong: str, file_name: str | None, line: str | None):
    location = f" at {file_name}:{line}" if file_name and line else f" in {file_name}" if file_name else ""
    return f"{error_type}: {what_is_wrong}{location}"


def extract_secondary_issues(log: str, root_line: str, max_items: int = 5):
    issues = []
    seen = set()
    root_normalized = (root_line or "").strip().lower()
    secondary_patterns = [
        re.compile(r"^\s*\[warning\].+", re.IGNORECASE),
        re.compile(r"\bmust be unique\b", re.IGNORECASE),
        re.compile(r"\bduplicate declaration\b", re.IGNORECASE),
        re.compile(r"\bdeprecated\b", re.IGNORECASE),
        re.compile(r"\bskipped\b", re.IGNORECASE),
        re.compile(r"\bwarning:\b", re.IGNORECASE),
    ]

    for raw in log.splitlines():
        line = raw.strip()
        lower = line.lower()

        if not line or lower == root_normalized:
            continue

        if any(pattern.search(line) for pattern in secondary_patterns):
            normalized = re.sub(r"\s+", " ", lower)

            if normalized in seen:
                continue

            seen.add(normalized)
            issues.append(line)

        if len(issues) >= max_items:
            break

    return issues


def build_human_root_cause(error_type: str, root_cause: str, what_is_wrong: str, context_text: str, missing_symbols):
    lower = (context_text or "").lower()

    if error_type == "COMPILATION_ERROR" and missing_symbols:
        class_names = [symbol for symbol in missing_symbols if symbol and "." not in symbol]
        if class_names:
            if len(class_names) == 1:
                return f"Missing DTO class: {class_names[0]}"
            return f"Missing DTO classes: {', '.join(class_names)}"

        package_details = extract_missing_symbol_details(context_text)
        package_names = [detail["name"] for detail in package_details if detail["kind"].lower() == "package"]
        if package_names:
            return f"Missing package or import: {package_names[0]}"

    if error_type == "TEST_FAILURE" and is_database_integrity_issue(lower):
        return "Database constraint violation (Duplicate Entry)"

    return root_cause or what_is_wrong or "The build stopped on a blocking error."


def build_fix_options(error_type: str, context_text: str, file_name: str | None, line: str | None, missing_symbols):
    lower = (context_text or "").lower()
    location = f"{file_name}:{line}" if file_name and line else file_name or "the failing file"
    options = []

    if error_type == "COMPILATION_ERROR":
        package_details = extract_missing_symbol_details(context_text)
        package_names = [detail["name"] for detail in package_details if detail["kind"].lower() == "package"]
        package_name = package_names[0] if package_names else "com.cyhub.backend.dto.admin"

        if missing_symbols:
            joined = ", ".join(missing_symbols)
            options.append(f"If the classes do not exist, create {joined} in package {package_name}.")
            options.append(f"If the classes already exist, fix the import statements in {location}.")
            options.append("If the DTOs were renamed or moved, update the package path in the controller/service references.")
            return options

        options.append(f"Open {location} and fix the missing symbol, import, or signature mismatch.")
        options.append("Confirm the source file compiles with the same package structure used by the build.")
        return options

    if error_type == "TEST_FAILURE" and is_database_integrity_issue(lower):
        options.append("Ensure the test creates unique data, such as UUIDs or randomized IDs.")
        options.append("Clean or rollback the database state between tests so prior rows do not collide.")
        options.append("Verify the entity unique constraints and fixture data agree on allowed values.")
        options.append("Avoid reusing the same input record or primary key in repeated test runs.")
        return options

    if error_type == "TEST_FAILURE":
        options.append("Reproduce the first failing test locally and confirm the expected result.")
        options.append("Check whether the production code or the test assertion needs to change.")
        options.append("Verify fixtures, mocks, and setup data for stale assumptions.")
        return options

    if error_type == "BUILD_CONFIG_ERROR" and ("must be unique" in lower or "duplicate declaration" in lower):
        options.append(f"Remove duplicate entries from {location} so only one canonical declaration remains.")
        options.append("Introduce dependency management or central version control to prevent repeated declarations.")
        options.append("Clean up unused dependencies after the blocking duplicate is removed.")
        return options

    options.append("Open the first blocking error and fix the underlying source or configuration issue.")
    options.append("Rerun the same pipeline only after the root error is resolved.")
    return options


def build_meaning(error_type: str, context_text: str, missing_symbols, db_details=None):
    lower = (context_text or "").lower()
    db_details = db_details or extract_database_integrity_details(context_text)

    if error_type == "COMPILATION_ERROR" and missing_symbols:
        if len(missing_symbols) == 1:
            return "The compiler cannot resolve one referenced DTO or symbol, so the source tree and imports are out of sync."
        return "The compiler cannot resolve multiple referenced DTOs or symbols, so the source tree and imports are out of sync."

    if error_type == "TEST_FAILURE" and is_database_integrity_issue(lower):
        if db_details.get("sqlError") or db_details.get("sqlState"):
            return "A database uniqueness rule rejected the insert, which means the test data violates a unique constraint."
        return "A database uniqueness rule rejected the insert, which means the test data is not unique enough for the schema."

    if error_type == "TEST_FAILURE":
        return "The observed behavior does not match the expected test result."

    if error_type == "RUNTIME_ERROR":
        return "The process stopped while executing application code."

    if error_type == "BUILD_CONFIG_ERROR":
        return "The build or pipeline configuration is inconsistent enough to stop task execution."

    return "The build stopped on a blocking error."


def build_likely_cause(error_type: str, context_text: str, missing_symbols):
    lower = (context_text or "").lower()

    if error_type == "COMPILATION_ERROR" and missing_symbols:
        if len(missing_symbols) == 1:
            return "The DTO was not created, moved, or imported correctly."
        return "The DTOs were not created, moved, or imported correctly."

    if error_type == "TEST_FAILURE" and is_database_integrity_issue(lower):
        return "Test data is being reused, the database is not being reset, or the fixture violates a unique constraint."

    if error_type == "TEST_FAILURE":
        return "The test expectation, fixture, or production behavior changed."

    if error_type == "BUILD_CONFIG_ERROR":
        return "A build file, stage definition, or dependency declaration is malformed."

    if error_type == "RUNTIME_ERROR":
        return "The code hit a runtime exception or invalid state during execution."

    return "The first blocking error line points to the active root cause."


def format_analysis_output(
    root_cause,
    error_type,
    file_name,
    line,
    class_or_function,
    missing_symbols,
    what_is_wrong,
    exact_fix,
    secondary_issues,
    meaning=None,
    likely_cause=None,
    fix_options=None,
    details=None,
):
    location_bits = [bit for bit in [file_name, line] if bit]
    location = ":".join(location_bits) if location_bits else "Unknown location"

    if class_or_function:
        location += f" ({class_or_function})"

    if missing_symbols:
        symbol_lines = "\n".join(f"- {symbol}" for symbol in missing_symbols)
        missing_symbol_block = f"MISSING SYMBOLS:\n{symbol_lines}\n\n"
    else:
        missing_symbol_block = ""

    details_lines = []
    if details:
        for item in details:
            if item:
                details_lines.append(f"- {item}")
    elif missing_symbols:
        details_lines.extend(f"- Missing symbol: {symbol}" for symbol in missing_symbols)

    details_block = f"DETAILS:\n" + "\n".join(details_lines) + "\n\n" if details_lines else ""

    fix_option_lines = []
    if fix_options:
        for index, option in enumerate(fix_options, start=1):
            fix_option_lines.append(f"{index}. {option}")
    fix_options_block = f"FIX OPTIONS:\n" + "\n".join(fix_option_lines) + "\n\n" if fix_option_lines else ""

    meaning_text = meaning or what_is_wrong
    likely_text = likely_cause or "See the fix options below."
    secondary = "; ".join(secondary_issues) if secondary_issues else "None"
    return (
        f"ROOT CAUSE:\n{root_cause}\n\n"
        f"ERROR TYPE:\n{error_type}\n\n"
        f"LOCATION:\n{location}\n\n"
        f"{missing_symbol_block}"
        f"{details_block}"
        f"MEANING:\n{meaning_text}\n\n"
        f"LIKELY CAUSE:\n{likely_text}\n\n"
        f"{fix_options_block}"
        f"WHAT IS WRONG: {what_is_wrong}\n\n"
        f"FIX: {exact_fix}\n\n"
        f"SECONDARY ISSUES: {secondary}"
    )


def match_signature(log_text: str):
    for signature in load_signatures():
        for pattern in signature.get("patterns", []):
            if pattern.lower() in log_text:
                return signature, pattern

        for regex in signature.get("regexPatterns", []):
            if re.search(regex, log_text, re.IGNORECASE):
                return signature, regex

    return None, None


def normalize_for_fingerprint(snippets, file_name: str | None, tool: str | None):
    basis = []

    if file_name:
        basis.append(file_name.lower())

    if tool:
        basis.append(tool.lower())

    for snippet in snippets[:5]:
        normalized = re.sub(r"\b[0-9a-f]{7,40}\b", "<sha>", snippet.lower())
        normalized = re.sub(r"\b\d+\b", "<num>", normalized)
        basis.append(normalized)

    if not basis:
        basis.append("unknown-failure")

    joined = "\n".join(basis).encode("utf-8")
    return hashlib.sha256(joined).hexdigest()[:16]


def extract_requirement_ids(log: str):
    return sorted(set(REQUIREMENT_ID_PATTERN.findall(log or "")))


def extract_verification_entities(log: str):
    lower = (log or "").lower()
    return {
        "requirementIds": extract_requirement_ids(log),
        "dataDictionaryVariables": sorted(set(DD_TOKEN_PATTERN.findall(log or ""))),
        "rvstestReferenced": "rvstest" in lower,
        "pythonVerificationReferenced": "pytest" in lower or ("python" in lower and "test" in lower),
        "stubReferenced": "stub" in lower or "mock" in lower,
        "coverageReferenced": "coverage" in lower or "threshold" in lower,
    }


def location_hint(file_name, line, column):
    if not file_name and not line:
        return ""

    bits = [file_name or "unknown file"]
    if line:
        bits.append(str(line))
    if column:
        bits.append(str(column))
    return " at " + ":".join(bits)


def recommendation_policy(confidence: str, matched_signature: dict | None):
    if matched_signature is None:
        return "Guided triage"

    if confidence.upper() in {"CRITICAL", "VERY HIGH", "HIGH"}:
        return "High-confidence recommendation"

    return "Confidence-gated recommendation"


def next_best_action(category: str, supports_retry: bool):
    if supports_retry:
        return "Verify the external or infrastructure dependency first, then retry the affected job or pipeline."

    if category == "Pipeline Configuration Failure":
        return "Inspect the changed CI configuration first and validate syntax before rerunning."

    if category == "Build Configuration Failure":
        return "Review recent build-file changes before inspecting downstream compiler noise."

    if category == "Test Failure":
        return "Reproduce the first failing test locally and confirm whether code or fixture changes caused it."

    return "Inspect the first high-signal error lines, review the recent diff, and confirm the true root cause before rerunning."


def build_result(
    failure_type: str,
    category: str,
    root_cause: str,
    fix: str,
    confidence: str,
    tool: str,
    file_name: str | None,
    line: str | None,
    column: str | None,
    snippets,
    location_evidence: str | None,
    supports_retry: bool,
    owner: str,
    signature_id: str | None,
    matched_pattern: str | None,
    raw_log: str,
    error_type: str,
    error_type_display: str,
    root_failure_statement: str,
    what_is_wrong: str,
    class_or_function: str | None,
    symbol_name: str | None,
    symbol_kind: str | None,
    missing_symbols,
    secondary_issues,
):
    confidence = confidence or "LOW"
    fingerprint = normalize_for_fingerprint(snippets, file_name, tool)
    requires_human_review = signature_id is None or confidence.upper() in {"LOW", "MEDIUM"} or not snippets
    verification_entities = extract_verification_entities(raw_log)
    db_details = extract_database_integrity_details(raw_log)
    symbol_details = extract_missing_symbol_details(raw_log)
    refined_root_cause = build_human_root_cause(error_type, root_cause, what_is_wrong, raw_log, missing_symbols)
    meaning = build_meaning(error_type, raw_log, missing_symbols, db_details)
    likely_cause = build_likely_cause(error_type, raw_log, missing_symbols)
    fix_options = build_fix_options(error_type, raw_log, file_name, line, missing_symbols)
    details = []

    if missing_symbols:
        details.append("Missing symbols: " + ", ".join(missing_symbols))
        package_names = [detail["name"] for detail in symbol_details if detail["kind"].lower() == "package"]
        if package_names:
            details.append("Location package: " + ", ".join(package_names))

    if db_details.get("sqlError"):
        details.append(f"SQL Error: {db_details['sqlError']}")

    if db_details.get("sqlState"):
        details.append(f"SQLState: {db_details['sqlState']}")

    if db_details.get("duplicateValue"):
        details.append(f"Duplicate value: {db_details['duplicateValue']}")

    return {
        "failureType": failure_type,
        "errorType": error_type,
        "errorTypeDisplay": error_type_display,
        "category": category,
        "tool": tool,
        "file": file_name,
        "line": line,
        "column": column,
        "classOrFunction": class_or_function,
        "symbolName": symbol_name,
        "symbolKind": symbol_kind,
        "missingSymbols": missing_symbols,
        "errorMessage": snippets[0] if snippets else "No strong failure pattern matched",
        "rootFailureStatement": build_root_failure_statement(error_type, refined_root_cause, file_name, line),
        "rootCause": refined_root_cause + location_hint(file_name, line, column),
        "whatIsWrong": what_is_wrong,
        "fixRecommendation": fix,
        "secondaryIssues": secondary_issues,
        "details": details,
        "meaning": meaning,
        "likelyCause": likely_cause,
        "fixOptions": fix_options,
        "formattedAnalysis": format_analysis_output(
            refined_root_cause,
            error_type_display,
            file_name,
            line,
            class_or_function,
            missing_symbols,
            what_is_wrong,
            fix,
            secondary_issues,
            meaning=meaning,
            likely_cause=likely_cause,
            fix_options=fix_options,
            details=details,
        ),
        "confidence": confidence,
        "analysisSource": "python-signature-catalog",
        "signatureId": signature_id,
        "matchedPattern": matched_pattern,
        "failureFingerprint": fingerprint,
        "recommendedOwner": owner,
        "supportsAutomatedRetry": supports_retry,
        "requiresHumanReview": requires_human_review,
        "recommendationPolicy": recommendation_policy(confidence, {"id": signature_id} if signature_id else None),
        "novelFailure": signature_id is None,
        "nextBestAction": next_best_action(category, supports_retry),
        "knowledgeGap": None if signature_id else "No catalog signature matched this failure pattern exactly.",
        "signals": ["catalog signature match"] if signature_id else ["unknown failure fingerprint generated"],
        "requirementIds": verification_entities["requirementIds"],
        "verificationEntities": verification_entities,
        "evidence": {
            "locationLine": location_evidence,
            "snippets": snippets,
        },
    }


def fallback_result(text, raw_log, tool, file_name, line, column, snippets, location_evidence):
    if any(token in text for token in ["cannot find symbol", "compilation failure", "undefined reference", "package does not exist"]):
        error_type = "COMPILATION_ERROR"
    elif is_database_integrity_issue(text):
        error_type = "TEST_FAILURE"
    elif any(token in text for token in ["assertionerror", "assertion failed", "there are test failures", "test failed", "tests run:"]):
        error_type = "TEST_FAILURE"
    else:
        error_type = "BUILD_CONFIG_ERROR"
    missing_symbol_details = extract_missing_symbol_details(raw_log)
    error_type_display = describe_error_type(error_type, raw_log, missing_symbol_details)
    what_is_wrong = infer_what_is_wrong(error_type, snippets[0] if snippets else "", raw_log, None, missing_symbol_details)
    fix = infer_exact_fix(error_type, what_is_wrong, file_name, line, raw_log, None, missing_symbol_details)
    root_failure_statement = build_root_failure_statement(error_type, build_human_root_cause(error_type, what_is_wrong, what_is_wrong, raw_log, extract_missing_symbols(raw_log)), file_name, line)
    class_or_function = extract_class_or_function(raw_log)
    symbol_details = extract_symbol_details(raw_log) or {}
    missing_symbols = extract_missing_symbols(raw_log)
    secondary_issues = extract_secondary_issues(raw_log, snippets[0] if snippets else "")

    if "error:" in text or "compilation failure" in text or "cannot find symbol" in text or "undefined reference" in text:
        return build_result(
            "Code Compilation Failure",
            "Code Compilation Failure",
            "A compiler or parser detected an error in source code or generated code.",
            fix,
            "MEDIUM" if file_name else "LOW",
            tool,
            file_name,
            line,
            column,
            snippets,
            location_evidence,
            False,
            "Feature development team",
            None,
            None,
            raw_log,
            error_type,
            error_type_display,
            root_failure_statement,
            what_is_wrong,
            class_or_function,
            symbol_details.get("name"),
            symbol_details.get("kind"),
            missing_symbols,
            secondary_issues,
        )

    return build_result(
        "Unknown Failure",
        "Unknown",
        "The analyzer did not find a confident signature for this failure.",
        fix,
        "LOW",
        tool,
        file_name,
        line,
        column,
        snippets,
        location_evidence,
        False,
        "Engineering team",
        None,
        None,
        raw_log,
        error_type,
        error_type_display,
        root_failure_statement,
        what_is_wrong,
        class_or_function,
        symbol_details.get("name"),
        symbol_details.get("kind"),
        missing_symbols,
        secondary_issues,
    )


def classify_and_fix(log: str):
    root_failure = find_root_failure(log)
    root_context_text = "\n".join(root_failure["contextLines"]) or root_failure["rootLine"]
    file_name, line, column, location_evidence = extract_file_line_col(root_context_text)

    if not file_name and not line:
        file_name, line, column, location_evidence = extract_file_line_col(log)

    snippets = [root_failure["rootLine"]]
    additional_snippets = extract_error_snippets(root_context_text, max_lines=4)

    for snippet in additional_snippets:
        if snippet not in snippets:
            snippets.append(snippet)

    text = log.lower()
    root_text = root_context_text.lower()
    root_error_type = infer_error_type(root_failure["rootLine"], root_context_text, None)
    signature, matched_pattern = match_signature(root_text)

    if signature is None:
        signature, matched_pattern = match_signature(text)

    if signature:
        signature_error_type = infer_error_type(root_failure["rootLine"], root_context_text, signature)

        if signature_error_type != root_error_type and root_error_type in {"COMPILATION_ERROR", "TEST_FAILURE", "RUNTIME_ERROR"}:
            signature = None
            matched_pattern = None

    tool = detect_tool(text, signature)
    error_type = infer_error_type(root_failure["rootLine"], root_context_text, signature)
    missing_symbol_details = extract_missing_symbol_details(root_context_text)
    missing_symbols = extract_missing_symbols(root_context_text)
    error_type_display = describe_error_type(error_type, root_context_text, missing_symbol_details)
    class_or_function = extract_class_or_function(root_context_text)
    symbol_details = extract_symbol_details(root_context_text) or {}
    what_is_wrong = infer_what_is_wrong(error_type, root_failure["rootLine"], root_context_text, signature, missing_symbol_details)
    secondary_issues = extract_secondary_issues(log, root_failure["rootLine"])
    exact_fix = infer_exact_fix(error_type, what_is_wrong, file_name, line, root_context_text, signature, missing_symbol_details)
    root_failure_statement = build_root_failure_statement(error_type, what_is_wrong, file_name, line)

    if signature:
        category = signature["category"]
        failure_type = signature["failureType"]
        return build_result(
            failure_type,
            category,
            signature["rootCause"],
            exact_fix,
            signature.get("confidence", "MEDIUM"),
            tool,
            file_name,
            line,
            column,
            snippets,
            location_evidence,
            bool(signature.get("supportsAutomatedRetry", False)),
            signature.get("owner", "Engineering team"),
            signature.get("id"),
            matched_pattern,
            log,
            error_type,
            error_type_display,
            root_failure_statement,
            what_is_wrong,
            class_or_function,
            symbol_details.get("name"),
            symbol_details.get("kind"),
            missing_symbols,
            secondary_issues,
        )

    return fallback_result(text, log, tool, file_name, line, column, snippets, location_evidence)


@app.route("/analyze", methods=["POST"])
def analyze():
    payload = request.get_json(force=True) or {}
    raw_log = payload.get("log", "")
    cleaned_log = strip_ansi(raw_log)
    return jsonify(classify_and_fix(cleaned_log))


if __name__ == "__main__":
    app.run(port=5000)
