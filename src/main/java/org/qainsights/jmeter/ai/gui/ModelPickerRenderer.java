package org.qainsights.jmeter.ai.gui;

import java.awt.Color;
import java.awt.Component;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.service.prefs.ModelSelectorPreferences;
import org.qainsights.jmeter.ai.service.reasoning.ModelCapabilityCatalog;

/**
 * Two-line row for the model picker popup: the friendly display name and
 * provider on top, a de-emphasized metadata line below with context window,
 * $/Mtok pricing, and capability tags from the vendored models.dev catalog.
 * Every row paints a ★ (accent when pinned, muted otherwise) so the clickable
 * pin zone is a visible affordance. Models with no catalog entry (Ollama
 * locals, unknown ids) show a "local model" line or no second line at all.
 */
class ModelPickerRenderer extends DefaultListCellRenderer {

    private final ModelSelectorPreferences prefs;
    private final ModelCapabilityCatalog catalog;

    ModelPickerRenderer(ModelSelectorPreferences prefs, ModelCapabilityCatalog catalog) {
        this.prefs = prefs;
        this.catalog = catalog;
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

    @Override
    public Component getListCellRendererComponent(
            JList<?> list, Object value, int index,
            boolean isSelected, boolean cellHasFocus) {
        String modelId = value == null ? "" : value.toString();
        String[] parts = ModelDisplay.parse(modelId);
        String metadata = metadataFor(modelId, catalog);
        boolean pinned = prefs.isPinned(modelId);

        Component c = super.getListCellRendererComponent(
                list, modelId, index, isSelected, cellHasFocus);
        javax.swing.JLabel label = (javax.swing.JLabel) c;

        // every row paints a star so the pin affordance is visible (the left
        // zone is clickable in every row, not just pinned ones)
        Color starColor = pinned ? ThemeColors.accent() : ThemeColors.secondaryText();
        Color secondary = ThemeColors.secondaryText();
        StringBuilder html = new StringBuilder("<html>");
        html.append("<span style='color:rgb(").append(starColor.getRed()).append(',')
                .append(starColor.getGreen()).append(',').append(starColor.getBlue())
                .append(");'>★</span> ");
        html.append("<b>").append(escapeHtml(parts[0])).append("</b>");
        if (!parts[1].isEmpty()) {
            html.append(" · ").append(escapeHtml(parts[1]));
        }
        if (!metadata.isEmpty()) {
            html.append("<br><span style='color:rgb(").append(secondary.getRed()).append(',')
                    .append(secondary.getGreen()).append(',').append(secondary.getBlue())
                    .append(");font-size:9px;'>").append(escapeHtml(metadata)).append("</span>");
        }
        html.append("</html>");
        label.setText(html.toString());
        label.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        return label;
    }
}
