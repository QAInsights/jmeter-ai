package org.qainsights.jmeter.ai.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

final class StarterId {
    private StarterId() { }

    static synchronized String load(Path path) throws IOException {
        if (Files.exists(path)) {
            return UUID.fromString(Files.readString(path).trim()).toString();
        }
        Files.createDirectories(path.getParent());
        String id = UUID.randomUUID().toString();
        try {
            Files.writeString(path, id, StandardOpenOption.CREATE_NEW);
            return id;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return UUID.fromString(Files.readString(path).trim()).toString();
        }
    }
}
