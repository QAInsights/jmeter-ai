package org.qainsights.jmeter.ai.record;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RecordingArtifactStore}.
 */
class RecordingArtifactStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void should_createSessionDir_when_validSessionId() throws IOException {
        RecordingArtifactStore store = new RecordingArtifactStore(tempDir.toString(), 7);
        String sessionId = UUID.randomUUID().toString();
        Path sessionDir = store.getSessionDirectory(sessionId);

        assertTrue(Files.exists(sessionDir));
        assertEquals(tempDir.resolve(sessionId).toAbsolutePath(), sessionDir.toAbsolutePath());
    }

    @Test
    void should_throwException_when_pathTraversalAttempted() {
        RecordingArtifactStore store = new RecordingArtifactStore(tempDir.toString(), 7);
        String badSessionId = "../outside-dir";

        assertThrows(SecurityException.class, () -> store.getSessionDirectory(badSessionId));
    }

    @Test
    void should_writeMetadataAtomically_when_called() throws IOException {
        RecordingArtifactStore store = new RecordingArtifactStore(tempDir.toString(), 7);
        String sessionId = UUID.randomUUID().toString();
        String json = "{\"test\": true}";

        store.writeSessionMetadata(sessionId, json);
        Path metadataFile = store.getSessionDirectory(sessionId).resolve("session.json");

        assertTrue(Files.exists(metadataFile));
        assertEquals(json, Files.readString(metadataFile));
    }

    @Test
    void should_deleteSessionDir_when_invoked() throws IOException {
        RecordingArtifactStore store = new RecordingArtifactStore(tempDir.toString(), 7);
        String sessionId = UUID.randomUUID().toString();
        Path sessionDir = store.getSessionDirectory(sessionId);
        Files.writeString(sessionDir.resolve("dummy.txt"), "hello");

        assertTrue(Files.exists(sessionDir));
        store.deleteSessionDirectory(sessionId);
        assertFalse(Files.exists(sessionDir));
    }

    @Test
    void should_cleanExpiredArtifacts_when_olderThanRetentionCutoff() throws IOException {
        RecordingArtifactStore store = new RecordingArtifactStore(tempDir.toString(), 1);
        String oldSession = "old-session";
        String newSession = "new-session";

        Path oldDir = store.getSessionDirectory(oldSession);
        Path newDir = store.getSessionDirectory(newSession);

        long twoDaysAgo = System.currentTimeMillis() - (2L * 24 * 60 * 60 * 1000);
        Files.setLastModifiedTime(oldDir, FileTime.fromMillis(twoDaysAgo));

        store.cleanExpiredArtifacts();

        assertFalse(Files.exists(oldDir));
        assertTrue(Files.exists(newDir));
    }
}
