package org.qainsights.jmeter.ai.service.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;

import org.qainsights.jmeter.ai.service.attach.AttachmentMarkerParser;

/**
 * Renders a {@link ConversationSession} as a shareable document - Markdown for
 * pasting into tickets/reports, or a self-contained styled HTML page. File
 * markers ({@code [file:<id>]}) are replaced with the attachment's file name
 * so exports read naturally without the registry around.
 */
public final class ConversationExporter {

    /** Supported export formats with their file extensions. */
    public enum Format {
        MARKDOWN(".md"),
        HTML(".html");

        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }
    }

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private ConversationExporter() {
    }

    /** Writes the session to the target file in the given format. */
    public static void write(ConversationSession session, Path target, Format format) throws IOException {
        String content = format == Format.HTML ? toHtml(session) : toMarkdown(session);
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    /** Markdown rendering: a header block, then one section per turn. */
    public static String toMarkdown(ConversationSession session) {
        Map<String, String> names = attachmentNames(session);
        StringBuilder out = new StringBuilder();
        out.append("# Feather Wand conversation\n\n");
        if (!session.model().isEmpty()) {
            out.append("- Model: `").append(session.model()).append("`\n");
        }
        if (session.createdAt() > 0) {
            out.append("- Started: ").append(STAMP.format(Instant.ofEpochMilli(session.createdAt()))).append("\n");
        }
        for (ConversationSession.Turn turn : session.turns()) {
            out.append("\n## ").append("user".equals(turn.role()) ? "You" : "Feather Wand");
            if (turn.timestamp() > 0) {
                out.append(" — ").append(STAMP.format(Instant.ofEpochMilli(turn.timestamp())));
            }
            out.append("\n\n").append(substituteMarkers(turn.text(), names)).append("\n");
        }
        return out.toString();
    }

    /** Self-contained HTML page with inline CSS (no external references). */
    public static String toHtml(ConversationSession session) {
        Map<String, String> names = attachmentNames(session);
        StringBuilder out = new StringBuilder();
        out.append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>Feather Wand conversation</title>\n")
                .append("<style>")
                .append("body{font-family:sans-serif;max-width:52em;margin:2em auto;padding:0 1em;color:#222}")
                .append(".turn{margin:1em 0;padding:.6em 1em;border-radius:8px;white-space:pre-wrap}")
                .append(".user{background:#eef3fb}.assistant{background:#f6f6f6}")
                .append(".who{font-weight:bold;font-size:.85em;color:#555;margin-bottom:.3em}")
                .append("</style></head><body>\n");
        out.append("<h1>Feather Wand conversation</h1>\n");
        if (!session.model().isEmpty()) {
            out.append("<p>Model: <code>").append(escape(session.model())).append("</code></p>\n");
        }
        for (ConversationSession.Turn turn : session.turns()) {
            boolean user = "user".equals(turn.role());
            out.append("<div class=\"turn ").append(user ? "user" : "assistant").append("\">")
                    .append("<div class=\"who\">").append(user ? "You" : "Feather Wand");
            if (turn.timestamp() > 0) {
                out.append(" — ").append(STAMP.format(Instant.ofEpochMilli(turn.timestamp())));
            }
            out.append("</div>")
                    .append(escape(substituteMarkers(turn.text(), names)))
                    .append("</div>\n");
        }
        out.append("</body></html>\n");
        return out.toString();
    }

    private static Map<String, String> attachmentNames(ConversationSession session) {
        Map<String, String> names = new HashMap<>();
        for (ConversationSession.AttachmentSnapshot snapshot : session.attachments()) {
            names.put(snapshot.id(), snapshot.fileName());
        }
        return names;
    }

    private static String substituteMarkers(String text, Map<String, String> names) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher matcher = AttachmentMarkerParser.MARKER_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = names.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    name != null ? "[" + name + "]" : "[attachment]"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
