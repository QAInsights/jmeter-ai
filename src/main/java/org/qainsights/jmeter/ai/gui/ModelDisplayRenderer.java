package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.awt.Font;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

/**
 * Renders model ids in the selector as friendly labels with a provider
 * suffix, e.g. {@code "gpt-4o · OpenAI"} instead of the raw
 * {@code "openai:gpt-4o"}. Only the <em>display</em> changes - the combo box
 * still stores and returns the original model id strings, so model routing
 * logic is completely unaffected.
 */
public class ModelDisplayRenderer extends DefaultListCellRenderer {

    /** Prefix-to-provider display names, ordered longest-first for matching. */
    private static final String[][] PROVIDERS = {
        { "openai:", "OpenAI" },
        { "ollama:", "Ollama" },
        { "deepseek:", "DeepSeek" },
        { "google:", "Google" },
        { "grok:", "Grok" },
        { "meta:", "Meta" },
        { "bedrock:", "AWS Bedrock" },
    };

    /** Unprefixed model ids route to Claude (see CommandDispatcher). */
    private static final String DEFAULT_PROVIDER = "Anthropic";

    /**
     * Splits a raw model id into display name and provider, e.g.
     * {@code "openai:gpt-4o" -> ["gpt-4o", "OpenAI"]} and
     * {@code "claude-sonnet" -> ["claude-sonnet", "Anthropic"]}.
     */
    static String[] parse(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return new String[] { "Loading models...", "" };
        }
        for (String[] entry : PROVIDERS) {
            if (modelId.startsWith(entry[0])) {
                return new String[] {
                    modelId.substring(entry[0].length()),
                    entry[1],
                };
            }
        }
        return new String[] { modelId, DEFAULT_PROVIDER };
    }

    @Override
    public Component getListCellRendererComponent(
        JList<?> list,
        Object value,
        int index,
        boolean isSelected,
        boolean cellHasFocus
    ) {
        String[] parts = parse(value == null ? null : value.toString());
        String display =
            parts[1].isEmpty()
                ? parts[0]
                : parts[0] + "  ·  " + parts[1];

        Component c = super.getListCellRendererComponent(
            list,
            display,
            index,
            isSelected,
            cellHasFocus
        );

        if (value == null) {
            c.setFont(c.getFont().deriveFont(Font.ITALIC));
        }
        return c;
    }
}
