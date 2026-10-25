package org.qainsights.jmeter.ai.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
import org.qainsights.jmeter.ai.service.prefs.ModelSelectorPreferences;
import org.qainsights.jmeter.ai.service.reasoning.ModelCapabilityCatalog;

/**
 * Two-line row for the model picker popup: the friendly display name and
 * provider on top, a de-emphasized metadata line below with context window,
 * $/Mtok pricing, and capability tags from the vendored models.dev catalog.
 * Every row paints a ★ (accent when pinned, muted otherwise) so the clickable
 * pin zone is a visible affordance. Models without catalog metadata show a
 * provider-specific fallback line.
 */
class ModelPickerRenderer extends DefaultListCellRenderer {

    private final ModelSelectorPreferences prefs;
    private final ModelCapabilityCatalog catalog;

    ModelPickerRenderer(ModelSelectorPreferences prefs, ModelCapabilityCatalog catalog) {
        this.prefs = prefs;
        this.catalog = catalog;
    }

    int rowHeight(JList<?> list) {
        Component prototype = getListCellRendererComponent(
                list, "claude-sonnet-4-6", 0, false, false);
        return prototype.getPreferredSize().height;
    }

    /**
     * Builds the metadata line for a model, e.g.
     * {@code "400k ctx · $1.25/$10 per Mtok · vision · thinking"}.
     * Empty when nothing is known about the model.
     */
    static String metadataFor(String modelId, ModelCapabilityCatalog catalog) {
        if (modelId == null || modelId.isEmpty()) {
            return "";
        }
        if (modelId.startsWith("ollama:")) {
            return "local model";
        }
        Optional<ModelCapabilityCatalog.CapabilityInfo> caps = catalog.capabilities(modelId);
        if (caps.isEmpty()) {
            return "";
        }
        ModelCapabilityCatalog.CapabilityInfo info = caps.get();
        List<String> parts = new ArrayList<>();
        if (info.getContextWindow() > 0) {
            parts.add(formatContext(info.getContextWindow()) + " ctx");
        }
        if (info.hasCost()) {
            parts.add("$" + formatCost(info.getCostIn()) + "/$"
                    + formatCost(info.getCostOut()) + " per Mtok");
        }
        if (info.isVision()) {
            parts.add("vision");
        }
        if (info.isReasoning()) {
            parts.add("thinking");
        }
        return String.join(" · ", parts);
    }

    static String secondLineFor(String modelId, ModelCapabilityCatalog catalog) {
        String metadata = metadataFor(modelId, catalog);
        if (!metadata.isEmpty()) {
            return metadata;
        }
        if (modelId != null) {
            if (modelId.startsWith("codex:")) {
                return "Uses your ChatGPT plan through the Codex CLI";
            }
            if (modelId.startsWith("claude-code:")) {
                return "Uses your Claude plan through Claude Code";
            }
            if (modelId.startsWith("opencode:")) {
                return "Runs through the OpenCode CLI";
            }
            if (modelId.startsWith("openrouter:")) {
                return "Routed through OpenRouter";
            }
        }
        String provider = ModelDisplay.parse(modelId)[1];
        return provider.isEmpty() ? "Model" : provider + " model";
    }

    /** Compact token count: 1048576 → "1M", 1500000 → "1.5M", 131072 → "131k". */
    static String formatContext(long tokens) {
        if (tokens >= 1_000_000) {
            long whole = tokens / 1_000_000;
            long tenth = (tokens % 1_000_000) / 100_000;
            return tenth == 0 ? whole + "M" : whole + "." + tenth + "M";
        }
        if (tokens >= 1_000) {
            return Math.round(tokens / 1000.0) + "k";
        }
        return Long.toString(tokens);
    }

    /** Trailing-zero-free price: 10.0 → "10", 1.25 → "1.25", 0.075 → "0.075". */
    static String formatCost(double perMtok) {
        return new BigDecimal(Double.toString(perMtok)).stripTrailingZeros().toPlainString();
    }

    /** Escapes the HTML specials that could appear in a model id. */
    static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String ellipsize(String text, FontMetrics fm, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (text.isEmpty() || fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        if (fm.stringWidth(ellipsis) > maxWidth) {
            return "";
        }
        int prefixEnd = 0;
        for (int offset = 0; offset < text.length();) {
            int next = offset + Character.charCount(text.codePointAt(offset));
            if (fm.stringWidth(text.substring(0, next) + ellipsis) > maxWidth) {
                break;
            }
            prefixEnd = next;
            offset = next;
        }
        return text.substring(0, prefixEnd) + ellipsis;
    }

    @Override
    public Component getListCellRendererComponent(
            JList<?> list, Object value, int index,
            boolean isSelected, boolean cellHasFocus) {
        String modelId = value == null ? "" : value.toString();
        String[] parts = ModelDisplay.parse(modelId);
        String secondLine = secondLineFor(modelId, catalog);
        boolean pinned = prefs.isPinned(modelId);

        Component c = super.getListCellRendererComponent(
                list, modelId, index, isSelected, cellHasFocus);
        javax.swing.JLabel label = (javax.swing.JLabel) c;
        label.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_2, UiTokens.SPACE_3,
                UiTokens.SPACE_2, UiTokens.SPACE_3));
        label.setToolTipText(null);
        String title = parts[0];
        if (list.getWidth() > 0) {
            Insets insets = label.getInsets();
            Font labelFont = label.getFont();
            FontMetrics regularMetrics = label.getFontMetrics(labelFont);
            FontMetrics boldMetrics = label.getFontMetrics(labelFont.deriveFont(Font.BOLD));
            int providerWidth = parts[1].isEmpty()
                    ? 0 : regularMetrics.stringWidth(" · " + parts[1]);
            int availableWidth = list.getWidth() - insets.left - insets.right
                    - regularMetrics.stringWidth("★ ") - providerWidth - 8;
            title = ellipsize(title, boldMetrics, availableWidth);
            if (!title.equals(parts[0])) {
                label.setToolTipText(modelId);
            }
        }

        // every row paints a star so the pin affordance is visible (the left
        // zone is clickable in every row, not just pinned ones)
        Color primary = ThemeColors.foreground();
        Color secondary = isSelected ? primary : ThemeColors.secondaryText();
        Color starColor = pinned
                ? ThemeColors.accent()
                : isSelected ? primary : ThemeColors.secondaryText();
        StringBuilder html = new StringBuilder("<html>");
        html.append("<span style='color:rgb(").append(starColor.getRed()).append(',')
                .append(starColor.getGreen()).append(',').append(starColor.getBlue())
                .append(");'>★</span> ");
        html.append("<nobr><span style='color:rgb(").append(primary.getRed()).append(',')
                .append(primary.getGreen()).append(',').append(primary.getBlue())
                .append(");'><b>").append(escapeHtml(title)).append("</b>");
        if (!parts[1].isEmpty()) {
            html.append(" · ").append(escapeHtml(parts[1]));
        }
        html.append("</span></nobr>");
        html.append("<br><nobr><span style='color:rgb(")
                .append(secondary.getRed()).append(',')
                .append(secondary.getGreen()).append(',').append(secondary.getBlue())
                .append(");font-size:9px;'>").append(escapeHtml(secondLine))
                .append("</span></nobr>");
        html.append("</html>");
        label.setText(html.toString());
        label.setOpaque(true);
        label.setBackground(isSelected
                ? ThemeColors.selectedBackground() : ThemeColors.elevatedSurface());
        return label;
    }
}
