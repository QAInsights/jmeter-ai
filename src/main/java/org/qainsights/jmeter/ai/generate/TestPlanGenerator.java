package org.qainsights.jmeter.ai.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point for spec/HAR → JMX generation. Detects whether the input is a HAR
 * capture or an OpenAPI/Swagger document, parses it into a {@link TestPlanModel},
 * and renders a JMeter {@code .jmx}.
 */
public final class TestPlanGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Recognised input shapes. */
    public enum Format { HAR, OPENAPI, UNKNOWN }

    private TestPlanGenerator() {
    }

    /** Generate JMX from a file on disk. */
    public static String generateFromFile(String inputPath, String testName) throws Exception {
        Path path = Paths.get(inputPath);
        String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        String name = (testName == null || testName.isEmpty())
                ? stripExtension(path.getFileName().toString()) : testName;
        return generate(json, name);
    }

    /** Generate JMX from a JSON string, auto-detecting the format. */
    public static String generate(String json, String testName) throws Exception {
        Format format = detect(json);
        TestPlanModel model;
        switch (format) {
            case HAR:
                model = HarParser.parse(json, testName);
                break;
            case OPENAPI:
                model = OpenApiParser.parse(json, testName);
                break;
            default:
                throw new IllegalArgumentException(
                        "Unrecognised input: expected a HAR capture or an OpenAPI/Swagger document (JSON).");
        }
        if (model.isEmpty()) {
            throw new IllegalArgumentException("No HTTP requests found in the input.");
        }
        return JmxTestPlanWriter.write(model);
    }

    static Format detect(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        if (root.has("log") && root.path("log").has("entries")) {
            return Format.HAR;
        }
        if (root.has("openapi") || root.has("swagger") || root.has("paths")) {
            return Format.OPENAPI;
        }
        return Format.UNKNOWN;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
