package org.qainsights.jmeter.ai.record;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Manages the persistence, path safety, and lifecycle cleanup of recording session artifacts.
 */
public final class RecordingArtifactStore {

    private final Path rootDirectory;
    private final int retentionDays;

    public RecordingArtifactStore() {
        this(
            AiConfig.getProperty("jmeter.ai.record.artifacts.dir", ""),
            Integer.parseInt(AiConfig.getProperty("jmeter.ai.record.retention.days", "7"))
        );
    }

    public RecordingArtifactStore(String rootDirStr, int retentionDays) {
        if (rootDirStr == null || rootDirStr.trim().isEmpty()) {
            this.rootDirectory = Paths.get(System.getProperty("java.io.tmpdir"), "jmeter-ai-recordings");
        } else {
            this.rootDirectory = Paths.get(rootDirStr);
        }
        this.retentionDays = retentionDays;
    }

    public Path getRootDirectory() {
        return rootDirectory;
    }

    public Path getSessionDirectory(String sessionId) throws IOException {
        Path sessionDir = rootDirectory.resolve(sessionId);
        checkContainment(sessionDir);
        if (!Files.exists(sessionDir)) {
            Files.createDirectories(sessionDir);
        }
        return sessionDir;
    }

    public void checkContainment(Path path) {
        Path rootAbsolute = rootDirectory.toAbsolutePath().normalize();
        Path targetAbsolute = path.toAbsolutePath().normalize();
        if (!targetAbsolute.startsWith(rootAbsolute) && !targetAbsolute.equals(rootAbsolute)) {
            throw new SecurityException("Path traversal attempt detected: " + path);
        }
    }

    public void writeSessionMetadata(String sessionId, String json) throws IOException {
        Path sessionDir = getSessionDirectory(sessionId);
        Path tempFile = Files.createTempFile(sessionDir, "session", ".tmp");
        try {
            Files.writeString(tempFile, json);
            Path targetFile = sessionDir.resolve("session.json");
            Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public void deleteSessionDirectory(String sessionId) throws IOException {
        Path sessionDir = rootDirectory.resolve(sessionId);
        checkContainment(sessionDir);
        if (Files.exists(sessionDir)) {
            deleteDirectoryRecursively(sessionDir);
        }
    }

    public void cleanExpiredArtifacts() throws IOException {
        if (!Files.exists(rootDirectory)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - ((long) retentionDays * 24 * 60 * 60 * 1000);
        try (var stream = Files.list(rootDirectory)) {
            for (Path path : stream.toList()) {
                if (Files.isDirectory(path) && Files.getLastModifiedTime(path).toMillis() < cutoff) {
                    deleteDirectoryRecursively(path);
                }
            }
        }
    }

    private void deleteDirectoryRecursively(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            var paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.delete(p);
            }
        }
    }
}
