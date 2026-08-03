package org.qainsights.jmeter.ai.service.attach;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a JMeter results file (.jtl, CSV flavor) into a compact markdown
 * digest for AI analysis: headline numbers (samples, error rate, response-time
 * percentiles, throughput), a per-label breakdown, the slowest and the failing
 * samples, and a few raw rows for flavor. This is the "smart" preprocessing
 * step - a full results file is far too large to inline into a prompt, and
 * most questions are answered better from aggregates than raw rows.
 */
public final class JtlSummarizer {

    private static final int MAX_TOP_N = 5;
    private static final int MAX_RAW_ROWS = 3;
    private static final int MAX_LABELS = 10;

    private JtlSummarizer() {
    }

    /** Column positions resolved from the header row (JMeter writes named columns). -1 = absent. */
    private static final class Columns {
        int timeStamp = -1;
        int elapsed = -1;
        int label = -1;
        int responseCode = -1;
        int responseMessage = -1;
        int success = -1;
        int failureMessage = -1;

        static Columns from(String headerLine) {
            Columns cols = new Columns();
            String[] names = headerLine.split(",", -1);
            for (int i = 0; i < names.length; i++) {
                switch (names[i].trim()) {
                    case "timeStamp": cols.timeStamp = i; break;
                    case "elapsed": cols.elapsed = i; break;
                    case "label": cols.label = i; break;
                    case "responseCode": cols.responseCode = i; break;
                    case "responseMessage": cols.responseMessage = i; break;
                    case "success": cols.success = i; break;
                    case "failureMessage": cols.failureMessage = i; break;
                    default: break;
                }
            }
            return cols;
        }
    }

    private static final class Sample {
        long timeStamp;
        long elapsed;
        String label = "";
        String responseCode = "";
        String responseMessage = "";
        boolean success = true;
        String failureMessage = "";
    }

    private static final class LabelStats {
        int count;
        int errors;
        long totalElapsed;
        long maxElapsed;
    }

    /** True when the content looks like a JMeter CSV results file. */
    public static boolean looksLikeJtl(String fileName, String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String firstLine = content.lines().findFirst().orElse("");
        if (firstLine.contains("timeStamp") && firstLine.contains("elapsed") && firstLine.contains("label")) {
            return true;
        }
        return fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jtl")
                && firstLine.contains(",");
    }

    /** Summarizes jtl CSV content into a compact markdown digest. */
    public static String summarize(String content) {
        List<String> lines = content.lines().toList();
        if (lines.size() < 2) {
            return "Empty or header-only results file.";
        }
        Columns cols = Columns.from(lines.get(0));
        List<Long> elapsed = new ArrayList<>();
        List<Sample> samples = new ArrayList<>();
        List<String> rawRows = new ArrayList<>();
        Map<String, LabelStats> byLabel = new LinkedHashMap<>();
        long minTs = Long.MAX_VALUE;
        long maxEnd = Long.MIN_VALUE;

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            Sample s = parseSample(line, cols);
            if (s == null) {
                continue;
            }
            samples.add(s);
            if (rawRows.size() < MAX_RAW_ROWS) {
                rawRows.add(line);
            }
            elapsed.add(s.elapsed);
            minTs = Math.min(minTs, s.timeStamp);
            maxEnd = Math.max(maxEnd, s.timeStamp + s.elapsed);
            byLabel.computeIfAbsent(s.label, k -> new LabelStats());
            LabelStats stats = byLabel.get(s.label);
            stats.count++;
            if (!s.success) {
                stats.errors++;
            }
            stats.totalElapsed += s.elapsed;
            stats.maxElapsed = Math.max(stats.maxElapsed, s.elapsed);
        }
        if (samples.isEmpty()) {
            return "No sample rows found in the results file.";
        }
        return render(samples, elapsed, byLabel, rawRows, minTs, maxEnd);
    }

    private static String render(List<Sample> samples, List<Long> elapsed,
            Map<String, LabelStats> byLabel, List<String> rawRows, long minTs, long maxEnd) {
        int total = samples.size();
        long errors = samples.stream().filter(s -> !s.success).count();
        Collections.sort(elapsed);

        StringBuilder sb = new StringBuilder();
        sb.append("## Results summary\n");
        sb.append("- Samples: ").append(total);
        sb.append(", Errors: ").append(errors)
                .append(" (").append(String.format(java.util.Locale.ROOT, "%.2f", total == 0 ? 0 : errors * 100.0 / total)).append("%)\n");
        sb.append("- Elapsed ms: avg ").append(average(elapsed))
                .append(", median ").append(percentile(elapsed, 50))
                .append(", p90 ").append(percentile(elapsed, 90))
                .append(", p95 ").append(percentile(elapsed, 95))
                .append(", p99 ").append(percentile(elapsed, 99))
                .append(", min ").append(elapsed.get(0))
                .append(", max ").append(elapsed.get(elapsed.size() - 1)).append('\n');
        double durationSec = Math.max(1.0, (maxEnd - minTs) / 1000.0);
        sb.append("- Duration: ").append(String.format(java.util.Locale.ROOT, "%.1f", durationSec))
                .append("s, Throughput: ").append(String.format(java.util.Locale.ROOT, "%.2f", total / durationSec))
                .append(" samples/s\n");

        sb.append("\n## Per-label breakdown (label: count, errors, avg ms, max ms)\n");
        byLabel.entrySet().stream().limit(MAX_LABELS).forEach(e ->
                sb.append("- ").append(e.getKey().isEmpty() ? "(no label)" : e.getKey())
                        .append(": ").append(e.getValue().count)
                        .append(", ").append(e.getValue().errors)
                        .append(", ").append(e.getValue().count == 0 ? 0 : e.getValue().totalElapsed / e.getValue().count)
                        .append(", ").append(e.getValue().maxElapsed).append('\n'));
        if (byLabel.size() > MAX_LABELS) {
            sb.append("- ... and ").append(byLabel.size() - MAX_LABELS).append(" more labels\n");
        }

        appendTop(sb, "\n## Slowest samples (label, elapsed ms, response code)\n",
                samples.stream().sorted((a, b) -> Long.compare(b.elapsed, a.elapsed)).limit(MAX_TOP_N).toList(),
                s -> "- " + s.label + ", " + s.elapsed + ", " + s.responseCode);
        List<Sample> failures = samples.stream().filter(s -> !s.success).limit(MAX_TOP_N).toList();
        if (!failures.isEmpty()) {
            appendTop(sb, "\n## Failing samples (label, code, message, failure)\n", failures,
                    s -> "- " + s.label + ", " + s.responseCode + ", " + trim(s.responseMessage)
                            + (s.failureMessage.isEmpty() ? "" : ", " + trim(s.failureMessage)));
        }

        sb.append("\n## Sample raw rows\n");
        for (String row : rawRows) {
            sb.append(row).append('\n');
        }
        return sb.toString().trim();
    }

    private static void appendTop(StringBuilder sb, String header, List<Sample> samples,
            java.util.function.Function<Sample, String> formatter) {
        sb.append(header);
        for (Sample s : samples) {
            sb.append(formatter.apply(s)).append('\n');
        }
    }

    private static long average(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).sum() / values.size();
    }

    private static long percentile(List<Long> sorted, int pct) {
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replace('\n', ' ').trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }

    private static Sample parseSample(String line, Columns cols) {
        String[] fields = splitCsv(line);
        // Require the core columns and guard EVERY accessed index, so results
        // files from other tools/column layouts are skipped, not crashed on.
        if (cols.timeStamp < 0 || cols.elapsed < 0 || cols.label < 0 || cols.success < 0) {
            return null;
        }
        int maxIndex = Math.max(cols.timeStamp, Math.max(cols.elapsed, Math.max(cols.label, cols.success)));
        for (int idx : new int[]{cols.responseCode, cols.responseMessage, cols.failureMessage}) {
            if (idx >= 0) {
                maxIndex = Math.max(maxIndex, idx);
            }
        }
        if (fields.length <= maxIndex) {
            return null;
        }
        Sample s = new Sample();
        try {
            s.timeStamp = Long.parseLong(fields[cols.timeStamp].trim());
            s.elapsed = Long.parseLong(fields[cols.elapsed].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        s.label = fields[cols.label];
        s.responseCode = cols.responseCode >= 0 ? fields[cols.responseCode] : "";
        s.responseMessage = cols.responseMessage >= 0 ? fields[cols.responseMessage] : "";
        s.success = !"false".equalsIgnoreCase(fields[cols.success].trim());
        s.failureMessage = cols.failureMessage >= 0 ? fields[cols.failureMessage] : "";
        return s;
    }

    /** Minimal CSV splitter honoring JMeter's double-quote escaping. */
    static String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
