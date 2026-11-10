package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.save.CSVSaveService;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** UTF-8 CSV with a header and explicit columns, using JMeter's CSV parser. */
public final class ParameterCsv {
    private ParameterCsv() { }

    public static void write(Path file, char delimiter, List<String> names, List<String> values,
            List<String> existingNames) throws IOException {
        write(file, delimiter, names, values, existingNames, true);
    }

    public static List<String> header(Path file, char delimiter) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Arrays.asList(CSVSaveService.csvReadFile(reader, delimiter));
        }
    }

    public static void write(Path file, char delimiter, List<String> names, List<String> values,
            List<String> existingNames, boolean existingHeader) throws IOException {
        if (names.isEmpty() || names.size() != values.size()) {
            throw new IllegalArgumentException("CSV requires equally sized, non-empty names and values.");
        }
        if (delimiter == '"' || delimiter == '\n' || delimiter == '\r') {
            throw new IllegalArgumentException("Invalid CSV delimiter.");
        }
        List<List<String>> rows = new ArrayList<>();
        if (!existingNames.isEmpty()) {
            if (!Files.exists(file)) {
                throw new IOException("The reused CSV file does not exist: " + file);
            }
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String[] row;
                while ((row = CSVSaveService.csvReadFile(reader, delimiter)).length > 0) {
                    rows.add(new ArrayList<>(Arrays.asList(row)));
                }
            }
            if (!existingHeader) { rows.add(0, new ArrayList<>(existingNames)); }
            if (rows.isEmpty() || !rows.get(0).equals(existingNames)) {
                throw new IOException("Existing CSV header must match the CSV Data Set variable names.");
            }
            for (List<String> row : rows) {
                if (row.size() != existingNames.size()) {
                    throw new IOException("Existing CSV has an inconsistent column count.");
                }
            }
            rows.get(0).addAll(names);
            if (rows.size() == 1) {
                throw new IOException("Existing CSV needs at least one data row before appending columns.");
            }
            for (int i = 1; i < rows.size(); i++) { rows.get(i).addAll(values); }
        } else {
            rows.add(new ArrayList<>(names));
            rows.add(new ArrayList<>(values));
        }
        StringBuilder output = new StringBuilder();
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) { output.append(delimiter); }
                output.append('"').append(row.get(i).replace("\"", "\"\"")).append('"');
            }
            output.append('\n');
        }
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path staged = Files.createTempFile(absolute.getParent(), ".parameterize-", ".csv");
        try {
            Files.writeString(staged, output, StandardCharsets.UTF_8);
            Files.move(staged, absolute, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
