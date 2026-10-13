package org.qainsights.jmeter.ai.mcpserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Loads, or creates on first use, the random bearer token MCP clients must send. */
public final class McpServerToken {

    private static final Pattern VALID = Pattern.compile("[0-9a-f]{64}");
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

    /** Writes a new token to {@code file}, replacing any old one, so existing client setups stop working. */
    public static String regenerate(Path file) throws IOException {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, token, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: the file stays readable only through the user's profile ACLs
        }
        return token;
    }
}
