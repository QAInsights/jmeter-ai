package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;

import org.apache.jmeter.gui.GuiPackage;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

/** Displays a Make it pass Markdown report inside JMeter. */
final class FixReportDialog extends JDialog {
    private static final String READ_ERROR = "The report file could not be read.";

    private final File reportFile;
    private final Consumer<String> reportFallback;
    private final JTextPane reportPane = new JTextPane();
    private final JButton copyButton = new JButton("Copy");
    private final String markdown;

    FixReportDialog(File reportFile, Consumer<String> reportFallback) {
        super(mainFrame(), "Make it pass report", ModalityType.MODELESS);
        this.reportFile = reportFile;
        this.reportFallback = reportFallback == null ? ignored -> { } : reportFallback;

        String path = reportPath();
        String content;
        boolean readable = false;
        try {
            if (reportFile == null) {
                throw new IOException("No report file");
            }
            content = Files.readString(reportFile.toPath(), StandardCharsets.UTF_8);
            readable = true;
        } catch (IOException | SecurityException e) {
            content = READ_ERROR + "\n\n" + path;
        }
        markdown = content;
        copyButton.setEnabled(readable);

        setContentPane(buildContent(path));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        configureEscape();
        setSize(780, 640);
        setLocationRelativeTo(getOwner());
    }

    private JPanel buildContent(String path) {
        JPanel root = new JPanel(new BorderLayout(0, UiTokens.SPACE_3));
        root.setBackground(ThemeColors.canvas());
        root.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_4, UiTokens.SPACE_4, UiTokens.SPACE_3, UiTokens.SPACE_4));

        reportPane.setEditable(false);
        reportPane.setBackground(ThemeColors.canvas());
        reportPane.setForeground(ThemeColors.foreground());
        java.awt.Font textFont = UIManager.getFont("TextPane.font");
        if (textFont != null) {
            reportPane.setFont(textFont);
        }
        try {
            new MarkdownRenderer().process(reportPane.getStyledDocument(), markdown);
        } catch (BadLocationException e) {
            reportPane.setText(markdown);
        }
        reportPane.setCaretPosition(0);

        JScrollPane scrollPane = new JScrollPane(reportPane);
        scrollPane.setBorder(BorderFactory.createLineBorder(ThemeColors.border()));
        scrollPane.getViewport().setBackground(ThemeColors.canvas());
        root.add(scrollPane, BorderLayout.CENTER);

        JTextField pathField = new JTextField(path);
        pathField.setEditable(false);
        pathField.setOpaque(false);
        pathField.setBorder(BorderFactory.createEmptyBorder());
        pathField.setFont(UiTokens.caption(pathField.getFont()));
        pathField.setForeground(ThemeColors.secondaryText());
        pathField.setToolTipText(path);

        JButton openButton = new JButton("Open file");
        openButton.addActionListener(event -> openFile());
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(event -> dispose());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.SPACE_2, 0));
        actions.setOpaque(false);
        actions.add(copyButton);
        actions.add(openButton);
        actions.add(closeButton);

        JPanel footer = new JPanel(new BorderLayout(UiTokens.SPACE_3, 0));
        footer.setOpaque(false);
        footer.add(pathField, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        copyButton.addActionListener(event -> copyMarkdown());
        return root;
    }

    private void configureEscape() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                dispose();
            }
        });
    }

    private void copyMarkdown() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(markdown), null);
            copyButton.setText("Copied");
            Timer timer = new Timer(1500, event -> copyButton.setText("Copy"));
            timer.setRepeats(false);
            timer.start();
        } catch (HeadlessException | IllegalStateException | SecurityException ignored) {
            // Clipboard access is not available in every desktop environment.
        }
    }

    private void openFile() {
        if (reportFile == null) {
            reportFallback.accept(reportPath());
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(reportFile);
                return;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Fall back to the existing report-opening handler.
        }
        reportFallback.accept(reportPath());
    }

    private String reportPath() {
        return reportFile == null ? "" : reportFile.getAbsolutePath();
    }

    private static java.awt.Frame mainFrame() {
        GuiPackage gui = GuiPackage.getInstance();
        return gui == null ? null : gui.getMainFrame();
    }
}
