package org.qainsights.jmeter.ai.intellisense;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;

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
        suggestionList.setCellRenderer(new SuggestionCellRenderer(() -> descriptionLookup));
        scrollPane = new JScrollPane(suggestionList);
        scrollPane.setBorder(null);
        popupMenu.setBorder(BorderFactory.createLineBorder(org.qainsights.jmeter.ai.gui.theme.ThemeColors.border()));
        popupMenu.add(scrollPane);
    }

    /** Overrides how descriptions under suggestions are resolved (e.g. prompt previews). */
    public void setDescriptionLookup(java.util.function.Function<String, String> lookup) {
        this.descriptionLookup = lookup != null ? lookup : CommandIntellisenseProvider::getDescription;
    }

    public void show(Component parent, int x, int y, List<String> suggestions) {
        suggestionList.setListData(suggestions.toArray(new String[0]));
        suggestionList.setSelectedIndex(0);
        suggestionList.setVisibleRowCount(Math.min(5, suggestions.size()));
        popupMenu.pack();
        popupMenu.show(parent, x, y);
        parent.requestFocusInWindow();
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
                label.setText(command);
                label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            } else {
                Color secondary = org.qainsights.jmeter.ai.gui.theme.ThemeColors.secondaryText();
                label.setText("<html><b>" + command + "</b><br>"
                        + "<span style='color:rgb(" + secondary.getRed() + ","
                        + secondary.getGreen() + "," + secondary.getBlue()
                        + ");font-size:9px;'>" + description + "</span></html>");
                label.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            }
            return label;
        }
    }
}
