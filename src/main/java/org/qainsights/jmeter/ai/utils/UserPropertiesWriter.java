package org.qainsights.jmeter.ai.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Path target = path.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("The properties file has no parent directory");
        }
        Files.createDirectories(parent);
        String original = Files.exists(target)
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

        Path temporary = Files.createTempFile(parent, ".feather-wand-user-properties-", ".tmp");
        try {
            Files.writeString(temporary, updated, StandardCharsets.UTF_8);
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
        return Pattern.compile("^(\\s*" + Pattern.quote(key) + "\\s*[=:]\\s*).*$");
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

    private record Line(String text, String ending) {
    }
}
