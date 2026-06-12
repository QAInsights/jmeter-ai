package org.qainsights.jmeter.ai.consensus;

import org.qainsights.jmeter.ai.security.AuditLogger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders a multi-CLI consensus run: each CLI's answer side by side, plus a
 * lightweight, deterministic <em>agreement score</em> (average pairwise Jaccard
 * similarity over the answer word-sets) so divergence is easy to spot. Semantic
 * judgement is left to the human — this just makes the comparison legible.
 */
public final class ConsensusReport {

    private static final Pattern WORD = Pattern.compile("[^a-z0-9]+");

    private final String prompt;
    private final List<ConsensusRunner.Outcome> outcomes;

    public ConsensusReport(String prompt, List<ConsensusRunner.Outcome> outcomes) {
        this.prompt = prompt == null ? "" : prompt;
        this.outcomes = outcomes;
    }

    /** @return average pairwise Jaccard similarity over successful answers (0..1). */
    public double agreementScore() {
        List<String> answers = successfulOutputs();
        if (answers.size() < 2) {
            return answers.size() == 1 ? 1.0 : 0.0;
        }
        double total = 0;
        int pairs = 0;
        for (int i = 0; i < answers.size(); i++) {
            for (int j = i + 1; j < answers.size(); j++) {
                total += jaccard(words(answers.get(i)), words(answers.get(j)));
                pairs++;
            }
        }
        return pairs == 0 ? 0.0 : total / pairs;
    }

    public String render(String format) {
        return "json".equalsIgnoreCase(format) ? toJson() : toMarkdown();
    }

    private String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Multi-CLI Consensus Report\n\n");
        sb.append("- **CLIs run:** ").append(outcomes.size()).append('\n');
        sb.append("- **Succeeded:** ").append(successfulOutputs().size()).append('\n');
        sb.append("- **Agreement score:** ").append(String.format("%.2f", agreementScore()))
          .append(" (1.0 = identical wording, 0.0 = disjoint)\n\n");
        sb.append("## Prompt\n\n").append(prompt).append("\n\n");
        for (ConsensusRunner.Outcome o : outcomes) {
            sb.append("## ").append(o.cliName).append("  —  ")
              .append(o.succeeded() ? "OK" : (o.available ? "FAILED" : "SKIPPED")).append('\n');
            if (!o.succeeded() && o.available) {
                sb.append("_exit ").append(o.exitCode).append(o.timedOut ? ", timed out" : "").append("_\n");
            }
            sb.append("\n```\n").append(o.output);
            if (!o.output.endsWith("\n")) {
                sb.append('\n');
            }
            sb.append("```\n\n");
        }
        return sb.toString();
    }

    private String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"agreementScore\":").append(String.format("%.4f", agreementScore()));
        sb.append(",\"prompt\":\"").append(AuditLogger.escape(prompt)).append('"');
        sb.append(",\"results\":[");
        for (int i = 0; i < outcomes.size(); i++) {
            ConsensusRunner.Outcome o = outcomes.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"cli\":\"").append(AuditLogger.escape(o.cliName)).append('"');
            sb.append(",\"available\":").append(o.available);
            sb.append(",\"exitCode\":").append(o.exitCode);
            sb.append(",\"timedOut\":").append(o.timedOut);
            sb.append(",\"output\":\"").append(AuditLogger.escape(o.output)).append('"');
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private List<String> successfulOutputs() {
        List<String> out = new java.util.ArrayList<>();
        for (ConsensusRunner.Outcome o : outcomes) {
            if (o.succeeded() && !o.output.trim().isEmpty()) {
                out.add(o.output);
            }
        }
        return out;
    }

    static Set<String> words(String text) {
        Set<String> set = new HashSet<>();
        for (String w : WORD.split(text.toLowerCase())) {
            if (!w.isEmpty()) {
                set.add(w);
            }
        }
        return set;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
    }
}
