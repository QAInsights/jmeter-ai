package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.save.CSVSaveService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Arrays;
import java.util.List;

/** UTF-8 CSV with a header and explicit columns, using JMeter's CSV parser. */
public final class ParameterCsv {
    private ParameterCsv() {}

    public static void write(
            Path file,
            char delimiter,
            List<String> names,
            List<String> values,
            List<String> existingNames)
            throws IOException {
        write(file, delimiter, names, values, existingNames, true);
    }

    public static List<String> header(Path file, char delimiter) throws IOException {
        return header(file, delimiter, true);
    }

    public static List<String> header(Path file, char delimiter, boolean quotedData) throws IOException {
        try (PushbackReader reader = new PushbackReader(
                Files.newBufferedReader(file, StandardCharsets.UTF_8), 1)) {
            Record header = record(reader, quotedData);
            return header == null ? List.of() : fields(header, delimiter, quotedData);
        }
    }

    public static void write(
            Path file,
            char delimiter,
            List<String> names,
            List<String> values,
            List<String> existingNames,
            boolean existingHeader)
            throws IOException {
        write(file, delimiter, names, values, existingNames, existingHeader, true);
    }

    public static void write(Path file, char delimiter, List<String> names, List<String> values,
            List<String> existingNames, boolean existingHeader, boolean quotedData) throws IOException {
        if (names.isEmpty() || names.size() != values.size()) {
            throw new IllegalArgumentException(
                    "CSV requires equally sized, non-empty names and values.");
        }
        if (delimiter == '"' || delimiter == '\n' || delimiter == '\r') {
            throw new IllegalArgumentException("Invalid CSV delimiter.");
        }
        if (!quotedData) {
            validateUnquoted(names, delimiter);
            validateUnquoted(values, delimiter);
        }
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path staged = Files.createTempFile(absolute.getParent(), ".parameterize-", ".csv");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(staged, StandardCharsets.UTF_8)) {
                if (existingNames.isEmpty()) {
                    row(writer, delimiter, names, List.of(), quotedData);
                    row(writer, delimiter, values, List.of(), quotedData);
                } else {
                    try (PushbackReader reader = new PushbackReader(
                            Files.newBufferedReader(absolute, StandardCharsets.UTF_8), 1)) {
                        Record first = record(reader, quotedData);
                        if (existingHeader) {
                            if (first == null || !fields(first, delimiter, quotedData).equals(existingNames)) {
                                throw new IOException("Existing CSV header must match the CSV Data Set variable names.");
                            }
                            append(writer, first, delimiter, names, quotedData);
                            first = record(reader, quotedData);
                        } else {
                            row(writer, delimiter, existingNames, names, quotedData);
                        }
                        boolean hasData = false;
                        for (Record current = first; current != null; current = record(reader, quotedData)) {
                            if (current.text.isEmpty()) {
                                writer.write(current.ending);
                                continue;
                            }
                            if (fields(current, delimiter, quotedData).size() != existingNames.size()) {
                                throw new IOException("Existing CSV has an inconsistent column count.");
                            }
                            append(writer, current, delimiter, values, quotedData);
                            hasData = true;
                        }
                        if (!hasData) {
                            throw new IOException("Existing CSV needs at least one data row before appending columns.");
                        }
                    }
                }
            }
            if (Files.exists(absolute)
                    && Files.getFileAttributeView(absolute, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(absolute));
            }
            replaceFile(staged, absolute);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private record Record(String text, String ending) {
    }

    public static void validateUnquoted(List<String> values, char delimiter) {
        for (String value : values) {
            if (value.indexOf(delimiter) >= 0 || value.contains("\"")
                    || value.contains("\r") || value.contains("\n")) {
                throw new IllegalArgumentException("Existing CSV has quotedData=false; new values must not"
                        + " contain the delimiter, a quote or a newline.");
            }
        }
    }

    private static Record record(PushbackReader reader, boolean quotedData) throws IOException {
        StringBuilder text = new StringBuilder();
        boolean quoted = false;
        int character;
        while ((character = reader.read()) != -1) {
            if (quotedData && character == '"') {
                quoted = !quoted;
            }
            if (!quoted && (character == '\n' || character == '\r')) {
                String ending = Character.toString((char) character);
                if (character == '\r') {
                    int next = reader.read();
                    if (next == '\n') {
                        ending = "\r\n";
                    } else if (next != -1) {
                        reader.unread(next);
                    }
                }
                return new Record(text.toString(), ending);
            }
            text.append((char) character);
        }
        return text.isEmpty() ? null : new Record(text.toString(), "");
    }

    private static List<String> fields(Record record, char delimiter, boolean quotedData) throws IOException {
        if (!quotedData) {
            return Arrays.asList(record.text.split(java.util.regex.Pattern.quote(String.valueOf(delimiter)), -1));
        }
        try (BufferedReader reader = new BufferedReader(new StringReader(record.text + "\n"))) {
            return Arrays.asList(CSVSaveService.csvReadFile(reader, delimiter));
        }
    }

    private static void append(BufferedWriter writer, Record record, char delimiter,
            List<String> appended, boolean quotedData) throws IOException {
        writer.write(record.text);
        for (String value : appended) {
            writer.write(delimiter);
            writer.write(quotedData ? "\"" + value.replace("\"", "\"\"") + "\"" : value);
        }
        writer.write(record.ending);
    }

    private static void row(BufferedWriter writer, char delimiter, List<String> first,
            List<String> appended, boolean quotedData) throws IOException {
        boolean separator = false;
        for (List<String> fields : List.of(first, appended)) {
            for (String value : fields) {
                if (separator) {
                    writer.write(delimiter);
                }
                writer.write(quotedData ? "\"" + value.replace("\"", "\"\"") + "\"" : value);
                separator = true;
            }
        }
        writer.write('\n');
    }

    static void replaceFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
