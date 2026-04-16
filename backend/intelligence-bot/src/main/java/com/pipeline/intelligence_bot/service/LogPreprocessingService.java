package com.pipeline.intelligence_bot.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class LogPreprocessingService {

    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[;\\d]*[ -/]*[@-~]");

    private static final List<Pattern> HIGH_SIGNAL_PATTERNS = List.of(
            Pattern.compile("(?i)cannot find symbol"),
            Pattern.compile("(?i)compilation error"),
            Pattern.compile("(?i)build failure"),
            Pattern.compile("(?i)test failed"),
            Pattern.compile("(?i)assertion failed"),
            Pattern.compile("(?i)assertionerror"),
            Pattern.compile("(?i)duplicate entry"),
            Pattern.compile("(?i)unique constraint"),
            Pattern.compile("(?i)sqlstate"),
            Pattern.compile("(?i)permission denied"),
            Pattern.compile("(?i)connection refused"),
            Pattern.compile("(?i)timed out"),
            Pattern.compile("(?i)timeout"),
            Pattern.compile("(?i)not found"),
            Pattern.compile("(?i)no such file or directory"),
            Pattern.compile("(?i)must be unique"),
            Pattern.compile("(?i)duplicate declaration"),
            Pattern.compile("(?i)runner system failure"),
            Pattern.compile("(?i)segmentation fault"),
            Pattern.compile("(?i)out of memory"),
            Pattern.compile("(?i)failed to execute goal"),
            Pattern.compile("(?i)error:")
    );

    private static final int CONTEXT_RADIUS = 4;
    private static final int MAX_HIGH_SIGNAL_LINES = 36;
    private static final int MAX_CLEANED_EXCERPT_CHARS = 12000;

    public Map<String, Object> preprocess(String logs) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (logs == null || logs.isBlank()) {
            result.put("lineCount", 0);
            result.put("signalCount", 0);
            result.put("firstSignalLine", null);
            result.put("highSignalLines", List.of());
            result.put("contextWindow", List.of());
            result.put("cleanedLogExcerpt", "");
            result.put("truncated", false);
            result.put("summary", "No log content was provided.");
            return result;
        }

        String cleaned = stripAnsi(logs).replace("\r\n", "\n").replace('\r', '\n');
        String[] rawLines = cleaned.split("\n", -1);
        List<String> lines = new ArrayList<>(rawLines.length);

        for (String line : rawLines) {
            lines.add(line);
        }

        List<String> highSignalLines = new ArrayList<>();
        Set<String> seenSignals = new LinkedHashSet<>();
        Integer firstSignalIndex = null;

        for (int index = 0; index < lines.size(); index++) {
            String trimmed = lines.get(index).trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            if (!isHighSignal(trimmed)) {
                continue;
            }

            String normalized = trimmed.toLowerCase(Locale.ROOT);

            if (seenSignals.add(normalized)) {
                highSignalLines.add(formatLine(index + 1, trimmed));
            }

            if (firstSignalIndex == null) {
                firstSignalIndex = index;
            }

            if (highSignalLines.size() >= MAX_HIGH_SIGNAL_LINES) {
                break;
            }
        }

        List<String> contextWindow = buildContextWindow(lines, firstSignalIndex);
        String cleanedExcerpt = truncate(cleaned, MAX_CLEANED_EXCERPT_CHARS);

        result.put("lineCount", lines.size());
        result.put("signalCount", highSignalLines.size());
        result.put("firstSignalLine", firstSignalIndex == null ? null : firstSignalIndex + 1);
        result.put("highSignalLines", highSignalLines);
        result.put("contextWindow", contextWindow);
        result.put("cleanedLogExcerpt", cleanedExcerpt);
        result.put("truncated", cleaned.length() > cleanedExcerpt.length());
        result.put("summary", buildSummary(lines.size(), highSignalLines.size(), firstSignalIndex));

        return result;
    }

    private String stripAnsi(String value) {
        return ANSI_ESCAPE.matcher(value == null ? "" : value).replaceAll("");
    }

    private boolean isHighSignal(String line) {
        for (Pattern pattern : HIGH_SIGNAL_PATTERNS) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }

        return false;
    }

    private List<String> buildContextWindow(List<String> lines, Integer firstSignalIndex) {
        if (firstSignalIndex == null || lines.isEmpty()) {
            return List.of();
        }

        int start = Math.max(0, firstSignalIndex - CONTEXT_RADIUS);
        int end = Math.min(lines.size(), firstSignalIndex + CONTEXT_RADIUS + 1);
        List<String> context = new ArrayList<>();

        for (int index = start; index < end; index++) {
            String trimmed = lines.get(index).trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            context.add(formatLine(index + 1, trimmed));
        }

        return context;
    }

    private String formatLine(int lineNumber, String line) {
        return lineNumber + " | " + line;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }

        return value.substring(0, maxChars);
    }

    private String buildSummary(int lineCount, int signalCount, Integer firstSignalIndex) {
        if (signalCount == 0) {
            return "Preprocessed " + lineCount + " log lines and found no strong failure signature.";
        }

        return "Preprocessed "
                + lineCount
                + " log lines, extracted "
                + signalCount
                + " high-signal lines, first signal at line "
                + (firstSignalIndex == null ? "unknown" : firstSignalIndex + 1)
                + ".";
    }
}
