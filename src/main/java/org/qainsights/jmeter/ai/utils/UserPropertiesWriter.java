package org.qainsights.jmeter.ai.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.jmeter.util.JMeterUtils;

public final class UserPropertiesWriter {
    private static final String ADDED_COMMENT = "# Feather Wand (added by Connect a provider)";

    private UserPropertiesWriter() {
    }

    public static Path resolve() {
        String configuredPath = JMeterUtils.getPropDefault("user.properties", "user.properties");
        Path path = Path.of(configuredPath == null || configuredPath.isBlank()
                ? "user.properties" : configuredPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        String binDirectory = String.valueOf(JMeterUtils.getJMeterBinDir());
        return Path.of(binDirectory).resolve(path).normalize();
    }

    public static void apply(Path path, LinkedHashMap<String, String> values) throws IOException {
        if (path == null || values == null) {
            throw new IllegalArgumentException("A path and properties are required");
        }
        LinkedHashMap<String, String> properties = new LinkedHashMap<>(values);
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Property keys must be non-empty single-line values");
            }
            if (value == null) {
                throw new IllegalArgumentException("Property values cannot be null");
            }
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Property values cannot contain line breaks");
            }
        }

        try {
            write(path, properties);
        } catch (IOException e) {
            setRuntimeProperties(properties);
            throw e;
        }
        setRuntimeProperties(properties);
    }

    public static String propertyLines(LinkedHashMap<String, String> values) {
        StringBuilder lines = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (lines.length() > 0) {
                lines.append(System.lineSeparator());
            }
            lines.append(entry.getKey()).append('=').append(escapeValue(entry.getValue()));
        }
        return lines.toString();
    }

    private static void write(Path path, LinkedHashMap<String, String> values) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(target)) {
            target = target.toRealPath();
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("The properties file has no parent directory");
        }
        Files.createDirectories(parent);
        boolean targetExists = Files.exists(target);
        FileProtection protection = targetExists ? readProtection(target) : null;
        String original = targetExists
                ? Files.readString(target, StandardCharsets.UTF_8) : "";
        String separator = lineSeparator(original);
        List<Line> lines = parseLines(original);
        List<Map.Entry<String, String>> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            int lastMatchingLine = lastMatchingLine(lines, entry.getKey());
            if (lastMatchingLine < 0) {
                missing.add(entry);
                continue;
            }
            Line line = lines.get(lastMatchingLine);
            Matcher matcher = propertyPattern(entry.getKey()).matcher(line.text());
            if (matcher.matches()) {
                lines.set(lastMatchingLine,
                        new Line(matcher.group(1) + escapeValue(entry.getValue()), line.ending()));
            }
        }

        StringBuilder updated = new StringBuilder();
        for (Line line : lines) {
            updated.append(line.text()).append(line.ending());
        }
        if (!missing.isEmpty()) {
            if (updated.length() > 0 && !endsWithLineBreak(updated)) {
                updated.append(separator);
            }
            updated.append(ADDED_COMMENT).append(separator);
            for (int index = 0; index < missing.size(); index++) {
                Map.Entry<String, String> entry = missing.get(index);
                updated.append(entry.getKey()).append('=').append(escapeValue(entry.getValue()));
                if (index < missing.size() - 1) {
                    updated.append(separator);
                }
            }
        }

        Path temporary = createTemporaryFile(parent);
        try {
            Files.writeString(temporary, updated, StandardCharsets.UTF_8);
            if (protection != null) {
                copyAccess(temporary, protection);
                copyOwner(temporary, protection.owner());
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static FileProtection readProtection(Path target) throws IOException {
        try {
            FileOwnerAttributeView ownerView =
                    Files.getFileAttributeView(target, FileOwnerAttributeView.class);
            UserPrincipal owner = ownerView == null ? null : ownerView.getOwner();
            PosixFileAttributeView posixView =
                    Files.getFileAttributeView(target, PosixFileAttributeView.class);
            if (posixView != null) {
                return new FileProtection(posixView.readAttributes().permissions(), null, owner);
            }
            AclFileAttributeView aclView =
                    Files.getFileAttributeView(target, AclFileAttributeView.class);
            if (aclView != null) {
                return new FileProtection(null, aclView.getAcl(), owner);
            }
            throw new IOException("The file system does not support copying file protections");
        } catch (SecurityException | UnsupportedOperationException e) {
            throw new IOException("Could not read the properties file protections", e);
        }
    }

    private static Path createTemporaryFile(Path parent) throws IOException {
        try {
            if (Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null) {
                return Files.createTempFile(
                        parent,
                        ".feather-wand-user-properties-",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(EnumSet.of(
                                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            }
        } catch (UnsupportedOperationException | SecurityException e) {
            throw new IOException("Could not create a private properties file", e);
        }
        Path temporary = Files.createTempFile(parent, ".feather-wand-user-properties-", ".tmp");
        try {
            AclFileAttributeView aclView =
                    Files.getFileAttributeView(temporary, AclFileAttributeView.class);
            FileOwnerAttributeView ownerView =
                    Files.getFileAttributeView(temporary, FileOwnerAttributeView.class);
            if (aclView == null || ownerView == null) {
                throw new IOException("Could not create a private properties file");
            }
            UserPrincipal owner = ownerView.getOwner();
            if (owner == null) {
                throw new IOException("Could not determine the temporary file owner");
            }
            AclEntry ownerEntry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            aclView.setAcl(List.of(ownerEntry));
            return temporary;
        } catch (IOException e) {
            deleteTemporaryAfterFailure(temporary, e);
            throw e;
        } catch (SecurityException | UnsupportedOperationException e) {
            IOException failure = new IOException("Could not create a private properties file", e);
            deleteTemporaryAfterFailure(temporary, failure);
            throw failure;
        }
    }

    private static void deleteTemporaryAfterFailure(Path temporary, IOException failure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void copyAccess(Path temporary, FileProtection protection) throws IOException {
        try {
            if (protection.posixPermissions() != null) {
                PosixFileAttributeView posixView =
                        Files.getFileAttributeView(temporary, PosixFileAttributeView.class);
                if (posixView == null) {
                    throw new IOException("Could not preserve POSIX file permissions");
                }
                posixView.setPermissions(protection.posixPermissions());
            } else {
                AclFileAttributeView aclView =
                        Files.getFileAttributeView(temporary, AclFileAttributeView.class);
                if (aclView == null) {
                    throw new IOException("Could not preserve file access rules");
                }
                aclView.setAcl(protection.acl());
            }
        } catch (SecurityException | UnsupportedOperationException e) {
            throw new IOException("Could not preserve the properties file protections", e);
        }
    }

    private static void copyOwner(Path temporary, UserPrincipal owner) {
        if (owner != null) {
            try {
                FileOwnerAttributeView ownerView =
                        Files.getFileAttributeView(temporary, FileOwnerAttributeView.class);
                if (ownerView != null) {
                    ownerView.setOwner(owner);
                }
            } catch (IOException | SecurityException | UnsupportedOperationException ignored) {
            }
        }
    }

    private static int lastMatchingLine(List<Line> lines, String key) {
        Pattern pattern = propertyPattern(key);
        for (int index = lines.size() - 1; index >= 0; index--) {
            if (pattern.matcher(lines.get(index).text()).matches()) {
                return index;
            }
        }
        return -1;
    }

    private static Pattern propertyPattern(String key) {
        return Pattern.compile("^(\\s*" + Pattern.quote(key) + "(\\s*[=:]\\s*|\\s+)).*$");
    }

    private static String escapeValue(String value) {
        return value.replace("\\", "\\\\");
    }

    private static String lineSeparator(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\r') {
                return index + 1 < text.length() && text.charAt(index + 1) == '\n' ? "\r\n" : "\r";
            }
            if (character == '\n') {
                return "\n";
            }
        }
        return System.lineSeparator();
    }

    private static List<Line> parseLines(String text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character != '\r' && character != '\n') {
                continue;
            }
            int end = index + 1;
            if (character == '\r' && end < text.length() && text.charAt(end) == '\n') {
                end++;
            }
            lines.add(new Line(text.substring(start, index), text.substring(index, end)));
            start = end;
            index = end - 1;
        }
        if (start < text.length()) {
            lines.add(new Line(text.substring(start), ""));
        }
        return lines;
    }

    private static boolean endsWithLineBreak(StringBuilder text) {
        char last = text.charAt(text.length() - 1);
        return last == '\r' || last == '\n';
    }

    private static void setRuntimeProperties(LinkedHashMap<String, String> values) {
        values.forEach(JMeterUtils::setProperty);
    }

    private record FileProtection(
            Set<PosixFilePermission> posixPermissions,
            List<AclEntry> acl,
            UserPrincipal owner) {
    }

    private record Line(String text, String ending) {
    }
}
