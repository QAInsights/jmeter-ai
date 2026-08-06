package org.qainsights.jmeter.ai.service.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConversationStore}: atomic save/load round-trip,
 * most-recent selection, resilience to corrupt files, empty-session skip, and
 * directory pruning.
 */
class ConversationStoreTest {

    @TempDir
    Path tempDir;

    private Path sessionsDir() {
        return tempDir.resolve("sessions");
    }

    private static ConversationSession session(String id, long createdAt) {
        return ConversationSession.fromHistory(id, createdAt, "openai:gpt-5.1",
                List.of("hello " + id, "hi " + id), List.of(1L, 2L), List.of());
    }

    @Test
    void saveLoadRoundTrip() {
        ConversationStore store = new ConversationStore(sessionsDir());
        store.save(session("s1", 1000L));

        Optional<ConversationSession> loaded = store.loadMostRecent();
        assertTrue(loaded.isPresent());
        assertEquals("s1", loaded.get().id());
        assertEquals(1000L, loaded.get().createdAt());
        assertEquals("openai:gpt-5.1", loaded.get().model());
        assertEquals(List.of("hello s1", "hi s1"), loaded.get().toHistory());
        assertEquals(List.of(1L, 2L), loaded.get().turnTimestamps());
    }

    @Test
    void emptySessionIsNotSaved() {
        ConversationStore store = new ConversationStore(sessionsDir());
        store.save(ConversationSession.fromHistory("empty", 0L, "", List.of(), List.of(), List.of()));
        store.save(null);
        assertTrue(store.loadMostRecent().isEmpty());
        assertFalse(Files.exists(sessionsDir()));
    }

    @Test
    void mostRecentWins() throws Exception {
        ConversationStore store = new ConversationStore(sessionsDir());
        store.save(session("older", 1000L));
        store.save(session("newer", 2000L));
        // ensure a deterministic mtime ordering regardless of filesystem granularity
        Files.setLastModifiedTime(sessionsDir().resolve("older.json"),
                java.nio.file.attribute.FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(sessionsDir().resolve("newer.json"),
                java.nio.file.attribute.FileTime.fromMillis(2000L));

        assertEquals("newer", store.loadMostRecent().orElseThrow().id());
    }

    @Test
    void reSaveKeepsSameFile() throws Exception {
        ConversationStore store = new ConversationStore(sessionsDir());
        store.save(session("s1", 1000L));
        store.save(ConversationSession.fromHistory("s1", 1000L, "openai:gpt-5.1",
                List.of("hello s1", "hi s1", "follow-up"), List.of(1L, 2L, 3L), List.of()));

        try (Stream<Path> files = Files.list(sessionsDir())) {
            assertEquals(1, files.filter(f -> f.toString().endsWith(".json")).count());
        }
        assertEquals(3, store.loadMostRecent().orElseThrow().turns().size());
    }

    @Test
    void corruptFilesAreSkipped() throws Exception {
        ConversationStore store = new ConversationStore(sessionsDir());
        store.save(session("good", 1000L));
        Files.writeString(sessionsDir().resolve("broken.json"), "{not json");

        Optional<ConversationSession> loaded = store.loadMostRecent();
        assertTrue(loaded.isPresent());
        assertEquals("good", loaded.get().id());
    }

    @Test
    void missingDirectoryYieldsEmpty() {
        ConversationStore store = new ConversationStore(tempDir.resolve("nope"));
        assertTrue(store.loadMostRecent().isEmpty());
    }

    @Test
    void attachmentsSurviveRoundTrip() {
        ConversationStore store = new ConversationStore(sessionsDir());
        ConversationSession original = new ConversationSession("s1", 1000L, "",
                List.of(new ConversationSession.Turn("user", "check [file:f1]", 5L)),
                List.of(new ConversationSession.AttachmentSnapshot("f1", "jmeter.log", "smart", "body")));
        store.save(original);

        ConversationSession loaded = store.loadMostRecent().orElseThrow();
        assertEquals(1, loaded.attachments().size());
        assertEquals("jmeter.log", loaded.attachments().get(0).fileName());
        assertEquals("smart", loaded.attachments().get(0).mode());
        assertEquals("body", loaded.attachments().get(0).content());
    }

    @Test
    void pruningKeepsOnlyMaxSessions() throws Exception {
        ConversationStore store = new ConversationStore(sessionsDir());
        for (int i = 0; i < ConversationStore.MAX_SESSIONS + 5; i++) {
            store.save(session(String.format("s%03d", i), i));
        }
        try (Stream<Path> files = Files.list(sessionsDir())) {
            assertEquals(ConversationStore.MAX_SESSIONS,
                    files.filter(f -> f.toString().endsWith(".json")).count());
        }
    }

    @Test
    void unsafeSessionIdsAreNotSaved() {
        ConversationStore store = new ConversationStore(sessionsDir());
        store.save(session("../../evil", 1000L));
        store.save(session("..", 1000L));
        store.save(session("a/b", 1000L));

        assertTrue(store.loadMostRecent().isEmpty());
        assertFalse(Files.exists(sessionsDir()));
        assertFalse(Files.exists(tempDir.resolve("evil.json")));
    }

    @Test
    void craftedIdInJsonIsNeutralizedOnLoad() throws Exception {
        Files.createDirectories(sessionsDir());
        Files.writeString(sessionsDir().resolve("planted.json"), """
                {"id":"../../evil","createdAt":1,"model":"",
                 "turns":[{"role":"user","text":"hi","ts":1}],"attachments":[]}
                """);

        ConversationStore store = new ConversationStore(sessionsDir());
        ConversationSession loaded = store.loadMostRecent().orElseThrow();
        assertNotEquals("../../evil", loaded.id());
        assertTrue(ConversationStore.isValidSessionId(loaded.id()));
        // and the neutralized session can be saved safely inside the directory
        store.save(loaded);
        try (Stream<Path> files = Files.list(sessionsDir())) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().equals(loaded.id() + ".json")));
        }
        assertFalse(Files.exists(tempDir.resolve("evil.json")));
    }

    @Test
    void sessionIdValidation() {
        assertTrue(ConversationStore.isValidSessionId("20260806-143211-1a2b"));
        assertTrue(ConversationStore.isValidSessionId("session.backup-1"));
        assertFalse(ConversationStore.isValidSessionId("../escape"));
        assertFalse(ConversationStore.isValidSessionId(".."));
        assertFalse(ConversationStore.isValidSessionId("."));
        assertFalse(ConversationStore.isValidSessionId("a/b"));
        assertFalse(ConversationStore.isValidSessionId("a\\b"));
        assertFalse(ConversationStore.isValidSessionId(null));
        assertFalse(ConversationStore.isValidSessionId(""));
    }

    @Test
    void openHonoursDirPropertyOverride() {
        System.setProperty(ConversationStore.DIR_PROPERTY, sessionsDir().toString());
        try {
            ConversationStore store = ConversationStore.open();
            assertEquals(sessionsDir(), store.directory());
        } finally {
            System.clearProperty(ConversationStore.DIR_PROPERTY);
        }
    }

    @Test
    void newSessionIdsAreUnique() {
        assertNotEquals(ConversationStore.newSessionId(), ConversationStore.newSessionId());
    }
}
