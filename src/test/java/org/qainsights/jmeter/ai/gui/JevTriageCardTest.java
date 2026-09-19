package org.qainsights.jmeter.ai.gui;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.FailureTriage;
import org.qainsights.jmeter.ai.agent.TriageNotice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevTriageCardTest {

    private static TriageNotice notice(int total, int classified, String dominant,
                                       TriageNotice.Row... rows) {
        return new TriageNotice(total, classified, dominant, List.of(rows));
    }

    private static TriageNotice.Row row(String label, String category, double confidence) {
        return new TriageNotice.Row(label, category, confidence);
    }

    @Test
    void cardShowsCountsDominantCategoryAndAdvisory() {
        JevTriageCard card = new JevTriageCard(notice(3, 3, "TIMEOUT",
                row("Login POST", "TIMEOUT", 0.87),
                row("Search GET", "TIMEOUT", 0.82),
                row("Checkout POST", "HTTP_SERVER", 0.71)), "openai:gpt-5");

        assertTrue(card.getHeaderText().contains("Jev Failure Triage"));
        assertTrue(card.getSummaryText().contains("3 failures"));
        assertTrue(card.getSummaryText().contains("3 classified"));
        assertTrue(card.getSummaryText().contains("Timeout"));
        assertTrue(card.getAdvisoryText().contains("Advisory only"));
        assertTrue(card.getAdvisoryText().contains("OpenAI gpt-5"));
        assertTrue(card.getAdvisoryText().contains("still writes the diagnosis"));
    }

    @Test
    void detailsGroupFailuresByCategoryWithConfidence() {
        JevTriageCard card = new JevTriageCard(notice(3, 2, "TIMEOUT",
                row("Login POST", "TIMEOUT", 0.87),
                row("Search GET", "TIMEOUT", 0.82),
                row("Odd Sampler", FailureTriage.UNCLASSIFIED, 0.0)), "claude-sonnet");

        String details = card.getDetailsText();
        assertTrue(details.contains("Timeout (2):"));
        assertTrue(details.contains("Login POST"));
        assertTrue(details.contains("87%"));
        assertTrue(details.contains("Unclassified (1):"));
        assertTrue(details.contains("Odd Sampler"));
    }

    @Test
    void untriagedOverflowAndMissingDominantAreSurfaced() {
        JevTriageCard card = new JevTriageCard(notice(9, 0, "",
                row("A", "HTTP_SERVER", 0.8)), "meta:muse-spark-1.2-contributor");

        assertTrue(card.getSummaryText().contains("9 failures"));
        assertFalse(card.getSummaryText().contains("dominant"));
        assertTrue(card.getSummaryText().contains("8 more not triaged"));
        assertTrue(card.getAdvisoryText().contains("Meta muse-spark-1.2-contributor"));
    }

    @Test
    void nullNoticeAndModelAreSafe() {
        JevTriageCard card = new JevTriageCard(null, null);

        assertTrue(card.getSummaryText().contains("0 failures"));
        assertDoesNotThrow(card::applyTheme);
    }

    @Test
    void expansionTogglesHeaderAndDetails() {
        JevTriageCard card = new JevTriageCard(notice(1, 1, "SCRIPT",
                row("JSR223", "SCRIPT", 0.9)), "google:gemini");

        assertFalse(card.isExpanded());
        assertTrue(card.getHeaderText().startsWith("▸"));
        card.setExpanded(true);
        assertTrue(card.isExpanded());
        assertTrue(card.getHeaderText().startsWith("▾"));
    }
}
