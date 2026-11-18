package org.qainsights.jmeter.ai.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.channels.FileChannel;
import java.util.UUID;

final class StarterId {
    private StarterId() { }

    static synchronized String load(Path path) throws IOException {
        path = path.toAbsolutePath();
        Path parent = path.getParent();
        Files.createDirectories(parent);
        var attributes = Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView.class)
                ? new java.nio.file.attribute.FileAttribute<?>[] {
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")) }
                : new java.nio.file.attribute.FileAttribute<?>[0];
        try (FileChannel channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
                java.util.Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), attributes);
             var lock = channel.lock()) {
            if (Files.exists(path)) {
                if (attributes.length > 0) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
                try {
                    return UUID.fromString(Files.readString(path).trim()).toString();
                } catch (IllegalArgumentException ignored) { }
            }
            String id = UUID.randomUUID().toString();
            Path temporary = Files.createTempFile(parent, ".starter-id-", ".tmp", attributes);
            try {
                Files.writeString(temporary, id);
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
                return id;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
