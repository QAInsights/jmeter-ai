package org.qainsights.jmeter.ai.intellisense;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

/**
 * Popup panel for displaying intellisense suggestions below the input box.
 */
public class IntellisensePopup {
    private final JPopupMenu popupMenu;
    protected final JList<String> suggestionList; // Changed to protected for testing
    private final JScrollPane scrollPane;

    /**
     * Supplies the one-line description shown under a suggestion. Defaults to
     * command descriptions; prompt mode swaps in prompt previews. Held as a
     * field (not captured by the renderer) so mode switches take effect on the
     * next repaint without rebuilding the renderer.
     */
    private java.util.function.Function<String, String> descriptionLookup =
            CommandIntellisenseProvider::getDescription;

    public IntellisensePopup() {
        popupMenu = new JPopupMenu();
        suggestionList = new JList<>();
        suggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        suggestionList.setFocusable(false);
        suggestionList.setSelectionBackground(
                org.qainsights.jmeter.ai.gui.theme.ThemeColors.selectedBackground());
        suggestionList.setSelectionForeground(
                org.qainsights.jmeter.ai.gui.theme.ThemeColors.foreground());
        suggestionList.setFixedCellHeight(UiTokens.SUGGESTION_ROW_HEIGHT);
        suggestionList.setCellRenderer(new SuggestionCellRenderer(() -> descriptionLookup));
        scrollPane = new JScrollPane(suggestionList);
        scrollPane.setBorder(null);
        popupMenu.setBackground(org.qainsights.jmeter.ai.gui.theme.ThemeColors.elevatedSurface());
        popupMenu.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                        org.qainsights.jmeter.ai.gui.theme.ThemeColors.separator()),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        popupMenu.add(scrollPane);
    }

    /** Overrides how descriptions under suggestions are resolved (e.g. prompt previews). */
    public void setDescriptionLookup(java.util.function.Function<String, String> lookup) {
        this.descriptionLookup = lookup != null ? lookup : CommandIntellisenseProvider::getDescription;
    }

    /**
     * Shows the popup docked above the anchor, stretched to the anchor's
     * width - same placement rule as the model picker. The input box sits at
     * the bottom of the chat panel, so opening upward keeps the suggestions
     * on screen and off the text being typed.
     */
    public void showAbove(Component parent, int x, int y, List<String> suggestions) {
        prepare(suggestions);
        if (parent.getWidth() > 0) {
            Dimension preferred = scrollPane.getPreferredSize();
            scrollPane.setPreferredSize(new Dimension(parent.getWidth(), preferred.height));
        }
        popupMenu.pack();
        popupMenu.show(parent, x, aboveY(y, popupMenu.getPreferredSize().height));
        parent.requestFocusInWindow();
    }

    private void prepare(List<String> suggestions) {
        suggestionList.setListData(suggestions.toArray(new String[0]));
        suggestionList.setSelectedIndex(0);
        suggestionList.setVisibleRowCount(Math.min(5, suggestions.size()));
    }

    /**
     * Y for an upward-opening popup: the anchor minus its height. This is
     * negative when the anchor is the input's top edge - legal for
     * {@code JPopupMenu.show}, which auto-adjusts to keep the popup on
     * screen. Clamping here would pin the popup's top to the anchor and
     * make it extend downward over the input instead.
     */
    static int aboveY(int anchorY, int popupHeight) {
        return anchorY - popupHeight;
    }

    public void hide() {
        popupMenu.setVisible(false);
    }

    public boolean isVisible() {
        return popupMenu.isVisible();
    }

    public void addSuggestionClickListener(MouseListener listener) {
        suggestionList.addMouseListener(listener);
    }

    public void addSuggestionKeyListener(KeyListener listener) {
        suggestionList.addKeyListener(listener);
    }

    public String getSelectedValue() {
        return suggestionList.getSelectedValue();
    }

    public void setSelectedIndex(int index) {
        suggestionList.setSelectedIndex(index);
        // keep the selection in view when arrowing past the visible rows
        suggestionList.ensureIndexIsVisible(index);
    }

    public int getSuggestionCount() {
        return suggestionList.getModel().getSize();
    }
    
    /**
     * Gets the currently selected index in the suggestion list.
     *
     * @return The selected index, or 0 if nothing is selected
     */
    public int getSelectedIndex() {
        return suggestionList.getSelectedIndex();
    }

    /**
     * Two-line suggestion cell: the command in bold on top, its one-line
     * description in de-emphasized text below. Suggestions without a known
     * description render as a single line.
     */
    static class SuggestionCellRenderer extends DefaultListCellRenderer {
        private final java.util.function.Supplier<java.util.function.Function<String, String>> descriptionLookup;

        SuggestionCellRenderer() {
            this(() -> CommandIntellisenseProvider::getDescription);
        }

        SuggestionCellRenderer(
                java.util.function.Supplier<java.util.function.Function<String, String>> descriptionLookup) {
            this.descriptionLookup = descriptionLookup;
        }

        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            String command = value == null ? "" : value.toString();
            String description = descriptionLookup.get().apply(command);
            if (description == null) {
                description = "";
            }

            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, command, index, isSelected, cellHasFocus);

            if (description.isEmpty()) {
                // Swing renders any label text starting with "<html" as HTML,
                // so a user-saved prompt name must not reach setText raw.
                label.setText(isHtml(command) ? "<html>" + escapeHtml(command) + "</html>" : command);
                label.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
            } else {
                Color secondary = org.qainsights.jmeter.ai.gui.theme.ThemeColors.secondaryText();
                label.setText("<html><b>" + escapeHtml(command) + "</b><br>"
                        + "<span style='color:rgb(" + secondary.getRed() + ","
                        + secondary.getGreen() + "," + secondary.getBlue()
                        + ");font-size:9px;'>" + escapeHtml(description) + "</span></html>");
                label.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
            }
            label.setOpaque(true);
            label.setBackground(isSelected
                    ? org.qainsights.jmeter.ai.gui.theme.ThemeColors.selectedBackground()
                    : org.qainsights.jmeter.ai.gui.theme.ThemeColors.elevatedSurface());
            return label;
        }

        /** True when Swing would interpret the text as HTML rather than plain text. */
        private static boolean isHtml(String text) {
            return text.regionMatches(true, 0, "<html", 0, 5);
        }

        /** Escapes user-controlled text for safe injection into a Swing HTML cell. */
        static String escapeHtml(String text) {
            StringBuilder sb = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '&' -> sb.append("&amp;");
                    case '<' -> sb.append("&lt;");
                    case '>' -> sb.append("&gt;");
                    case '"' -> sb.append("&quot;");
                    case '\'' -> sb.append("&#39;");
                    default -> sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}
