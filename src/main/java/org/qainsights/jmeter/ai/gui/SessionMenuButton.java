package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.qainsights.jmeter.ai.service.session.ConversationExporter;
import org.qainsights.jmeter.ai.service.session.ConversationSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Header overflow menu for conversation-level actions. Today that's
 * "Export chat" (Markdown / HTML); future session actions (history picker,
 * rename) hang off the same popup. The heavy lifting lives in
 * {@link ConversationExporter} - this class is dialog plumbing only.
 */
class SessionMenuButton extends JButton {

    private static final Logger log = LoggerFactory.getLogger(SessionMenuButton.class);

    private final transient Component dialogParent;
    private final transient Supplier<ConversationSession> sessionSupplier;
    private final JPopupMenu menu;

    SessionMenuButton(Component dialogParent, Supplier<ConversationSession> sessionSupplier) {
        super("Export");
        this.dialogParent = dialogParent;
        this.sessionSupplier = sessionSupplier;
        setIcon(ChevronIcons.down(10));
        setToolTipText("Conversation actions (export and more)");
        setFocusPainted(false);

        menu = new JPopupMenu();
        menu.add(exportItem("Export chat as Markdown…", ConversationExporter.Format.MARKDOWN));
        menu.add(exportItem("Export chat as HTML…", ConversationExporter.Format.HTML));
        addActionListener(e -> menu.show(this, 0, getHeight()));
    }

    private JMenuItem exportItem(String label, ConversationExporter.Format format) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> exportWithDialog(format));
        return item;
    }

    /** Number of items in the popup (for tests). */
    int menuItemCount() {
        return menu.getComponentCount();
    }

    private void exportWithDialog(ConversationExporter.Format format) {
        ConversationSession session = sessionSupplier.get();
        if (session == null || session.turns().isEmpty()) {
            JOptionPane.showMessageDialog(dialogParent,
                    "There's nothing to export yet - send a message first.",
                    "Export chat", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("feather-wand-chat-" + session.id() + format.extension()));
        chooser.setFileFilter(new FileNameExtensionFilter(
                format == ConversationExporter.Format.HTML ? "HTML files" : "Markdown files",
                format.extension().substring(1)));
        if (chooser.showSaveDialog(dialogParent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (!target.getFileName().toString().endsWith(format.extension())) {
            target = target.resolveSibling(target.getFileName() + format.extension());
        }
        if (java.nio.file.Files.exists(target) && JOptionPane.showConfirmDialog(dialogParent,
                "Replace the existing file?\n" + target.getFileName(),
                "Export chat", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            ConversationExporter.write(session, target, format);
        } catch (IOException e) {
            log.warn("Could not export conversation to {}", target, e);
            JOptionPane.showMessageDialog(dialogParent,
                    "Could not write the export file:\n" + e.getMessage(),
                    "Export chat", JOptionPane.ERROR_MESSAGE);
        }
    }
}
