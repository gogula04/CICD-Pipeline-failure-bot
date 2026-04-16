package com.pipeline.intelligence_bot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class RepoContextService {

    private static final Logger logger = LoggerFactory.getLogger(RepoContextService.class);
    private static final int MAX_SNIPPET_CHARS = 800;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{2,}");
    private static final Pattern SENSITIVE_LINE_PATTERN = Pattern.compile(
            "(?i).*(token|api[_-]?key|secret|password|private[_-]?key|authorization).*"
    );
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git",
            ".idea",
            ".vscode",
            "node_modules",
            "target",
            "build",
            "dist",
            "coverage",
            "out",
            ".cache",
            ".gradle"
    );
    private static final Set<String> IGNORED_FILES = Set.of(
            ".env",
            ".env.local",
            ".env.development",
            ".env.production",
            ".env.test"
    );
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            ".java",
            ".kt",
            ".kts",
            ".xml",
            ".yml",
            ".yaml",
            ".json",
            ".js",
            ".jsx",
            ".ts",
            ".tsx",
            ".css",
            ".scss",
            ".md",
            ".txt",
            ".properties",
            ".gradle",
            ".groovy",
            ".sh",
            ".py",
            ".html",
            ".toml",
            ".cfg",
            ".ini",
            ".sql",
            ".gitignore",
            "dockerfile",
            ".dockerfile"
    );
    private static final List<String> DEFAULT_REPO_PATHS = List.of(
            "backend/intelligence-bot/src/main/java/com/pipeline/intelligence_bot/service/AiChatService.java",
            "backend/intelligence-bot/src/main/java/com/pipeline/intelligence_bot/service/AiReasoningService.java",
            "backend/intelligence-bot/src/main/java/com/pipeline/intelligence_bot/service/EnterprisePipelineAnalysisService.java",
            "backend/intelligence-bot/src/main/java/com/pipeline/intelligence_bot/service/LogPreprocessingService.java",
            "backend/intelligence-bot/src/main/java/com/pipeline/intelligence_bot/controller/ChatController.java",
            "backend/intelligence-bot/src/main/java/com/pipeline/intelligence_bot/controller/GitLabController.java",
            "frontend/src/App.jsx",
            "frontend/src/AICopilot.jsx"
    );
    private static final List<String> SAFE_CONFIG_PATHS = List.of(
            "backend/intelligence-bot/.env",
            "backend/intelligence-bot/src/main/resources/application.properties",
            "backend/intelligence-bot/src/main/java/com/pipeline/intelligence_bot/IntelligenceBotApplication.java",
            "backend/intelligence-bot/src/main/java/com/pipeline/intelligence_bot/service/GitLabService.java",
            "backend/intelligence-bot/src/main/java/com/pipeline/intelligence_bot/service/AiChatService.java",
            "backend/intelligence-bot/src/main/java/com/pipeline/intelligence_bot/service/AiReasoningService.java"
    );

    @Value("${repo.root:}")
    private String configuredRepoRoot;

    @Value("${repo.max-files:2}")
    private int maxFiles;

    @Value("${repo.max-snippet-lines:80}")
    private int maxSnippetLines;

    @Value("${repo.max-file-size-kb:256}")
    private int maxFileSizeKb;

    @Value("${repo.max-search-files:2000}")
    private int maxSearchFiles;

    public Map<String, Object> buildRepositoryContext(String question, Map<String, Object> analysis) {
        QuestionMode questionMode = classifyQuestion(question);
        Map<String, Object> root = safeMap(analysis);
        Map<String, Object> failure = firstMap(root, "failure", "primaryFailure", "primaryFailureAnalysis");
        Map<String, Object> commit = firstMap(root, "commit", "commitAnalysis");

        if (questionMode == QuestionMode.GENERAL) {
            return buildGeneralQuestionContext(questionMode);
        }

        Path repoRoot = resolveRepoRoot();

        if (questionMode == QuestionMode.SECRET_CONFIG) {
            return buildSafeConfigContext(repoRoot, questionMode);
        }

        List<String> searchTerms = collectSearchTerms(question, root, failure, commit);
        List<String> focusPaths = collectFocusPaths(root, failure, commit);
        Integer failureLine = parseInteger(failure.get("line"));

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("available", repoRoot != null);
        context.put("repoRoot", repoRoot == null ? "" : repoRoot.toString());
        context.put("status", repoRoot == null
                ? "Repository root was not found on disk."
                : "Local workspace repository context loaded.");
        context.put("questionMode", questionMode.name());
        context.put("searchTerms", searchTerms);
        context.put("focusPaths", focusPaths);

        if (repoRoot == null) {
            context.put("summary", Map.of(
                    "matchedFiles", 0,
                    "selectedFiles", 0
            ));
            context.put("files", List.of());
            context.put("references", List.of());
            return context;
        }

        List<RepoFileMatch> matches = findRelevantFiles(repoRoot, searchTerms, focusPaths, failureLine);

        if (matches.isEmpty()) {
            matches = loadDefaultRepositoryFiles(repoRoot, searchTerms, failureLine);
        }

        context.put("summary", Map.of(
                "matchedFiles", matches.size(),
                "selectedFiles", Math.min(matches.size(), maxFiles),
                "repoFileCount", countTrackedFiles(repoRoot)
        ));
        context.put("files", matches.stream()
                .sorted(Comparator.comparingInt(RepoFileMatch::score).reversed().thenComparing(RepoFileMatch::path))
                .limit(maxFiles)
                .map(RepoFileMatch::toMap)
                .toList());
        context.put("references", matches.stream()
                .sorted(Comparator.comparingInt(RepoFileMatch::score).reversed().thenComparing(RepoFileMatch::path))
                .limit(maxFiles)
                .map(RepoFileMatch::path)
                .toList());
        return context;
    }

    private Map<String, Object> buildGeneralQuestionContext(QuestionMode questionMode) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("available", false);
        context.put("repoRoot", "");
        context.put("status", "General question. Repository lookup was skipped.");
        context.put("questionMode", questionMode.name());
        context.put("searchTerms", List.of());
        context.put("focusPaths", List.of());
        context.put("summary", Map.of("matchedFiles", 0, "selectedFiles", 0));
        context.put("files", List.of());
        context.put("references", List.of());
        return context;
    }

    private Map<String, Object> buildSafeConfigContext(Path repoRoot, QuestionMode questionMode) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("available", repoRoot != null);
        context.put("repoRoot", repoRoot == null ? "" : repoRoot.toString());
        context.put("status", repoRoot == null
                ? "Repository root was not found on disk."
                : "Secret-related question. Showing safe config locations only.");
        context.put("questionMode", questionMode.name());
        context.put("searchTerms", List.of("token", "secret", "env", "config", "credentials"));
        context.put("focusPaths", SAFE_CONFIG_PATHS);

        if (repoRoot == null) {
            context.put("summary", Map.of("matchedFiles", 0, "selectedFiles", 0));
            context.put("files", List.of());
            context.put("references", List.of());
            return context;
        }

        List<Map<String, Object>> files = new ArrayList<>();
        for (String relativePath : SAFE_CONFIG_PATHS) {
            Path candidate = repoRoot.resolve(relativePath).normalize();
            if (!Files.exists(candidate)) {
                continue;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", relativePath);
            entry.put("score", 999);
            entry.put("reasons", List.of("Safe configuration location"));
            entry.put("snippet", buildSafeConfigSnippet(relativePath));
            entry.put("matchedTerms", List.of("token", "secret", "config"));
            entry.put("lineHint", null);
            files.add(entry);
        }

        context.put("summary", Map.of(
                "matchedFiles", files.size(),
                "selectedFiles", Math.min(files.size(), maxFiles)
        ));
        context.put("files", files);
        context.put("references", files.stream().map(file -> String.valueOf(file.get("path"))).toList());
        return context;
    }

    private Path resolveRepoRoot() {
        Path configured = normalizeConfiguredPath(configuredRepoRoot);

        if (configured != null) {
            return configured;
        }

        Path current = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();

        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }

            if (Files.isDirectory(current.resolve("frontend")) && Files.isDirectory(current.resolve("backend"))) {
                return current;
            }

            current = current.getParent();
        }

        return null;
    }

    private Path normalizeConfiguredPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        Path path = Paths.get(value).toAbsolutePath().normalize();
        if (Files.exists(path)) {
            return path;
        }

        return null;
    }

    private List<RepoFileMatch> findRelevantFiles(Path repoRoot, List<String> searchTerms, List<String> focusPaths, Integer failureLine) {
        Map<String, RepoFileMatch> matches = new LinkedHashMap<>();

        try (Stream<Path> stream = Files.walk(repoRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !shouldIgnore(path))
                    .limit(Math.max(1, maxSearchFiles))
                    .forEach(path -> {
                        RepoFileMatch match = scoreFile(repoRoot, path, searchTerms, focusPaths, failureLine);
                        if (match != null) {
                            matches.put(match.path(), match);
                        }
                    });
        } catch (IOException exception) {
            logger.warn("Unable to search repository files: {}", exception.getMessage());
        }

        return new ArrayList<>(matches.values());
    }

    private List<RepoFileMatch> loadDefaultRepositoryFiles(Path repoRoot, List<String> searchTerms, Integer failureLine) {
        List<RepoFileMatch> matches = new ArrayList<>();

        for (String relativePath : DEFAULT_REPO_PATHS) {
            Path candidate = repoRoot.resolve(relativePath).normalize();

            if (!Files.exists(candidate) || shouldIgnore(candidate)) {
                continue;
            }

            RepoFileMatch match = scoreFile(repoRoot, candidate, searchTerms, List.of(relativePath), failureLine);
            if (match != null) {
                matches.add(match);
            }
        }

        return matches;
    }

    private RepoFileMatch scoreFile(
            Path repoRoot,
            Path file,
            List<String> searchTerms,
            List<String> focusPaths,
            Integer failureLine
    ) {
        try {
            long sizeBytes = Files.size(file);
            if (sizeBytes > maxFileSizeKb * 1024L) {
                return scoreLargeFile(repoRoot, file, searchTerms, focusPaths, failureLine);
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String normalizedPath = normalizePath(repoRoot.relativize(file).toString());
            String fileName = normalizePath(file.getFileName().toString());
            int score = 0;
            List<String> reasons = new ArrayList<>();
            List<String> matchedTerms = new ArrayList<>();
            int hitLine = -1;

            if (matchesFocusPath(normalizedPath, focusPaths)) {
                score += 120;
                reasons.add("Referenced directly by the analysis");
            }

            for (String term : searchTerms) {
                if (term.isBlank()) {
                    continue;
                }

                if (normalizedPath.contains(term)) {
                    score += 24;
                    addReason(reasons, "Path matches \"" + term + "\"");
                    matchedTerms.add(term);
                }

                if (fileName.contains(term)) {
                    score += 18;
                    addReason(reasons, "File name matches \"" + term + "\"");
                    matchedTerms.add(term);
                }

                int lineHit = findLineHit(lines, term);
                if (lineHit > 0) {
                    score += 6;
                    hitLine = hitLine > 0 ? Math.min(hitLine, lineHit) : lineHit;
                    addReason(reasons, "Content mentions \"" + term + "\"");
                    matchedTerms.add(term);
                }
            }

            if (score <= 0 && !isCodeLike(file)) {
                return null;
            }

            if (score <= 0) {
                score = 1;
            }

            String snippet = buildSnippet(lines, matchedTerms, hitLine > 0 ? hitLine : failureLine, file);
            return new RepoFileMatch(normalizedPath, score, reasons, snippet, matchedTerms, hitLine > 0 ? hitLine : failureLine);
        } catch (Exception exception) {
            return null;
        }
    }

    private RepoFileMatch scoreLargeFile(
            Path repoRoot,
            Path file,
            List<String> searchTerms,
            List<String> focusPaths,
            Integer failureLine
    ) {
        String normalizedPath = normalizePath(repoRoot.relativize(file).toString());
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (matchesFocusPath(normalizedPath, focusPaths)) {
            score += 120;
            reasons.add("Referenced directly by the analysis");
        }

        for (String term : searchTerms) {
            if (normalizedPath.contains(term)) {
                score += 12;
                addReason(reasons, "Path matches \"" + term + "\"");
            }
        }

        if (score <= 0) {
            return null;
        }

        String snippet = "File is larger than the configured repository snippet limit.";
        return new RepoFileMatch(normalizedPath, score, reasons, snippet, List.of(), failureLine);
    }

    private int findLineHit(List<String> lines, String term) {
        if (term == null || term.isBlank()) {
            return -1;
        }

        String needle = term.toLowerCase(Locale.ROOT);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line != null && line.toLowerCase(Locale.ROOT).contains(needle)) {
                return index + 1;
            }
        }

        return -1;
    }

    private String buildSnippet(List<String> lines, List<String> matchedTerms, Integer lineHint, Path file) {
        if (lines.isEmpty()) {
            return "";
        }

        int startLine = 1;
        if (lineHint != null && lineHint > 0) {
            startLine = Math.max(1, lineHint - 12);
        } else {
            int termHit = findFirstTermHit(lines, matchedTerms);
            if (termHit > 0) {
                startLine = Math.max(1, termHit - 12);
            }
        }

        int endLine = Math.min(lines.size(), startLine + maxSnippetLines - 1);
        StringBuilder builder = new StringBuilder();
        builder.append("```text\n");

        for (int index = startLine; index <= endLine; index++) {
            String sanitized = sanitizeLine(lines.get(index - 1));
            builder.append(index).append(": ").append(sanitized).append('\n');
            if (builder.length() >= MAX_SNIPPET_CHARS) {
                break;
            }
        }

        if (builder.length() >= MAX_SNIPPET_CHARS) {
            builder.append("... truncated ...\n");
        }

        builder.append("```\n");
        return builder.toString();
    }

    private int findFirstTermHit(List<String> lines, List<String> terms) {
        for (String term : terms) {
            int hit = findLineHit(lines, term);
            if (hit > 0) {
                return hit;
            }
        }

        return -1;
    }

    private boolean shouldIgnore(Path path) {
        String normalized = normalizePath(path.toString());

        for (String segment : normalized.split("/")) {
            if (IGNORED_DIRECTORIES.contains(segment)) {
                return true;
            }
        }

        String fileName = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return IGNORED_FILES.contains(fileName);
    }

    private boolean isCodeLike(Path file) {
        String name = normalizePath(file.getFileName().toString());

        for (String suffix : CODE_EXTENSIONS) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }

        return name.indexOf('.') < 0;
    }

    private boolean matchesFocusPath(String normalizedPath, List<String> focusPaths) {
        for (String focusPath : focusPaths) {
            String normalizedFocus = normalizePath(focusPath);
            if (normalizedFocus.isBlank()) {
                continue;
            }

            if (normalizedPath.equals(normalizedFocus) || normalizedPath.endsWith("/" + normalizedFocus)) {
                return true;
            }
        }

        return false;
    }

    private List<String> collectSearchTerms(String question, Map<String, Object> root, Map<String, Object> failure, Map<String, Object> commit) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addTerms(terms, question);
        addTerms(terms, asString(root.get("analysisMode")));
        addTerms(terms, asString(failure.get("failureType")));
        addTerms(terms, asString(failure.get("category")));
        addTerms(terms, asString(failure.get("rootCause")));
        addTerms(terms, asString(failure.get("whatIsWrong")));
        addTerms(terms, asString(failure.get("errorMessage")));
        addTerms(terms, asString(failure.get("file")));
        addTerms(terms, asString(commit.get("commitTitle")));
        addTerms(terms, asString(commit.get("commitSha")));
        addTerms(terms, asString(commit.get("causalAssessment")));
        addTerms(terms, asString(commit.get("smartFileCorrelation")));

        for (String path : collectFocusPaths(root, failure, commit)) {
            addTerms(terms, path);
        }

        List<String> orderedTerms = new ArrayList<>(terms);
        return new ArrayList<>(orderedTerms.subList(0, Math.min(orderedTerms.size(), 18)));
    }

    private QuestionMode classifyQuestion(String question) {
        String text = normalizePath(question);

        if (containsAny(text, "token", "secret", "api key", "apikey", "credentials", "environment variable", "env", "gitlab token", "groq api key")) {
            return QuestionMode.SECRET_CONFIG;
        }

        if (containsAny(text, "repo", "file", "files", "code", "class", "method", "function", "path", "line", "config", "pipeline", "job", "log", "failure", "error", "fix", "commit", "branch")) {
            return QuestionMode.REPO_OR_PIPELINE;
        }

        return QuestionMode.GENERAL;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }

        return false;
    }

    private String buildSafeConfigSnippet(String relativePath) {
        if (relativePath.endsWith(".env")) {
            return "Set secrets locally in this file. Example:\nGITLAB_TOKEN=your_gitlab_token_here\nGROQ_API_KEY=your_groq_key_here";
        }

        if (relativePath.endsWith("application.properties")) {
            return "This file reads environment variables such as GITLAB_TOKEN and GROQ_API_KEY.";
        }

        return "Safe configuration location. Use this file for local setup.";
    }

    private List<String> collectFocusPaths(Map<String, Object> root, Map<String, Object> failure, Map<String, Object> commit) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        addPath(paths, failure.get("file"));
        addPath(paths, commit.get("file"));
        addPath(paths, commit.get("changedFiles"));
        addPath(paths, commit.get("likelyRelatedFiles"));
        addPath(paths, root.get("changedFiles"));
        addPath(paths, root.get("likelyRelatedFiles"));
        return new ArrayList<>(paths);
    }

    private void addPath(Set<String> paths, Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            addPath(paths, rawMap.get("path"));
            addPath(paths, rawMap.get("new_path"));
            addPath(paths, rawMap.get("old_path"));
            addPath(paths, rawMap.get("file"));
            return;
        }

        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addPath(paths, item);
            }
            return;
        }

        String path = asString(value).trim();
        if (!path.isBlank() && looksLikePath(path)) {
            paths.add(path);
        }
    }

    private boolean looksLikePath(String value) {
        String normalized = normalizePath(value);
        return normalized.contains("/") || normalized.endsWith(".java") || normalized.endsWith(".jsx")
                || normalized.endsWith(".js") || normalized.endsWith(".py") || normalized.endsWith(".yml")
                || normalized.endsWith(".yaml") || normalized.endsWith(".xml") || normalized.endsWith(".json")
                || normalized.endsWith(".properties") || normalized.endsWith(".md") || normalized.endsWith(".css");
    }

    private void addTerms(Set<String> terms, String value) {
        if (value == null || value.isBlank()) {
            return;
        }

        for (String token : tokenize(value)) {
            if (!isStopWord(token)) {
                terms.add(token);
            }
        }
    }

    private boolean isStopWord(String token) {
        return STOP_WORDS.contains(token);
    }

    private String sanitizeLine(String line) {
        if (line == null) {
            return "";
        }

        if (SENSITIVE_LINE_PATTERN.matcher(line).matches()) {
            return "[redacted sensitive content]";
        }

        String trimmed = line.trim();
        if (trimmed.contains("GITLAB_TOKEN") || trimmed.contains("GROQ_API_KEY") || trimmed.contains("SECRET")
                || trimmed.contains("PRIVATE_KEY")) {
            return "[redacted sensitive content]";
        }

        return line;
    }

    private void addReason(List<String> reasons, String reason) {
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
    }

    private int parseInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }

        try {
            return Integer.parseInt(asString(value));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private Map<String, Object> firstMap(Map<String, Object> root, String... keys) {
        for (String key : keys) {
            Map<String, Object> candidate = safeMap(root.get(key));
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }

        return new LinkedHashMap<>();
    }

    private Map<String, Object> safeMap(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                converted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return converted;
        }

        return new LinkedHashMap<>();
    }

    private String normalizePath(String value) {
        if (value == null) {
            return "";
        }

        return value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int countTrackedFiles(Path repoRoot) {
        try (Stream<Path> stream = Files.walk(repoRoot)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> !shouldIgnore(path))
                    .count();
        } catch (IOException exception) {
            return 0;
        }
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        java.util.regex.Matcher matcher = TOKEN_PATTERN.matcher(text);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "the",
            "and",
            "for",
            "with",
            "from",
            "into",
            "that",
            "this",
            "what",
            "when",
            "where",
            "why",
            "how",
            "your",
            "you",
            "can",
            "should",
            "would",
            "could",
            "issue",
            "error",
            "failure",
            "fix",
            "show",
            "code",
            "repo",
            "file",
            "files",
            "pipeline",
            "commit",
            "groq",
            "ai",
            "copilot",
            "assistant",
            "my",
            "me",
            "on",
            "in",
            "of",
            "to",
            "a",
            "an",
            "is",
            "it",
            "be"
    );

    private static final class RepoFileMatch {
        private final String path;
        private final int score;
        private final List<String> reasons;
        private final String snippet;
        private final List<String> matchedTerms;
        private final Integer lineHint;

        private RepoFileMatch(
                String path,
                int score,
                List<String> reasons,
                String snippet,
                List<String> matchedTerms,
                Integer lineHint
        ) {
            this.path = path;
            this.score = score;
            this.reasons = new ArrayList<>(reasons);
            this.snippet = snippet;
            this.matchedTerms = new ArrayList<>(matchedTerms);
            this.lineHint = lineHint;
        }

        private String path() {
            return path;
        }

        private int score() {
            return score;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", path);
            result.put("score", score);
            result.put("reasons", reasons);
            result.put("snippet", snippet);
            result.put("matchedTerms", matchedTerms);
            result.put("lineHint", lineHint);
            return result;
        }
    }

    private enum QuestionMode {
        GENERAL,
        REPO_OR_PIPELINE,
        SECRET_CONFIG
    }
}
