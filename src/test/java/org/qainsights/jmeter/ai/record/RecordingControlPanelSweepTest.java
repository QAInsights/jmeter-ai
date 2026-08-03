package org.qainsights.jmeter.ai.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the retention sweep in {@link RecordingControlPanel}: expired
 * session directories are deleted before a new session starts, fresh ones
 * survive, and a cleanup failure never escapes to block recording.
 */
class RecordingControlPanelSweepTest {

    @TempDir
    Path tempDir;

    @Test
    void sweepDeletesOnlyExpiredSessions() throws Exception {
        Path expired = Files.createDirectories(tempDir.resolve("old-session"));
        Path fresh = Files.createDirectories(tempDir.resolve("new-session"));
        // age the old one beyond the 7-day retention window
        FileTime eightDaysAgo = FileTime.fromMillis(
                System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000);
        Files.setLastModifiedTime(expired, eightDaysAgo);

        RecordingArtifactStore store = new RecordingArtifactStore(tempDir.toString(), 7);
        RecordingControlPanel panel = new RecordingControlPanel(
                RecordingSessionController.getInstance(), store);

        panel.sweepExpiredArtifacts();

        assertFalse(Files.exists(expired), "expired session must be deleted");
        assertTrue(Files.exists(fresh), "fresh session must survive");
    }

    @Test
    void sweepFailureDoesNotEscape() throws Exception {
        // a store whose root is a FILE: cleanExpiredArtifacts throws NotDirectoryException
        Path fileRoot = Files.writeString(tempDir.resolve("not-a-dir"), "x");
        RecordingArtifactStore brokenStore = new RecordingArtifactStore(fileRoot.toString(), 7);
        RecordingControlPanel panel = new RecordingControlPanel(
                RecordingSessionController.getInstance(), brokenStore);

        assertDoesNotThrow(panel::sweepExpiredArtifacts,
                "a cleanup failure must be logged, never thrown");
    }
}
