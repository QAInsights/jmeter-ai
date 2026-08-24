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
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

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
                BorderFactory.createLineBorder(ThemeColors.separator(), 1, true),
                BorderFactory.createEmptyBorder(
                        UiTokens.SPACE_1, UiTokens.SPACE_1,
                        UiTokens.SPACE_1, UiTokens.SPACE_1)
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
        headerPanel.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_1, UiTokens.SPACE_2,
                UiTokens.SPACE_1, UiTokens.SPACE_1));

        JLabel languageLabel = new JLabel(
            language.isEmpty() ? "code" : language.toLowerCase()
        );
        languageLabel.setFont(UiTokens.label(languageLabel.getFont()));
        languageLabel.setForeground(ThemeColors.secondaryText());
        headerPanel.add(languageLabel, BorderLayout.WEST);

        JButton copyButton = new QuietButton("Copy");
        copyButton.setIcon(ActionIcons.copy(12));
        copyButton.setIconTextGap(UiTokens.SPACE_1);
        copyButton.setToolTipText("Copy code to clipboard");
        copyButton.setFont(UiTokens.caption(copyButton.getFont()));
        copyButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
                    copyButton.setText("Copied \u2713");
                    Timer timer = new Timer(2000, event ->
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
        codeArea.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_1, UiTokens.SPACE_2,
                UiTokens.SPACE_2, UiTokens.SPACE_2));
        return codeArea;
    }
}
