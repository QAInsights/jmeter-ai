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

    public IntellisensePopup() {
        popupMenu = new JPopupMenu();
        suggestionList = new JList<>();
        suggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        suggestionList.setFocusable(false);
        suggestionList.setCellRenderer(new SuggestionCellRenderer());
        scrollPane = new JScrollPane(suggestionList);
        scrollPane.setBorder(null);
        popupMenu.setBorder(BorderFactory.createLineBorder(org.qainsights.jmeter.ai.gui.theme.ThemeColors.border()));
        popupMenu.add(scrollPane);
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
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            String command = value == null ? "" : value.toString();
            String description = CommandIntellisenseProvider.getDescription(command);

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
