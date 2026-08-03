package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import java.util.List;
import javax.swing.JLabel;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.JPanel;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TableBlockRenderer}: separator/line detection, row
 * splitting (including edge pipes and escaped pipes), and grid rendering.
 */
class TableBlockRendererTest {

    @Test
    void separatorDetection() {
        assertTrue(TableBlockRenderer.isTableSeparator("| --- | --- |"));
        assertTrue(TableBlockRenderer.isTableSeparator("|---|---|"));
        assertTrue(TableBlockRenderer.isTableSeparator("| :--- | ---: | :-: |"));
        assertFalse(TableBlockRenderer.isTableSeparator("| Level | Logger |"));
        assertFalse(TableBlockRenderer.isTableSeparator("plain --- text"));
        assertFalse(TableBlockRenderer.isTableSeparator(null));
    }

    @Test
    void tableLineDetection() {
        assertTrue(TableBlockRenderer.isTableLine("| a | b |"));
        assertFalse(TableBlockRenderer.isTableLine("no pipes"));
        assertFalse(TableBlockRenderer.isTableLine(""));
        assertFalse(TableBlockRenderer.isTableLine(null));
    }

    @Test
    void splitRowTrimsAndDropsEdgeSlots() {
        assertEquals(List.of("a", "b"), TableBlockRenderer.splitRow("| a | b |"));
        assertEquals(List.of("a", "b"), TableBlockRenderer.splitRow("a | b"));
        assertEquals(List.of("a", "b", ""), TableBlockRenderer.splitRow("| a | b ||"));
    }

    @Test
    void splitRowKeepsEscapedPipes() {
        // the backslash is consumed and the pipe is kept literal (markdown unescape)
        assertEquals(List.of("a|b", "c"), TableBlockRenderer.splitRow("| a\\|b | c |"));
    }

    @Test
    void renderInsertsGridComponent() throws Exception {
        StyledDocument doc = new DefaultStyledDocument();
        TableBlockRenderer.render(doc, List.of("Level", "Logger"),
                List.of(List.of("ERROR", "GoogleAiService"), List.of("WARN", "Plugin")));

        JPanel grid = findGrid(doc);
        assertNotNull(grid, "a grid component must be embedded in the document");
        java.awt.Component[] cells = grid.getComponents();
        // 2 columns x (1 header + 2 body rows) = 6 cells
        assertEquals(6, cells.length);
        assertTrue(cells[0] instanceof JLabel);
        assertEquals("Level", ((JLabel) cells[0]).getText());
        assertEquals("GoogleAiService", ((JLabel) cells[3]).getText());
        // cells render AI content - HTML must be disabled on every one
        for (java.awt.Component cell : cells) {
            assertEquals(Boolean.TRUE, ((JLabel) cell).getClientProperty("html.disable"));
        }
    }

    @Test
    void htmlPrefixedCellRendersAsPlainText() throws Exception {
        String payload = "<html><img src=https://tracker.example/x>";
        StyledDocument doc = new DefaultStyledDocument();
        TableBlockRenderer.render(doc, List.of("Col"), List.of(List.of(payload)));

        JPanel grid = findGrid(doc);
        assertNotNull(grid);
        JLabel cell = (JLabel) grid.getComponent(1); // body cell
        assertEquals(payload, cell.getText(), "payload must survive literally");
        assertEquals(Boolean.TRUE, cell.getClientProperty("html.disable"),
                "HTML rendering must be disabled for AI-provided cell content");
    }

    @Test
    void renderHandlesRaggedRows() throws Exception {
        StyledDocument doc = new DefaultStyledDocument();
        TableBlockRenderer.render(doc, List.of("a", "b", "c"),
                List.of(List.of("1"), List.of("2", "3", "4", "5")));
        JPanel grid = findGrid(doc);
        assertNotNull(grid);
        // 3 columns x (1 + 2) = 9 cells, missing cells padded empty
        assertEquals(9, grid.getComponentCount());
        assertEquals("", ((JLabel) grid.getComponent(4)).getText());
    }

    private static JPanel findGrid(StyledDocument doc) {
        for (int offset = 0; offset < doc.getLength(); offset++) {
            Element leaf = doc.getCharacterElement(offset);
            java.awt.Component component = StyleConstants.getComponent(leaf.getAttributes());
            if (component instanceof JPanel && ((JPanel) component).getLayout() instanceof java.awt.GridLayout) {
                return (JPanel) component;
            }
        }
        return null;
    }
}
