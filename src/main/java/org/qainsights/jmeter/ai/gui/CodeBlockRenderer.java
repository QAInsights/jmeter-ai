package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

/**
 * Renders a fenced code block as an embedded panel inside the chat transcript:
 * a slim header bar (language label + Copy button) above the monospaced code,
 * wrapped in a subtle theme-aware rounded border.
 * <p>
 * Extracted from {@link MessageProcessor} to keep both classes focused (and
 * within the project's file line limit). The component hierarchy
 * (codePanel > headerPanel > "Copy" JButton) is intentionally preserved - the
 * existing copy-behavior tests navigate it directly.
 */
final class CodeBlockRenderer {

    private CodeBlockRenderer() {
    }

    /**
     * Inserts a styled code block panel at the end of the document.
     *
     * @param doc      the transcript document
     * @param code     the code to render (leading/trailing blank lines trimmed)
     * @param language the fence language label, may be empty
     */
    static void render(StyledDocument doc, String code, String language)
        throws BadLocationException {
        Color codeBg = ThemeColors.codeBackground();

        JPanel codePanel = new JPanel(new BorderLayout());
        codePanel.setBackground(codeBg);
        codePanel.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.border(), 1, true),
                BorderFactory.createEmptyBorder(2, 2, 2, 2)
            )
        );

        codePanel.add(createHeader(code, language, codeBg), BorderLayout.NORTH);
        codePanel.add(createCodeArea(code, codeBg), BorderLayout.CENTER);

        // Insert the code panel into the document
        SimpleAttributeSet panelStyle = new SimpleAttributeSet();
        StyleConstants.setComponent(panelStyle, codePanel);
        doc.insertString(doc.getLength(), " ", panelStyle);

        // Add extra spacing after the code block
        SimpleAttributeSet spacer = new SimpleAttributeSet();
        StyleConstants.setFontFamily(spacer, "Monospaced");
        doc.insertString(doc.getLength(), "\n", spacer);
    }

    /** Header bar: language label on the left, Copy button on the right. */
    private static JPanel createHeader(String code, String language, Color codeBg) {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(codeBg);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 6));

        JLabel languageLabel = new JLabel(
            language.isEmpty() ? "code" : language.toLowerCase()
        );
        languageLabel.setFont(
            languageLabel.getFont().deriveFont(Font.BOLD, 11f)
        );
        languageLabel.setForeground(ThemeColors.secondaryText());
        headerPanel.add(languageLabel, BorderLayout.WEST);

        JButton copyButton = new JButton("Copy");
        copyButton.setToolTipText("Copy code to clipboard");
        copyButton.setFont(copyButton.getFont().deriveFont(11f));
        copyButton.setFocusPainted(false);
        copyButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copyButton.setMargin(new java.awt.Insets(2, 8, 2, 8));
        copyButton.addActionListener(
            new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    // Copy code to clipboard
                    java.awt.Toolkit.getDefaultToolkit()
                        .getSystemClipboard()
                        .setContents(
                            new java.awt.datatransfer.StringSelection(code),
                            null
                        );

                    // Provide visual feedback
                    copyButton.setText("Copied!");
                    Timer timer = new Timer(1500, event ->
                        copyButton.setText("Copy")
                    );
                    timer.setRepeats(false);
                    timer.start();
                }
            }
        );

        headerPanel.add(copyButton, BorderLayout.EAST);
        return headerPanel;
    }

    /** Read-only monospaced code body sized to the current UI font. */
    private static Component createCodeArea(String code, Color codeBg) {
        JTextArea codeArea = new JTextArea(code.trim()); // Trim to remove extra lines
        Font base = UIManager.getFont("TextField.font");
        float size = base != null ? base.getSize2D() : 12f;
        codeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.round(size)));
        codeArea.setEditable(false);
        codeArea.setBackground(codeBg);
        codeArea.setForeground(ThemeColors.themeColor("TextArea.foreground", ThemeColors.foreground()));
        codeArea.setBorder(BorderFactory.createEmptyBorder(2, 10, 6, 10));
        return codeArea;
    }
}
