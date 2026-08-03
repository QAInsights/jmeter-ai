package org.qainsights.jmeter.ai.service.attach;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code [file:<id>]} attachment markers embedded in user message
 * strings. Services use it (via {@link AttachmentRegistry#resolveInlineMarkers})
 * to substitute prepared content at request time; the UI uses it to strip
 * markers from displayed text and render file chips instead.
 */
public final class AttachmentMarkerParser {

    public static final Pattern MARKER_PATTERN = Pattern.compile("\\[file:([A-Za-z0-9]+)]");

    private AttachmentMarkerParser() {
    }

    /** The attachment ids referenced in the message, in order of appearance. */
    public static List<String> findMarkerIds(String text) {
        List<String> ids = new ArrayList<>();
        if (text == null) {
            return ids;
        }
        Matcher matcher = MARKER_PATTERN.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    /** True when the message contains at least one attachment marker. */
    public static boolean hasMarkers(String text) {
        return text != null && MARKER_PATTERN.matcher(text).find();
    }

    /**
     * Removes all markers from the message for display, collapsing the
     * whitespace they leave behind (the UI renders chips for them separately).
     */
    public static String stripMarkers(String text) {
        if (text == null) {
            return "";
        }
        return MARKER_PATTERN.matcher(text)
                .replaceAll("")
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .trim();
    }
}
