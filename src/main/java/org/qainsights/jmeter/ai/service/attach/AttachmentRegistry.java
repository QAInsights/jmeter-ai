package org.qainsights.jmeter.ai.service.attach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Session-scoped store for attached files. Attachments are registered with a
 * short id, referenced inside user messages as {@code [file:<id>]} markers
 * (which ride along in conversation history for free), and resolved back to
 * their prepared content at request-build time via {@link #resolveInlineMarkers}.
 * <p>
 * One instance is owned by the chat panel - no global singleton, so tests stay
 * independent and a "new conversation" simply clears it.
 */
public class AttachmentRegistry {

    public static final String MAX_COUNT_PROPERTY = "jmeter.ai.file.max.count";
    public static final int DEFAULT_MAX_COUNT = 3;

    private final Map<String, Attachment> byId = new LinkedHashMap<>();
    /** Ids registered but not yet consumed by a send - the "per message" view. */
    private final List<String> pendingIds = new ArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();

    /** The per-message attachment cap from {@code jmeter.ai.file.max.count} (default 3). */
    public static int maxCount() {
        try {
            return Math.max(1, Integer.parseInt(
                    AiConfig.getProperty(MAX_COUNT_PROPERTY, String.valueOf(DEFAULT_MAX_COUNT)).trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_COUNT;
        }
    }

    /**
     * Thread safety: all mutating/iterating methods are synchronized because
     * the EDT (attachment bar clicks, chip removal) and request-builder
     * background threads (marker resolution in SwingWorker) share this
     * instance. Contention is negligible - EDT operations are single clicks
     * and resolution holds the lock only while substituting one message.
     */

    /** True when another attachment may be added for the NEXT message (per-message cap). */
    public synchronized boolean canAddMore() {
        return pendingIds.size() < maxCount();
    }

    /**
     * Registers a file attachment (prepared in the given mode).
     *
     * @throws IllegalStateException when the per-message cap is reached
     */
    public synchronized Attachment register(String fileName, String content, FileContentPreparer.Mode mode) {
        if (!canAddMore()) {
            throw new IllegalStateException(
                    "Attachment limit reached (" + maxCount() + " per message)");
        }
        Attachment attachment = new Attachment("f" + counter.incrementAndGet(), fileName, content, mode);
        byId.put(attachment.getId(), attachment);
        pendingIds.add(attachment.getId());
        return attachment;
    }

    /** The attachment with the given id, or null. */
    public synchronized Attachment find(String id) {
        return byId.get(id);
    }

    /** Removes and returns the attachment with the given id (null when absent). */
    public synchronized Attachment remove(String id) {
        pendingIds.remove(id);
        return byId.remove(id);
    }

    /**
     * The attachments registered since the last consume, in registration order,
     * and marks them consumed. Consumed attachments stay registered so their
     * markers in history keep resolving; only the next message's marker set shrinks.
     */
    public synchronized List<Attachment> consumePending() {
        List<Attachment> pending = new ArrayList<>();
        for (String id : pendingIds) {
            Attachment attachment = byId.get(id);
            if (attachment != null) {
                pending.add(attachment);
            }
        }
        pendingIds.clear();
        return pending;
    }

    /** Number of unconsumed attachments (for the next message). */
    public synchronized int pendingCount() {
        return pendingIds.size();
    }

    public synchronized int size() {
        return byId.size();
    }

    /** All attachments in registration order. */
    public synchronized List<Attachment> all() {
        return List.copyOf(byId.values());
    }

    /** Drops every attachment (used by "new conversation"). */
    public synchronized void clear() {
        byId.clear();
        pendingIds.clear();
    }

    /**
     * Replaces every {@code [file:<id>]} marker in the message with the
     * attachment's prepared content. Unknown ids (e.g. after history trimming
     * or a cleared registry) are stripped and replaced with a small note so the
     * model isn't confused by dangling markers.
     */
    public synchronized String resolveInlineMarkers(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        java.util.regex.Matcher matcher = AttachmentMarkerParser.MARKER_PATTERN.matcher(message);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            Attachment attachment = byId.get(matcher.group(1));
            String replacement = attachment != null
                    ? "\n" + attachment.getPreparedContent() + "\n"
                    : " [attachment no longer available] ";
            matcher.appendReplacement(resolved,
                    java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }
}
