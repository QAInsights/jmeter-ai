package org.qainsights.jmeter.ai.param;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ParameterSampleRows {
    private static final String NUMBER = "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    record Column(String name, String type) {
    }

    private ParameterSampleRows() {
    }

    static Column column(String name, String currentValue) {
        String type = "string";
        if (currentValue.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            type = "email";
        } else if (currentValue.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            type = "uuid";
        } else if (currentValue.matches(NUMBER)) {
            type = "number";
        } else if (currentValue.matches("\\d{4}-\\d{2}-\\d{2}(?:[T ].*)?")) {
            type = "date";
        } else if (currentValue.equalsIgnoreCase("true") || currentValue.equalsIgnoreCase("false")) {
            type = "boolean";
        }
        return new Column(name, type);
    }

    static String prompt(List<Column> columns, int count) {
        if (columns.isEmpty() || count < 1 || count > 500) {
            throw new IllegalArgumentException("Select columns and between 1 and 500 sample rows.");
        }
        try {
            return "Generate fictional test data. Return ONLY a JSON array of exactly " + count
                    + " rows, each an array of strings in the column order below."
                    + " Use fictional example domains for emails. No expressions, formulas, control"
                    + " characters, double quotes or backslashes in cells. Columns (names and types only):\n"
                    + JSON.writeValueAsString(columns.stream()
                            .map(c -> Map.of("name", c.name(), "type", c.type())).toList());
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not prepare sample columns.", e);
        }
    }

    static List<List<String>> parse(String response, int count, List<Column> columns) {
        if (response == null || response.isBlank() || response.length() > 4_000_000) {
            throw new IllegalArgumentException("The provider returned an empty or oversized sample response.");
        }
        try {
            JsonNode root = array(response);
            if (!root.isArray() || root.size() != count) {
                throw new IllegalArgumentException("The sample response has the wrong row count.");
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode row : root) {
                if (!row.isArray() || row.size() != columns.size()) {
                    throw new IllegalArgumentException("The sample response has the wrong column count.");
                }
                List<String> cells = new ArrayList<>();
                for (JsonNode cell : row) {
                    if (!cell.isTextual() || !safe(cell.textValue())) {
                        throw new IllegalArgumentException("The sample response contains an unsafe cell.");
                    }
                    String type = columns.get(cells.size()).type();
                    String value = cell.textValue();
                    if ((type.equals("number") && !value.matches(NUMBER))
                            || (type.equals("boolean") && !value.equals("true") && !value.equals("false"))) {
                        throw new IllegalArgumentException("The sample response contains a value that does not match its column type.");
                    }
                    cells.add(cell.textValue());
                }
                rows.add(List.copyOf(cells));
            }
            return List.copyOf(rows);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("The provider must return valid JSON sample rows.", e);
        }
    }

    private static JsonNode array(String response) throws java.io.IOException {
        JsonNode root = null;
        int start = response.indexOf('[');
        while (start >= 0) {
            int next = start + 1;
            try (com.fasterxml.jackson.core.JsonParser parser =
                    JSON.getFactory().createParser(response.substring(start))) {
                JsonNode candidate = JSON.reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(parser);
                if (candidate != null && candidate.isArray()) {
                    if (root != null) {
                        throw new IllegalArgumentException("The provider returned multiple sample arrays.");
                    }
                    root = candidate;
                    next = start + (int) parser.currentLocation().getCharOffset();
                }
            } catch (java.io.IOException ignored) {
            }
            start = response.indexOf('[', next);
        }
        if (root == null) {
            throw new java.io.IOException("No valid sample array was returned.");
        }
        return root;
    }

    private static boolean safe(String value) {
        if (value.length() > 4096 || value.contains("${")
                || value.codePoints().anyMatch(Character::isISOControl)
                || !ParameterReplacement.safeJsonString(value)) {
            return false;
        }
        String trimmed = value.stripLeading();
        return !trimmed.startsWith("=") && !trimmed.startsWith("+") && !trimmed.startsWith("@")
                && (!trimmed.startsWith("-") || trimmed.matches("-\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"));
    }
}
