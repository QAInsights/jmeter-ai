package org.qainsights.jmeter.ai.gui;

import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

/**
 * Renders GitHub-flavored markdown tables into a {@link StyledDocument} as an
 * embedded grid component: header row bold on a tinted background, body rows
 * plain, thin borders all around. Without this, table markdown (| col | col |)
 * shows up as literal pipes in the transcript.
 * <p>
 * Extracted from {@link MarkdownRenderer} so both classes stay within the
 * project's file line limit.
 */
final class TableBlockRenderer {

    private TableBlockRenderer() {
    }

    /** True when a line can be part of a table block (contains a pipe). */
    static boolean isTableLine(String line) {
        return line != null && line.contains("|") && !line.isBlank();
    }

    /** True when a line is the header/body separator (e.g. {@code | --- | :-: | --- |}). */
    static boolean isTableSeparator(String line) {
        if (line == null) {
            return false;
        }
        String compact = line.replace(" ", "");
        if (compact.length() < 3 || !compact.contains("-")) {
            return false;
        }
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c != '|' && c != '-' && c != ':') {
                return false;
            }
        }
        return true;
    }

    /**
     * Splits a table row into trimmed cell values, dropping the empty slots a
     * leading/trailing pipe produces. Escaped pipes (\|) are kept literal.
     */
    static List<String> splitRow(String line) {
        String trimmed = line.trim();
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\\' && !escaped) {
                escaped = true;
                continue;
            }
            if (c == '|' && !escaped) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
                escaped = false;
            }
        }
        cells.add(current.toString().trim());
        // drop the edge slots from leading/trailing pipes
        if (!cells.isEmpty() && cells.get(0).isEmpty()) {
            cells.remove(0);
        }
        if (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
            cells.remove(cells.size() - 1);
        }
        return cells;
    }

    /** Renders header + rows as an embedded grid component at the document end. */
    static void render(StyledDocument doc, List<String> header, List<List<String>> rows)
            throws BadLocationException {
        int columns = Math.max(1, header.size());
        JPanel grid = new JPanel(new GridLayout(0, columns, 0, 0));
        grid.setBorder(BorderFactory.createLineBorder(ThemeColors.border()));
        grid.setOpaque(false);

        for (int col = 0; col < columns; col++) {
            grid.add(cell(col < header.size() ? header.get(col) : "", true));
        }
        for (List<String> row : rows) {
            for (int col = 0; col < columns; col++) {
                grid.add(cell(col < row.size() ? row.get(col) : "", false));
            }
        }

        SimpleAttributeSet componentStyle = new SimpleAttributeSet();
        StyleConstants.setComponent(componentStyle, grid);
        doc.insertString(doc.getLength(), " ", componentStyle);
        doc.insertString(doc.getLength(), "\n", null);
    }

    private static JLabel cell(String text, boolean header) {
        // Table cells come from AI responses - never render them as HTML.
        JLabel label = LabelUtils.plain(text);
        Font font = label.getFont();
        label.setFont(header
                ? font.deriveFont(Font.BOLD, font.getSize2D() - 1f)
                : font.deriveFont(font.getSize2D() - 1f));
        if (header) {
            label.setOpaque(true);
            label.setBackground(ThemeColors.codeBackground());
        } else {
            label.setOpaque(false);
        }
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 1, ThemeColors.border()),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        return label;
    }
}
