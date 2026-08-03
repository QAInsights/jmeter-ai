package org.qainsights.jmeter.ai.service.attach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw log file (jmeter.log and friends) into a compact markdown
 * digest for AI analysis: total line counts, first/last lines for context,
 * ERROR and WARN lines (capped), counts by logger, and OutOfMemory /
 * stacktrace extraction. Raw logs are far too large to inline into a prompt;
 * most "why did my test fail" questions live in the ERROR lines.
 */
public final class LogDigester {

    private static final int FIRST_LINES = 15;
    private static final int LAST_LINES = 10;
    private static final int MAX_ERROR_WARN = 40;
    private static final int MAX_LOGGERS = 10;
    private static final int MAX_EXCEPTIONS = 3;
    private static final int MAX_STACK_LINES = 5;

    private static final Pattern LEVEL_PATTERN = Pattern.compile("\\b(ERROR|WARN)\\b");
    private static final Pattern LOGGER_PATTERN = Pattern.compile("\\b(?:ERROR|WARN|INFO)\\s+([\\w.$]+):");
    private static final Pattern EXCEPTION_PATTERN =
            Pattern.compile("(?:^|\\s)([\\w.$]+(?:Exception|Error))(:\\s*.*)?$");

    private LogDigester() {
    }

    /** True when the content looks like a log file (timestamped level lines or a .log name). */
    public static boolean looksLikeLog(String fileName, String content) {
        if (fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".log")) {
            return true;
        }
        if (content == null) {
            return false;
        }
        return content.lines().limit(20)
                .filter(line -> line.contains(" INFO ") || line.contains(" ERROR ") || line.contains(" WARN "))
                .count() >= 3;
    }

    /** Digests raw log content into a compact markdown summary. */
    public static String digest(String content) {
        List<String> lines = content.lines().toList();
        StringBuilder sb = new StringBuilder();
        sb.append("## Log digest (").append(lines.size()).append(" lines total)\n");

        int errorCount = 0;
        int warnCount = 0;
        List<String> errorWarnLines = new ArrayList<>();
        Map<String, Integer> byLogger = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher level = LEVEL_PATTERN.matcher(line);
            if (level.find()) {
                if ("ERROR".equals(level.group(1))) {
                    errorCount++;
                } else {
                    warnCount++;
                }
                if (errorWarnLines.size() < MAX_ERROR_WARN) {
                    errorWarnLines.add(line);
                }
                Matcher logger = LOGGER_PATTERN.matcher(line);
                if (logger.find()) {
                    byLogger.merge(logger.group(1), 1, Integer::sum);
                }
            }
        }
        sb.append("- ERROR x ").append(errorCount).append(", WARN x ").append(warnCount).append('\n');

        if (!byLogger.isEmpty()) {
            sb.append("\n## Counts by logger\n");
            byLogger.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(MAX_LOGGERS)
                    .forEach(e -> sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n'));
        }

        if (!errorWarnLines.isEmpty()) {
            sb.append("\n## ERROR/WARN lines");
            if (errorCount + warnCount > errorWarnLines.size()) {
                sb.append(" (first ").append(errorWarnLines.size()).append(" of ")
                        .append(errorCount + warnCount).append(')');
            }
            sb.append('\n');
            for (String line : errorWarnLines) {
                sb.append(line).append('\n');
            }
        }

        appendExceptions(sb, lines);

        sb.append("\n## First lines\n");
        lines.stream().limit(FIRST_LINES).forEach(line -> sb.append(line).append('\n'));
        sb.append("\n## Last lines\n");
        lines.stream().skip(Math.max(0, lines.size() - LAST_LINES))
                .forEach(line -> sb.append(line).append('\n'));
        return sb.toString().trim();
    }

    private static void appendExceptions(StringBuilder sb, List<String> lines) {
        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < lines.size() && blocks.size() < MAX_EXCEPTIONS; i++) {
            String line = lines.get(i);
            if (line.contains("OutOfMemoryError")) {
                blocks.add(line);
                continue;
            }
            Matcher matcher = EXCEPTION_PATTERN.matcher(line.trim());
            if (matcher.find() && !line.trim().startsWith("at ")) {
                StringBuilder block = new StringBuilder(line);
                int stackLines = 0;
                for (int j = i + 1; j < lines.size() && stackLines < MAX_STACK_LINES; j++) {
                    String stackLine = lines.get(j);
                    if (stackLine.trim().startsWith("at ")) {
                        block.append('\n').append(stackLine);
                        stackLines++;
                    } else {
                        break;
                    }
                }
                blocks.add(block.toString());
                i += stackLines;
            }
        }
        if (!blocks.isEmpty()) {
            sb.append("\n## Exceptions (OutOfMemory / stacktraces)\n");
            for (String block : blocks) {
                sb.append(block).append('\n');
            }
        }
    }
}
