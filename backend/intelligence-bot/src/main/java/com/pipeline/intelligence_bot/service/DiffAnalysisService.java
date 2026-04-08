package com.pipeline.intelligence_bot.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DiffAnalysisService {

    private static final Pattern DIFF_HEADER =
            Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private static final Set<String> BUILD_FILES = Set.of(
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "package.json",
            "pyproject.toml",
            "requirements.txt",
            "CMakeLists.txt",
            "Makefile",
            "Dockerfile",
            ".gitlab-ci.yml"
    );

    public Map<String, Object> analyzeDiffForFailure(
            List<Map<String, Object>> diffs,
            String failureFile,
            String failureLineStr,
            String dependencyName
    ) {

        Map<String, Object> result = new HashMap<>();
        List<String> modifiedFiles = new ArrayList<>();
        boolean buildFileModified = false;

        for (Map<String, Object> diff : diffs) {
            String filePath = getPath(diff);

            if (!filePath.isEmpty()) {
                modifiedFiles.add(filePath);
            }

            if (failureFile != null && filePath.endsWith(failureFile)) {
                buildFileModified = true;
            }
        }

        if (failureFile == null || failureFile.isBlank()) {
            if (!modifiedFiles.isEmpty() && dependencyName != null) {
                return buildResult(
                        false,
                        dependencyName,
                        "INDIRECT",
                        null,
                        "Dependency-specific failure detected, but the exact file location was not extracted from logs",
                        "LOW",
                        modifiedFiles
                );
            }

            result.put("rootCauseCommitMatch", false);
            result.put("rootCausePrecision", "Unknown failure location");
            result.put("confidenceLevel", "LOW");
            result.put("modifiedFiles", modifiedFiles);
            return result;
        }

        int failureLine;

        try {
            failureLine = failureLineStr == null || failureLineStr.isBlank()
                    ? -1
                    : Integer.parseInt(failureLineStr);
        } catch (Exception exception) {
            failureLine = -1;
        }

        boolean buildFileFailure = isBuildFile(failureFile);

        for (Map<String, Object> diff : diffs) {
            String filePath = getPath(diff);

            if (!filePath.endsWith(failureFile)) {
                continue;
            }

            if (failureLine == -1) {
                return buildResult(
                        true,
                        filePath,
                        "FILE_MATCH",
                        null,
                        "Commit directly modified the extracted failure file",
                        buildFileFailure ? "VERY HIGH" : "HIGH",
                        modifiedFiles
                );
            }

            String diffText = getDiffText(diff);
            String[] lines = diffText.split("\n");
            int currentNewLine = -1;
            boolean insideBlock = false;

            for (String line : lines) {
                Matcher matcher = DIFF_HEADER.matcher(line);

                if (matcher.find()) {
                    currentNewLine = Integer.parseInt(matcher.group(1));
                    insideBlock = true;
                    continue;
                }

                if (!insideBlock) {
                    continue;
                }

                if (line.startsWith("-") && !line.startsWith("---")) {
                    if (currentNewLine == failureLine) {
                        return buildResult(
                                true,
                                filePath,
                                "REMOVED",
                                line.substring(1).trim(),
                                "Commit removed the line associated with the extracted failure location",
                                "CRITICAL",
                                modifiedFiles
                        );
                    }

                    continue;
                }

                if (line.startsWith("+") && !line.startsWith("+++")) {
                    if (currentNewLine == failureLine) {
                        return buildResult(
                                true,
                                filePath,
                                "ADDED",
                                line.substring(1).trim(),
                                "Commit added the line associated with the extracted failure location",
                                "CRITICAL",
                                modifiedFiles
                        );
                    }

                    currentNewLine++;
                    continue;
                }

                if (currentNewLine == failureLine) {
                    return buildResult(
                            true,
                            filePath,
                            "MODIFIED",
                            line.trim(),
                            "Commit modified the extracted failure line",
                            "VERY HIGH",
                            modifiedFiles
                    );
                }

                currentNewLine++;
            }
        }

        if (buildFileFailure) {
            if (buildFileModified) {
                String dependencyInfo = dependencyName == null
                        ? ""
                        : " involving dependency '" + dependencyName + "'";

                return buildResult(
                        true,
                        failureFile,
                        "BUILD_CONFIG",
                        null,
                        "Commit modified a build or pipeline configuration file" + dependencyInfo,
                        "VERY HIGH",
                        modifiedFiles
                );
            }

            return buildResult(
                    false,
                    failureFile,
                    "BUILD_CONFIG",
                    null,
                    "Failure originated from a configuration artifact, but the current commit did not touch that file directly",
                    "MEDIUM",
                    modifiedFiles
            );
        }

        if (!modifiedFiles.isEmpty()) {
            return buildResult(
                    false,
                    failureFile,
                    "INDIRECT",
                    null,
                    "The commit changed code, but the extracted failure location was not directly modified",
                    "LOW",
                    modifiedFiles
            );
        }

        result.put("rootCauseCommitMatch", false);
        result.put("rootCausePrecision", "Failure location not found in commit diff");
        result.put("confidenceLevel", "LOW");
        result.put("modifiedFiles", modifiedFiles);
        return result;
    }

    private boolean isBuildFile(String file) {
        for (String buildFile : BUILD_FILES) {
            if (file.endsWith(buildFile)) {
                return true;
            }
        }

        return false;
    }

    private String getPath(Map<String, Object> diff) {
        return diff.get("new_path") != null ? diff.get("new_path").toString() : "";
    }

    private String getDiffText(Map<String, Object> diff) {
        return diff.get("diff") != null ? diff.get("diff").toString() : "";
    }

    private Map<String, Object> buildResult(
            boolean commitMatch,
            String file,
            String changeType,
            String lineContent,
            String precision,
            String confidence,
            List<String> modifiedFiles
    ) {

        Map<String, Object> result = new HashMap<>();
        result.put("rootCauseCommitMatch", commitMatch);
        result.put("matchedFile", file);
        result.put("changeType", changeType);
        result.put("exactLineContent", lineContent);
        result.put("rootCausePrecision", precision);
        result.put("confidenceLevel", confidence);
        result.put("modifiedFiles", modifiedFiles);
        return result;
    }
}
