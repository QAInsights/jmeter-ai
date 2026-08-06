package org.qainsights.jmeter.ai.service.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConversationTracker}: autosave per turn, snapshot
 * consistency, session rotation, adoption of a restored session, and thread
 * safety across mixed EDT/worker callers.
 */
class ConversationTrackerTest {

    @TempDir
    Path tempDir;

    private ConversationTracker tracker() {
        return new ConversationTracker(new ConversationStore(tempDir.resolve("sessions")));
    }

    @Test
    void addTurnAutosavesSession() throws Exception {
        ConversationTracker tracker = tracker();
        String id = tracker.sessionId();
        tracker.addTurn("hello", "openai:gpt-5.1", List.of());
        tracker.addTurn("hi there", "openai:gpt-5.1", List.of());

        Path file = tempDir.resolve("sessions").resolve(id + ".json");
        assertTrue(Files.exists(file));
        String json = Files.readString(file);
        assertTrue(json.contains("hello"));
        assertTrue(json.contains("openai:gpt-5.1"));
    }

    @Test
    void snapshotPairsHistoryWithTimestamps() {
        ConversationTracker tracker = tracker();
        tracker.addTurn("one", "", List.of());
        tracker.addTurn("two", "", List.of());

        ConversationSession session = tracker.snapshot("m", List.of());
        assertEquals(tracker.sessionId(), session.id());
        assertEquals(List.of("one", "two"), session.toHistory());
        assertEquals(2, session.turnTimestamps().size());
        assertTrue(session.turnTimestamps().stream().allMatch(ts -> ts > 0));
    }

    @Test
    void rotateArchivesAndStartsFresh() {
        ConversationTracker tracker = tracker();
        tracker.addTurn("old", "", List.of());
        String oldId = tracker.sessionId();

        tracker.rotate("", List.of());

        assertNotEquals(oldId, tracker.sessionId());
        assertTrue(tracker.history().isEmpty());
        assertTrue(Files.exists(tempDir.resolve("sessions").resolve(oldId + ".json")));
        // the fresh session writes nothing until its first turn
        assertFalse(Files.exists(tempDir.resolve("sessions").resolve(tracker.sessionId() + ".json")));
    }

    @Test
    void adoptCarriesIdHistoryAndTimestamps() {
        ConversationTracker tracker = tracker();
        ConversationSession loaded = new ConversationSession("restored-1", 1234L, "openai:gpt-4o",
                List.of(new ConversationSession.Turn("user", "hi", 10L),
                        new ConversationSession.Turn("assistant", "hello", 20L)),
                List.of());

        tracker.adopt(loaded);

        assertEquals("restored-1", tracker.sessionId());
        assertEquals(List.of("hi", "hello"), tracker.history());
        ConversationSession session = tracker.snapshot("", List.of());
        assertEquals(1234L, session.createdAt());
        assertEquals(List.of(10L, 20L), session.turnTimestamps());
    }

    @Test
    void concurrentTurnsNeverLoseEntriesOrCorruptSnapshots() throws Exception {
        ConversationTracker tracker = tracker();
        int threads = 4, turnsPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            int thread = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < turnsPerThread; i++) {
                        tracker.addTurn("t" + thread + "-" + i, "m", List.of());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(threads * turnsPerThread, tracker.history().size());
        ConversationSession session = tracker.snapshot("m", List.of());
        // turns and timestamps stay the same length even under interleaved saves
        assertEquals(session.turns().size(), session.turnTimestamps().size());
    }
}
