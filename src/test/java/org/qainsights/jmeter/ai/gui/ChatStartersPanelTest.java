package org.qainsights.jmeter.ai.gui;

import java.awt.Font;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatStartersPanelTest {
    @Test
    void startersFillOnlyWhenClickedAndCanBeHidden() {
        java.util.concurrent.atomic.AtomicReference<ChatStarterSuggestion> accepted =
                new java.util.concurrent.atomic.AtomicReference<>();
        ChatStarterSuggestion suggestion = new ChatStarterSuggestion("Add CSV", "Add a CSV data set");
        ChatStartersPanel panel = new ChatStartersPanel(new Font(Font.DIALOG, Font.PLAIN, 13));
        java.util.List<ChatStarterSuggestion> options = java.util.List.of(suggestion,
                new ChatStarterSuggestion("Review", "Review my plan"),
                new ChatStarterSuggestion("Size", "Size my workload"));
        panel.setSuggestions(options, accepted::set);
        assertEquals(3, panel.suggestionButtons().size());
        org.junit.jupiter.api.Assertions.assertNull(accepted.get());
        for (int index = 0; index < options.size(); index++) {
            panel.suggestionButtons().get(index).doClick();
            assertEquals(options.get(index), accepted.get());
        }
        panel.applyFont(new Font(Font.DIALOG, Font.PLAIN, 20));
        org.junit.jupiter.api.Assertions.assertTrue(panel.suggestionButtons().stream()
                .allMatch(button -> button.getMaximumSize().height >= button.getPreferredSize().height));
        panel.setSuggestions(java.util.List.of(), accepted::set);
        org.junit.jupiter.api.Assertions.assertTrue(panel.suggestionButtons().isEmpty());
    }

}
