package org.qainsights.jmeter.ai.mcpserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

/** Loads, or creates on first use, the random bearer token MCP clients must send. */
public final class McpServerToken {

    private static final Pattern VALID = Pattern.compile("[0-9a-f]{64}");
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final SecureRandom RANDOM = new SecureRandom();

    private McpServerToken() {
    }

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".jmeter-ai", "mcp-token");
    }

    /** Returns the token stored at {@code file}, writing a new one there if it is missing or malformed. */
    public static String loadOrCreate(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (VALID.matcher(existing).matches()) {
                return existing;
            }
        }
        return regenerate(file);
    }

    /**
     * Writes a new token to {@code file}, replacing any old one, so existing client setups stop working.
     * The token goes to a temp file that is created owner-only and then moved into place, so it is never
     * readable by other users, and a failed write leaves the old token intact.
     */
    public static String regenerate(Path file) throws IOException {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        FileAttribute<?>[] ownerOnly = posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY)}
                : new FileAttribute<?>[0];
        Path temp = Files.createTempFile(dir, ".mcp-token", ".tmp", ownerOnly);
        try {
            Files.writeString(temp, token, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return token;
    }
}
