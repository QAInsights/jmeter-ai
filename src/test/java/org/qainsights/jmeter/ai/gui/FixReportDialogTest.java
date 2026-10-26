package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FixReportDialogTest {
    @TempDir
    Path tempDir;

    @Test
    void rendersReportHeadingInTheDocument() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path report = tempDir.resolve("report.md");
        Files.writeString(report, "# Sample report\n\nA short summary.");

        FixReportDialog dialog = showDialog(report);
        try {
            assertTrue(documentText(dialog).contains("Sample report"));
        } finally {
            dispose(dialog);
        }
    }

    @Test
    void reportTextRemainsSelectableWithInvisibleCaret() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path report = tempDir.resolve("selectable.md");
        Files.writeString(report, "# Sample report\n\nA short summary.");

        FixReportDialog dialog = showDialog(report);
        try {
            AtomicReference<String> selectedText = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                JTextPane pane = findTextPane(dialog);
                assertEquals(pane.getBackground(), pane.getCaretColor());
                pane.select(0, "Sample".length());
                selectedText.set(pane.getSelectedText());
            });
            assertEquals("Sample", selectedText.get());
        } finally {
            dispose(dialog);
        }
    }

    @Test
    void missingReportShowsReadErrorAndPath() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Path report = tempDir.resolve("missing.md");

        FixReportDialog dialog = showDialog(report);
        try {
            String text = documentText(dialog);
            assertTrue(text.contains("The report file could not be read."));
            assertTrue(text.contains(report.toFile().getAbsolutePath()));
        } finally {
            dispose(dialog);
        }
    }

    @Test
    void copyPutsMarkdownOnTheSystemClipboardWhenAvailable() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Clipboard clipboard;
        try {
            clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        } catch (HeadlessException | IllegalStateException | SecurityException e) {
            assumeTrue(false, "System clipboard is unavailable");
            return;
        }

        String markdown = "# Copy this report\n\nKeep the Markdown.";
        Path report = tempDir.resolve("copy.md");
        Files.writeString(report, markdown);
        FixReportDialog dialog = showDialog(report);
        try {
            JButton copyButton = findButton(dialog, "Copy");
            SwingUtilities.invokeAndWait(copyButton::doClick);
            Transferable contents;
            try {
                contents = clipboard.getContents(null);
            } catch (HeadlessException | IllegalStateException | SecurityException e) {
                assumeTrue(false, "System clipboard is unavailable");
                return;
            }
            assertNotNull(contents);
            assertTrue(contents.isDataFlavorSupported(DataFlavor.stringFlavor));
            assertEquals(markdown, contents.getTransferData(DataFlavor.stringFlavor));
            SwingUtilities.invokeAndWait(() -> assertEquals("Copied", copyButton.getText()));
        } finally {
            dispose(dialog);
        }
    }

    private static FixReportDialog showDialog(Path report) throws Exception {
        AtomicReference<FixReportDialog> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FixReportDialog dialog = new FixReportDialog(report.toFile(), ignored -> { });
            dialog.setVisible(true);
            result.set(dialog);
        });
        return result.get();
    }

    private static String documentText(JDialog dialog) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JTextPane pane = findTextPane(dialog);
            try {
                result.set(pane.getDocument().getText(0, pane.getDocument().getLength()));
            } catch (BadLocationException e) {
                throw new AssertionError(e);
            }
        });
        return result.get();
    }

    private static JTextPane findTextPane(Component component) {
        if (component instanceof JTextPane pane) {
            return pane;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JTextPane pane = findTextPane(child);
                if (pane != null) {
                    return pane;
                }
            }
        }
        return null;
    }

    private static JButton findButton(Component component, String text) throws Exception {
        AtomicReference<JButton> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> findButton(component, text, result));
        return result.get();
    }

    private static void findButton(Component component, String text, AtomicReference<JButton> result) {
        if (component instanceof JButton button && text.equals(button.getText())) {
            result.set(button);
            return;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                findButton(child, text, result);
            }
        }
    }

    private static void dispose(JDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(dialog::dispose);
    }
}
