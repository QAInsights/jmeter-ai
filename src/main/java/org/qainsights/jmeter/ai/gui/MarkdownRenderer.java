package org.qainsights.jmeter.ai.gui;

import java.awt.Color;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders markdown text into a {@link StyledDocument} for the chat transcript.
 * <p>
 * Supported formatting: fenced code blocks (rendered as embedded panels with a
 * Copy button via {@link CodeBlockRenderer}), headings ({@code #}..{@code ###}),
 * bold, italic, inline code, unordered list bullets ({@code - }/{@code * }),
 * horizontal rules ({@code ---}), and links ({@code [label](url)}) shown as
 * accented underlined labels.
 * <p>
 * Extracted from {@link MessageProcessor} so both classes stay focused and
 * within the project's file line limit.
 */
class MarkdownRenderer {

    private static final Logger log = LoggerFactory.getLogger(
        MarkdownRenderer.class
    );

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
        "```([\\w-]*)\\s*([\\s\\S]*?)```"
    );
    private static final Pattern BULLET_PATTERN = Pattern.compile("^[-*]\\s+.*");
    private static final Pattern HR_PATTERN = Pattern.compile(
        "^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$"
    );
    private static final Pattern LINK_PATTERN = Pattern.compile(
        "\\[([^\\]]+)\\]\\(([^)]+)\\)"
    );

    private final Map<String, String> codeSnippets = new HashMap<>();

    /**
     * Processes a markdown message and applies formatting to the document.
     *
     * @param doc The document to apply formatting to
     * @param message The markdown message to process
     * @throws BadLocationException If there is an error with the document location
     */
    public void process(StyledDocument doc, String message)
        throws BadLocationException {
        log.info("Processing markdown message");

        // Extract code blocks first
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(message);
        StringBuffer sb = new StringBuffer();
        int codeBlockCount = 0;

        while (matcher.find()) {
            codeBlockCount++;
            String language = matcher.group(1).trim();
            String code = matcher.group(2);

            // Store the code snippet for potential reuse
            String snippetKey = "snippet_" + codeBlockCount;
            codeSnippets.put(snippetKey, code);

            // Replace the code block with a placeholder
            // Add extra newlines before and after for better spacing
            String placeholder =
                "\n[CODE_BLOCK:" + snippetKey + ":" + language + "]\n";
            matcher.appendReplacement(
                sb,
                Matcher.quoteReplacement(placeholder)
            );
        }
        matcher.appendTail(sb);

        // Process the text without code blocks
        String processedText = sb.toString();
        processBasicMarkdown(doc, processedText);
    }

    /**
     * Gets the stored code snippets.
     *
     * @return The map of code snippets
     */
    public Map<String, String> getCodeSnippets() {
        return codeSnippets;
    }

    /**
     * Processes basic markdown formatting and code block placeholders.
     */
    private void processBasicMarkdown(StyledDocument doc, String text)
        throws BadLocationException {
        String[] lines = text.split("\n");

        SimpleAttributeSet normal = new SimpleAttributeSet();
        StyleConstants.setFontFamily(normal, Font.DIALOG);

        SimpleAttributeSet bold = new SimpleAttributeSet(normal);
        StyleConstants.setBold(bold, true);

        SimpleAttributeSet italic = new SimpleAttributeSet(normal);
        StyleConstants.setItalic(italic, true);

        SimpleAttributeSet heading1 = headingOf(bold, 6);
        SimpleAttributeSet heading2 = headingOf(bold, 4);
        SimpleAttributeSet heading3 = headingOf(bold, 2);

        SimpleAttributeSet codeStyle = new SimpleAttributeSet();
        StyleConstants.setFontFamily(codeStyle, "Monospaced");
        StyleConstants.setBackground(codeStyle, ThemeColors.codeBackground());

        SimpleAttributeSet linkStyle = new SimpleAttributeSet(normal);
        StyleConstants.setForeground(linkStyle, ThemeColors.accent());
        StyleConstants.setUnderline(linkStyle, true);

        InlineStyles styles = new InlineStyles(
            normal, bold, italic, codeStyle, linkStyle
        );

        for (String line : lines) {
            // Check for code block placeholder
            if (
                line.trim().startsWith("[CODE_BLOCK:") &&
                line.trim().endsWith("]")
            ) {
                renderPlaceholderCodeBlock(doc, line, normal);
                continue;
            }

            if (HR_PATTERN.matcher(line).matches()) {
                renderHorizontalRule(doc);
                continue;
            }

            if (line.startsWith("# ")) {
                doc.insertString(doc.getLength(), line.substring(2) + "\n", heading1);
            } else if (line.startsWith("## ")) {
                doc.insertString(doc.getLength(), line.substring(3) + "\n", heading2);
            } else if (line.startsWith("### ")) {
                doc.insertString(doc.getLength(), line.substring(4) + "\n", heading3);
            } else if (BULLET_PATTERN.matcher(line).matches()) {
                // Unordered list item: normalized bullet + inline formatting
                doc.insertString(doc.getLength(), "• ", bold);
                processInline(doc, line.substring(2), styles, false);
            } else {
                processInline(doc, line, styles, true);
            }
        }
    }

    private static SimpleAttributeSet headingOf(SimpleAttributeSet base, int plus) {
        SimpleAttributeSet heading = new SimpleAttributeSet(base);
        StyleConstants.setFontSize(
            heading,
            StyleConstants.getFontSize(base) + plus
        );
        return heading;
    }

    /** Renders a stored code block referenced by a placeholder line. */
    private void renderPlaceholderCodeBlock(
        StyledDocument doc,
        String line,
        SimpleAttributeSet normal
    ) throws BadLocationException {
        String[] parts = line
            .trim()
            .substring(12, line.trim().length() - 1)
            .split(":");
        String snippetKey = parts[0];
        String language = parts.length > 1 ? parts[1] : "";

        String code = codeSnippets.get(snippetKey);
        if (code != null) {
            // Add extra spacing before the code block
            doc.insertString(doc.getLength(), "\n", normal);
            CodeBlockRenderer.render(doc, code, language);
            // Add extra spacing after the code block
            doc.insertString(doc.getLength(), "\n", normal);
        }
    }

    /** Renders a horizontal rule as a thin embedded divider component. */
    private void renderHorizontalRule(StyledDocument doc)
        throws BadLocationException {
        JPanel rule = new JPanel();
        rule.setBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeColors.border())
        );
        rule.setPreferredSize(new java.awt.Dimension(1, 6));
        rule.setOpaque(false);

        SimpleAttributeSet componentStyle = new SimpleAttributeSet();
        StyleConstants.setComponent(componentStyle, rule);
        doc.insertString(doc.getLength(), " ", componentStyle);
        doc.insertString(doc.getLength(), "\n", null);
    }

    /**
     * Writes one line with inline formatting (bold, italic, inline code,
     * links) applied.
     *
     * @param appendNewline true to terminate the line with a newline
     */
    private void processInline(
        StyledDocument doc,
        String line,
        InlineStyles styles,
        boolean appendNewline
    ) throws BadLocationException {
        StringBuilder currentText = new StringBuilder();
        SimpleAttributeSet currentStyle = styles.normal;

        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);

            // Links: [label](url) - label shown accented/underlined, url dropped
            if (c == '[') {
                Matcher m = LINK_PATTERN.matcher(line.substring(i));
                if (m.lookingAt() && m.start() == 0) {
                    doc.insertString(doc.getLength(), currentText.toString(), currentStyle);
                    currentText.setLength(0);
                    doc.insertString(doc.getLength(), m.group(1), styles.link);
                    i += m.end();
                    continue;
                }
            }

            // Check for bold (**text**)
            if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                doc.insertString(doc.getLength(), currentText.toString(), currentStyle);
                currentText.setLength(0);
                currentStyle = currentStyle == styles.bold ? styles.normal : styles.bold;
                i += 2;
            } else if (c == '*') {
                // Check for italic (*text*)
                doc.insertString(doc.getLength(), currentText.toString(), currentStyle);
                currentText.setLength(0);
                currentStyle = currentStyle == styles.italic ? styles.normal : styles.italic;
                i++;
            } else if (c == '`') {
                // Check for inline code (`text`)
                doc.insertString(doc.getLength(), currentText.toString(), currentStyle);
                currentText.setLength(0);
                currentStyle = currentStyle == styles.code ? styles.normal : styles.code;
                i++;
            } else {
                currentText.append(c);
                i++;
            }
        }

        doc.insertString(
            doc.getLength(),
            currentText.toString() + (appendNewline ? "\n" : ""),
            currentStyle
        );
        if (!appendNewline) {
            doc.insertString(doc.getLength(), "\n", styles.normal);
        }
    }

    /** Named bundle of the inline styles used while scanning a line. */
    private static final class InlineStyles {
        final SimpleAttributeSet normal;
        final SimpleAttributeSet bold;
        final SimpleAttributeSet italic;
        final SimpleAttributeSet code;
        final SimpleAttributeSet link;

        InlineStyles(
            SimpleAttributeSet normal,
            SimpleAttributeSet bold,
            SimpleAttributeSet italic,
            SimpleAttributeSet code,
            SimpleAttributeSet link
        ) {
            this.normal = normal;
            this.bold = bold;
            this.italic = italic;
            this.code = code;
            this.link = link;
        }
    }
}
