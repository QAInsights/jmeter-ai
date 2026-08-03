package org.qainsights.jmeter.ai.service.attach;

import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Prepares an attached text file for inlining into an AI prompt. Two modes,
 * selectable per file (default from {@code jmeter.ai.file.mode}):
 * <ul>
 *   <li><b>smart</b> - the default: .jtl results go through {@link JtlSummarizer}
 *       (aggregates instead of raw rows), logs go through {@link LogDigester}
 *       (ERROR/WARN lines, first/last, exceptions), other text gets a head+tail
 *       excerpt within the character budget.</li>
 *   <li><b>raw</b> - head + tail of the file up to
 *       {@code jmeter.ai.file.max.chars} (default 50000) with an explicit
 *       truncation marker.</li>
 * </ul>
 * The prepared text is wrapped as
 * {@code <attached file="name" mode="smart|raw">…</attached>} so the model
 * knows the provenance of the excerpt.
 */
public final class FileContentPreparer {

    public static final String MODE_PROPERTY = "jmeter.ai.file.mode";
    public static final String MAX_CHARS_PROPERTY = "jmeter.ai.file.max.chars";
    public static final int DEFAULT_MAX_CHARS = 50000;
    private static final double HEAD_RATIO = 0.7;

    /** Per-file processing mode. */
    public enum Mode {
        SMART,
        RAW;

        static Mode parse(String value) {
            return "raw".equalsIgnoreCase(value == null ? "" : value.trim()) ? RAW : SMART;
        }

        String label() {
            return this == RAW ? "raw" : "smart";
        }
    }

    private FileContentPreparer() {
    }

    /** The default mode from {@code jmeter.ai.file.mode} (smart unless set to raw). */
    public static Mode defaultMode() {
        return Mode.parse(AiConfig.getProperty(MODE_PROPERTY, "smart"));
    }

    /** The character budget for excerpts from {@code jmeter.ai.file.max.chars}. */
    public static int maxChars() {
        try {
            return Math.max(1000, Integer.parseInt(
                    AiConfig.getProperty(MAX_CHARS_PROPERTY, String.valueOf(DEFAULT_MAX_CHARS)).trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_CHARS;
        }
    }

    /**
     * Prepares a file's content for inlining.
     *
     * @param fileName the attachment's file name (used for type detection and the wrapper)
     * @param content  the raw file content
     * @param mode     smart or raw processing
     * @return the wrapped, prepared text
     */
    public static String prepare(String fileName, String content, Mode mode) {
        if (content == null) {
            content = "";
        }
        Mode effectiveMode = mode == null ? defaultMode() : mode;
        int budget = maxChars();
        String body;
        if (effectiveMode == Mode.RAW) {
            body = headTail(content, budget);
        } else if (JtlSummarizer.looksLikeJtl(fileName, content)) {
            body = JtlSummarizer.summarize(bounded(content, budget)) + truncationNote(content, budget);
        } else if (LogDigester.looksLikeLog(fileName, content)) {
            body = LogDigester.digest(bounded(content, budget)) + truncationNote(content, budget);
        } else {
            body = headTail(content, budget);
        }
        return "<attached file=\"" + sanitizeName(fileName)
                + "\" mode=\"" + effectiveMode.label() + "\">\n" + body + "\n</attached>";
    }

    /**
     * Makes a file name safe to embed in the {@code <attached file="...">}
     * provenance wrapper: quotes, angle brackets, and newlines become
     * underscores (they could otherwise break out of the pseudo-attribute and
     * smuggle instruction-like text into the prompt), and the name is capped
     * at 100 chars.
     */
    static String sanitizeName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "file";
        }
        String safe = fileName.replaceAll("[\"<>\r\n]", "_");
        return safe.length() <= 100 ? safe : safe.substring(0, 100);
    }

    /** First-budget excerpt of the content, snapped to a line boundary (for the smart digests). */
    static String bounded(String content, int budget) {
        if (content.length() <= budget) {
            return content;
        }
        String excerpt = content.substring(0, budget);
        int lastNl = excerpt.lastIndexOf('\n');
        if (lastNl > 0) {
            return excerpt.substring(0, lastNl);
        }
        // no newline to snap to: at least don't split a surrogate pair
        return dropTrailingHighSurrogate(excerpt);
    }

    /** Drops a dangling high surrogate at the end of a cut (avoids broken emoji at boundaries). */
    private static String dropTrailingHighSurrogate(String text) {
        return !text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1))
                ? text.substring(0, text.length() - 1)
                : text;
    }

    /** Drops a dangling low surrogate at the start of a cut. */
    private static String dropLeadingLowSurrogate(String text) {
        return !text.isEmpty() && Character.isLowSurrogate(text.charAt(0))
                ? text.substring(1)
                : text;
    }

    /** A note appended to smart digests when the input exceeded the budget. */
    private static String truncationNote(String content, int budget) {
        return content.length() <= budget
                ? ""
                : "\n[Digest covers only the first ~" + (budget / 1000) + " KB of the file - raise "
                        + MAX_CHARS_PROPERTY + " to include more.]";
    }

    /** Convenience overload using the property-backed default mode. */
    public static String prepare(String fileName, String content) {
        return prepare(fileName, content, null);
    }

    /**
     * Head + tail excerpt: whole content when it fits the budget, otherwise the
     * first ~70% and last ~30% of lines with a truncation marker between.
     */
    static String headTail(String content, int budget) {
        if (content.length() <= budget) {
            return content;
        }
        int headChars = (int) (budget * HEAD_RATIO);
        int tailChars = budget - headChars;
        String head = content.substring(0, headChars);
        String tail = content.substring(content.length() - tailChars);
        // snap to line boundaries so we don't cut mid-line
        int headNl = head.lastIndexOf('\n');
        if (headNl > 0) {
            head = head.substring(0, headNl);
        }
        int tailNl = tail.indexOf('\n');
        if (tailNl >= 0 && tailNl + 1 < tail.length()) {
            tail = tail.substring(tailNl + 1);
        }
        head = dropTrailingHighSurrogate(head);
        tail = dropLeadingLowSurrogate(tail);
        long skippedLines = content.substring(head.length(), content.length() - tail.length())
                .lines().count();
        return head + "\n[... truncated " + skippedLines + " lines ...]\n" + tail;
    }
}
