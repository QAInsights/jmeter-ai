package org.qainsights.jmeter.ai.service.session;

import java.util.ArrayList;
import java.util.List;

import org.qainsights.jmeter.ai.service.attach.Attachment;

/**
 * Owns the live conversation state for the chat panel - the flat alternating
 * user/assistant history, per-turn timestamps, and the current session id -
 * and autosaves through a {@link ConversationStore} after every turn.
 * <p>
 * Extracted from {@code AiChatPanel} so all history mutation + snapshotting is
 * confined behind one lock: turns arrive from the EDT (user sends) and from
 * stream-completion worker threads (assistant replies), and an unsynchronized
 * ArrayList could be snapshotted mid-mutation. All public methods that touch
 * state are synchronized.
 * <p>
 * Legacy contract preserved: {@link #history()} returns the live internal list
 * because {@code CommandDispatcher} mutates it in place (e.g. rewriting the
 * last entry for {@code @this}). Callers that only read should prefer
 * {@link #historyCopy()}.
 */
public final class ConversationTracker {

    private final ConversationStore store;
    private final List<String> history = new ArrayList<>();
    private final List<Long> timestamps = new ArrayList<>();
    private String sessionId;
    private long createdAt;

    public ConversationTracker(ConversationStore store) {
        this.store = store;
        resetSessionState();
    }

    private void resetSessionState() {
        sessionId = ConversationStore.newSessionId();
        createdAt = System.currentTimeMillis();
    }

    /** The id of the active session (changes on {@link #rotate}). */
    public synchronized String sessionId() {
        return sessionId;
    }

    /** The live history list (legacy contract - see class javadoc). */
    public List<String> history() {
        return history;
    }

    /** A consistent snapshot of the current history. */
    public synchronized List<String> historyCopy() {
        return new ArrayList<>(history);
    }

    /** Appends a turn and autosaves the session. */
    public synchronized void addTurn(String entry, String model, List<Attachment> attachments) {
        history.add(entry);
        timestamps.add(System.currentTimeMillis());
        store.save(snapshotLocked(model, attachments));
    }

    /** A consistent snapshot of the session as it stands now. */
    public synchronized ConversationSession snapshot(String model, List<Attachment> attachments) {
        return snapshotLocked(model, attachments);
    }

    /** Saves the current session explicitly (no-op when empty). */
    public synchronized void save(String model, List<Attachment> attachments) {
        store.save(snapshotLocked(model, attachments));
    }

    /**
     * Archives the current session (its file is already on disk via autosave,
     * but we save once more for the final state) and starts a fresh, empty
     * session with a new id.
     */
    public synchronized void rotate(String model, List<Attachment> attachments) {
        save(model, attachments);
        history.clear();
        timestamps.clear();
        resetSessionState();
    }

    /** Adopts a session loaded from the store (id, history, timestamps). */
    public synchronized void adopt(ConversationSession session) {
        sessionId = session.id();
        createdAt = session.createdAt();
        history.clear();
        history.addAll(session.toHistory());
        timestamps.clear();
        timestamps.addAll(session.turnTimestamps());
    }

    private ConversationSession snapshotLocked(String model, List<Attachment> attachments) {
        return ConversationSession.fromHistory(sessionId, createdAt, model, history, timestamps, attachments);
    }
}
