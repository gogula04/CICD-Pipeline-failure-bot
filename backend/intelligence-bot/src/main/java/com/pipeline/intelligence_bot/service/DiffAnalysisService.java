package com.pipeline.intelligence_bot.service;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

@Service
public class DiffAnalysisService {

    private static final Pattern DIFF_HEADER =
            Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private static final Set<String> BUILD_FILES = Set.of(
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle"
    );

    public Map<String, Object> analyzeDiffForFailure(
            List<Map<String, Object>> diffs,
            String failureFile,
            String failureLineStr,
            String dependencyName) {

        Map<String, Object> result = new HashMap<>();

        if (failureFile == null || failureLineStr == null) {

            result.put("rootCauseCommitMatch", false);
            result.put("rootCausePrecision", "Unknown failure location");
            result.put("confidenceLevel", "LOW");

            return result;
        }

        int failureLine;

        try {
            failureLine = Integer.parseInt(failureLineStr);
        } catch (Exception e) {

            result.put("rootCauseCommitMatch", false);
            result.put("rootCausePrecision", "Invalid failure line format");
            result.put("confidenceLevel", "LOW");

            return result;
        }

        boolean buildFileFailure = isBuildFile(failureFile);

        List<String> modifiedFiles = new ArrayList<>();
        boolean buildFileModified = false;

        for (Map<String, Object> diff : diffs) {

            String filePath = getPath(diff);

            if (!filePath.isEmpty()) {
                modifiedFiles.add(filePath);
            }

            if (filePath.endsWith(failureFile)) {
                buildFileModified = true;
            }
        }



        for (Map<String, Object> diff : diffs) {

            String filePath = getPath(diff);

            if (!filePath.endsWith(failureFile)) {
                continue;
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
                                "Commit REMOVED required line causing failure at "
                                        + failureFile + ":" + failureLine,
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
                                "Commit ADDED failure-causing line at "
                                        + failureFile + ":" + failureLine,
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
                            "Commit MODIFIED line causing failure at "
                                    + failureFile + ":" + failureLine,
                            "VERY HIGH",
                            modifiedFiles
                    );
                }

                currentNewLine++;
            }
        }



        if (buildFileFailure) {

            if (buildFileModified) {

                String dependencyInfo =
                        dependencyName != null
                                ? " involving dependency '" + dependencyName + "'"
                                : "";

                return buildResult(
                        true,
                        failureFile,
                        "BUILD_CONFIG",
                        null,
                        "Commit modified build configuration file "
                                + failureFile + dependencyInfo
                                + " causing dependency/build failure",
                        "VERY HIGH",
                        modifiedFiles
                );
            }

            return buildResult(
                    false,
                    failureFile,
                    "BUILD_CONFIG",
                    null,
                    "Failure originates from build configuration (" + failureFile
                            + ") but commit did not modify that file directly",
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
                    "Commit modified source files but failure location "
                            + failureFile
                            + " was not directly modified",
                    "LOW",
                    modifiedFiles
            );
        }



        result.put("rootCauseCommitMatch", false);

        result.put(
                "rootCausePrecision",
                "Failure location not found in commit diff"
        );

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

        return diff.get("new_path") != null
                ? diff.get("new_path").toString()
                : "";
    }

    private String getDiffText(Map<String, Object> diff) {

        return diff.get("diff") != null
                ? diff.get("diff").toString()
                : "";
    }

    private Map<String, Object> buildResult(
            boolean commitMatch,
            String file,
            String changeType,
            String lineContent,
            String precision,
            String confidence,
            List<String> modifiedFiles) {

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