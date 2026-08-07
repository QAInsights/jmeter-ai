package org.qainsights.jmeter.ai.service.session;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.attach.Attachment;
import org.qainsights.jmeter.ai.service.attach.FileContentPreparer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConversationSession}: history conversion, role
 * alternation, timestamps, and attachment snapshots.
 */
class ConversationSessionTest {

    @Test
    void fromHistoryAlternatesRolesAndKeepsTimestamps() {
        ConversationSession session = ConversationSession.fromHistory(
                "s1", 1000L, "openai:gpt-5.1",
                List.of("hello", "hi there", "how are you"),
                List.of(10L, 20L, 30L),
                List.of());

        assertEquals(3, session.turns().size());
        assertEquals("user", session.turns().get(0).role());
        assertEquals("assistant", session.turns().get(1).role());
        assertEquals("user", session.turns().get(2).role());
        assertEquals(20L, session.turns().get(1).timestamp());
        assertEquals("openai:gpt-5.1", session.model());
        assertEquals(1000L, session.createdAt());
    }

    @Test
    void missingTimestampsDefaultToZero() {
        ConversationSession session = ConversationSession.fromHistory(
                "s1", 0L, "", List.of("hello", "hi"), List.of(), List.of());
        assertEquals(0L, session.turns().get(0).timestamp());
    }

    @Test
    void toHistoryRoundTrips() {
        List<String> history = List.of("hello", "hi there");
        ConversationSession session = ConversationSession.fromHistory(
                "s1", 0L, null, history, null, null);
        assertEquals(history, session.toHistory());
        assertEquals("", session.model());
        assertEquals(List.of(0L, 0L), session.turnTimestamps());
    }

    @Test
    void attachmentSnapshotCapturesRawContentAndMode() {
        Attachment attachment = new Attachment("f1", "jmeter.log", "line1\nline2",
                FileContentPreparer.Mode.RAW);
        ConversationSession session = ConversationSession.fromHistory(
                "s1", 0L, "", List.of("check [file:f1]"), List.of(1L), List.of(attachment));

        assertEquals(1, session.attachments().size());
        ConversationSession.AttachmentSnapshot snapshot = session.attachments().get(0);
        assertEquals("f1", snapshot.id());
        assertEquals("jmeter.log", snapshot.fileName());
        assertEquals("raw", snapshot.mode());
        assertEquals("line1\nline2", snapshot.content());
    }

    @Test
    void danglingUserTurnIsPreserved() {
        ConversationSession session = ConversationSession.fromHistory(
                "s1", 0L, "", List.of("hello", "hi", "unanswered"), List.of(), List.of());
        assertEquals(List.of("hello", "hi", "unanswered"), session.toHistory());
        assertEquals("user", session.turns().get(2).role());
    }
}
