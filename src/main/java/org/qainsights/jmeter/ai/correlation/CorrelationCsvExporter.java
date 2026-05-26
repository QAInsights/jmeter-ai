package org.qainsights.jmeter.ai.correlation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class CorrelationCsvExporter {
    public void export(List<CorrelationCandidate> candidates, Path path) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(path, "path");
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("Sampler,Variable Name,Extractor Type,Expression,Source Location,Request Location,Status");
            writer.newLine();
            for (CorrelationCandidate candidate : candidates) {
                writer.write(csv(candidate.getSourceResponse().getLabel()));
                writer.write(',');
                writer.write(csv(candidate.getSuggestion().getVariableName()));
                writer.write(',');
                writer.write(csv(candidate.getSuggestion().getExtractorType().getDisplayName()));
                writer.write(',');
                writer.write(csv(candidate.getSuggestion().getExpression()));
                writer.write(',');
                writer.write(csv(candidate.getResponseLocation()));
                writer.write(',');
                writer.write(csv(candidate.getRequestLocation()));
                writer.write(',');
                writer.write(csv(candidate.getStatus().name()));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new PluginException("Failed to export correlation candidates: " + e.getMessage(), e);
        }
    }

    private static String csv(String value) {
        String safeValue = value == null ? "" : value;
        return '"' + safeValue.replace("\"", "\"\"") + '"';
    }
}
