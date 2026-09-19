package org.qainsights.jmeter.ai.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * UI-facing payload for one Jev failure triage, emitted when {@code get_test_results}
 * produced failures and triage classified them. Carries the per-failure verdicts the
 * transcript card renders; mirrors how {@link AgentRequestRouter.Notice} carries a
 * routing decision to the UI without coupling the two shapes.
 */
public record TriageNotice(int totalFailures, int classifiedFailures,
                           String dominantCategory, List<Row> rows) {

    public TriageNotice {
        rows = rows == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(rows));
        dominantCategory = dominantCategory == null ? "" : dominantCategory;
    }

    /** One failed sample's triage verdict: its label, category name, and Jev confidence. */
    public record Row(String label, String category, double confidence) {
    }
}
