package org.qainsights.jmeter.ai.correlation;

import org.qainsights.jmeter.ai.security.AuditLogger;

import java.util.List;

/**
 * Renders the {@link CorrelationCandidate}s found by {@link HarCorrelationAnalyzer}
 * into a report an engineer (or the AI) can act on: which dynamic values to
 * extract, where they come from, where they're reused, and the suggested extractor.
 */
public final class CorrelationReport {

    private final List<CorrelationCandidate> candidates;

    public CorrelationReport(List<CorrelationCandidate> candidates) {
        this.candidates = candidates;
    }

    public String render(String format) {
        return "json".equalsIgnoreCase(format) ? toJson() : toMarkdown();
    }

    private String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Correlation Autopilot Report\n\n");
        sb.append("Found **").append(candidates.size()).append("** dynamic value(s) to correlate.\n\n");
        if (candidates.isEmpty()) {
            sb.append("_No dynamic values detected in the recording._\n");
            return sb.toString();
        }
        int n = 1;
        for (CorrelationCandidate c : candidates) {
            sb.append("## ").append(n++).append(". `").append(c.getParameterName()).append("`\n\n");
            sb.append("- **Variable:** `${").append(c.getVariableName()).append("}`\n");
            sb.append("- **Sample value:** `").append(truncate(c.getSampleValue())).append("`\n");
            sb.append("- **Source:** sampler #").append(c.getSourceSamplerIndex())
              .append(" (").append(c.getSourceSamplerName()).append("), ").append(c.getSourceLocation()).append('\n');
            sb.append("- **Extractor:** ").append(c.getExtractorType())
              .append(" — `").append(c.getExtractionPattern()).append("`\n");
            sb.append("- **Reused in:** ").append(c.getUsageCount()).append(" sampler(s) — ")
              .append(String.join(", ", c.getTargetSamplerNames())).append("\n\n");
        }
        return sb.toString();
    }

    private String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"count\":").append(candidates.size()).append(",\"candidates\":[");
        for (int i = 0; i < candidates.size(); i++) {
            CorrelationCandidate c = candidates.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            field(sb, "parameter", c.getParameterName());
            sb.append(',');
            field(sb, "variable", c.getVariableName());
            sb.append(',');
            field(sb, "sampleValue", c.getSampleValue());
            sb.append(',');
            field(sb, "extractorType", c.getExtractorType());
            sb.append(',');
            field(sb, "extractionPattern", c.getExtractionPattern());
            sb.append(',');
            sb.append("\"sourceIndex\":").append(c.getSourceSamplerIndex());
            sb.append(',');
            field(sb, "sourceSampler", c.getSourceSamplerName());
            sb.append(',');
            sb.append("\"usageCount\":").append(c.getUsageCount());
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void field(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"").append(AuditLogger.escape(value)).append('"');
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
