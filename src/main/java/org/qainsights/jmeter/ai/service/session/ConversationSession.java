package org.qainsights.jmeter.ai.service.session;

import java.util.ArrayList;
import java.util.List;

import org.qainsights.jmeter.ai.service.attach.Attachment;

/**
 * One chat conversation as a persistable value: the alternating user/assistant
 * turns (mirroring the panel's flat {@code List<String>} history), the model in
 * use, and snapshots of every attachment so restored sessions can re-resolve
 * {@code [file:<id>]} markers without the original files.
 * <p>
 * Turn roles derive from position (even = user, odd = assistant) because that
 * is the invariant {@code CommandDispatcher} maintains; a dangling final user
 * turn (e.g. a request that errored out) is preserved as-is.
 */
public final class ConversationSession {

    /** One turn of the conversation. Role is {@code "user"} or {@code "assistant"}. */
    public record Turn(String role, String text, long timestamp) {
    }

    /** The attachment data needed to re-register it after a restart. */
    public record AttachmentSnapshot(String id, String fileName, String mode, String content) {

        static AttachmentSnapshot of(Attachment attachment) {
            return new AttachmentSnapshot(
                    attachment.getId(),
                    attachment.getFileName(),
                    attachment.getMode().name().toLowerCase(java.util.Locale.ROOT),
                    // raw content, not prepared: preparation re-runs on restore
                    attachment.getRawContent());
        }
    }

    private final String id;
    private final long createdAt;
    private final String model;
    private final List<Turn> turns;
    private final List<AttachmentSnapshot> attachments;

    public ConversationSession(String id, long createdAt, String model,
            List<Turn> turns, List<AttachmentSnapshot> attachments) {
        this.id = id;
        this.createdAt = createdAt;
        this.model = model == null ? "" : model;
        this.turns = turns == null ? List.of() : List.copyOf(turns);
        this.attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /**
     * Builds a session from the panel's flat history (alternating user/assistant
     * strings) with a parallel timestamp list.
     */
    public static ConversationSession fromHistory(String id, long createdAt, String model,
            List<String> history, List<Long> timestamps, List<Attachment> attachments) {
        List<Turn> turns = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            String role = i % 2 == 0 ? "user" : "assistant";
            long ts = timestamps != null && i < timestamps.size() ? timestamps.get(i) : 0L;
            turns.add(new Turn(role, history.get(i), ts));
        }
        List<AttachmentSnapshot> snapshots = new ArrayList<>();
        if (attachments != null) {
            for (Attachment attachment : attachments) {
                snapshots.add(AttachmentSnapshot.of(attachment));
            }
        }
        return new ConversationSession(id, createdAt, model, turns, snapshots);
    }

    /** The flat alternating history list this session was built from. */
    public List<String> toHistory() {
        List<String> history = new ArrayList<>();
        for (Turn turn : turns) {
            history.add(turn.text());
        }
        return history;
    }

    /** Parallel-to-{@link #toHistory()} turn timestamps (0 when unknown). */
    public List<Long> turnTimestamps() {
        List<Long> times = new ArrayList<>();
        for (Turn turn : turns) {
            times.add(turn.timestamp());
        }
        return times;
    }

    public String id() {
        return id;
    }

    public long createdAt() {
        return createdAt;
    }

    public String model() {
        return model;
    }

    public List<Turn> turns() {
        return turns;
    }

    public List<AttachmentSnapshot> attachments() {
        return attachments;
    }
}
