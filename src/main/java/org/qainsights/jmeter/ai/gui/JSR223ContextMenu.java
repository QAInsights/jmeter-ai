package org.qainsights.jmeter.ai.gui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.CodeRefactorer;
import org.qainsights.jmeter.ai.utils.AiConfig;
import java.awt.event.ActionEvent;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/**
 * Adds a right-click context menu to JSR223 script areas.
 */
public class JSR223ContextMenu {
    private static final Logger log = LoggerFactory.getLogger(JSR223ContextMenu.class);
    private static boolean initialized = false;
    private static AiService sharedAiService;
    private final AiService aiService;

    public JSR223ContextMenu(AiService aiService) {
        this.aiService = aiService;
    }

    /**
     * Initialize the JSR223 context menu functionality.
     * This should be called when JMeter starts.
     */
    public static synchronized void initialize(AiService aiService) {
        if (initialized) {
            return;
        }

        // Store the AI service for later use
        sharedAiService = aiService;

        // Start a background thread to avoid slowing down JMeter startup
        new Thread(() -> {
            try {
                // Wait a bit for JMeter to fully initialize
                Thread.sleep(2000);
                setupContextMenus(aiService);
                initialized = true;
                log.info("JSR223 context menus initialized");
            } catch (Exception e) {
                log.error("Failed to initialize JSR223 context menus", e);
            }
        }).start();
    }

    /**
     * Setup context menus for all JSR223 components.
     * This will be called periodically to catch newly created components.
     */
    private static void setupContextMenus(AiService aiService) {
        // Find all JSR223 script editors and add context menus
        RSyntaxTextArea scriptEditor = CodeCommandHandler.findJSR223ScriptEditor();
        if (scriptEditor != null) {
            addContextMenu(scriptEditor, aiService);
        }

        // We're removing the timer-based polling because it can interfere with typing
        // by periodically resetting the cursor position.
        // Instead, we'll rely on the initialization during plugin startup to add the
        // context menu.

        // If you need to ensure that newly created components get context menus,
        // consider integrating with JMeter's component creation lifecycle rather than
        // polling.
    }

    /**
     * Checks if AI refactoring is enabled based on JMeter properties and available
     * service
     * 
     * @return true if refactoring is enabled, false otherwise
     */
    private static boolean isAiRefactoringEnabled() {
        // Check if AI refactoring is explicitly disabled
        String enableRefactoring = AiConfig.getProperty("jmeter.ai.refactoring.enabled", "true");
        return Boolean.parseBoolean(enableRefactoring);
    }

    /**
     * Adds a context menu to the specified RSyntaxTextArea.
     * 
     * @param textArea  The text area to add the context menu to
     * @param aiService The AI service to use for refactoring
     */
    static void addContextMenu(RSyntaxTextArea textArea, AiService aiService) {
        // Check if the text area already has a context menu
        if (textArea.getClientProperty("contextMenuAdded") != null) {
            return;
        }

        // Check if AI refactoring is enabled
        if (!isAiRefactoringEnabled()) {
            // If AI refactoring is disabled, don't add our custom context menu
            // This will allow the default JMeter context menu to appear
            log.debug("AI refactoring disabled, not adding custom context menu");
            return;
        }

        CodeRefactorer refactorer = aiService == null ? null : new CodeRefactorer(aiService);
        JPopupMenu popupMenu = textArea.getPopupMenu();
        final boolean usingExistingPopupMenu = popupMenu != null;
        final JMenuItem[] cutItem = new JMenuItem[1];
        final JMenuItem[] copyItem = new JMenuItem[1];
        if (popupMenu == null) {
            popupMenu = new JPopupMenu();

            JMenuItem newCutItem = new JMenuItem("Cut");
            newCutItem.addActionListener(e -> textArea.cut());
            popupMenu.add(newCutItem);
            cutItem[0] = newCutItem;

            JMenuItem newCopyItem = new JMenuItem("Copy");
            newCopyItem.addActionListener(e -> textArea.copy());
            popupMenu.add(newCopyItem);
            copyItem[0] = newCopyItem;

            JMenuItem pasteItem = new JMenuItem("Paste");
            pasteItem.addActionListener(e -> textArea.paste());
            popupMenu.add(pasteItem);

            popupMenu.addSeparator();

            JMenuItem selectAllItem = new JMenuItem("Select All");
            selectAllItem.addActionListener(e -> textArea.selectAll());
            popupMenu.add(selectAllItem);
        }

        if (usingExistingPopupMenu || popupMenu.getComponentCount() > 0) {
            popupMenu.addSeparator();
        }

        // Add AI refactoring menu item
        JMenuItem aiHelpItem = new JMenuItem("Refactor Code");
        aiHelpItem.addActionListener(e -> {
            if (refactorer != null) {
                refactorer.refactorSelectedCode(textArea);
            }
        });
        popupMenu.add(aiHelpItem);

        // Add AI try, catch, finally menu item
        JMenuItem aiTryCatchFinallyItem = new JMenuItem("Try, Catch, Finally");
        aiTryCatchFinallyItem.addActionListener(e -> {
            if (refactorer != null) {
                refactorer.refactorTryCatchFinally(textArea);
            }
        });
        popupMenu.add(aiTryCatchFinallyItem);

        // Add format code menu item
        JMenuItem formatCodeItem = new JMenuItem("Format Code");
        formatCodeItem.addActionListener(e -> {
            // This is a placeholder - you'd implement or connect to a code formatter
            String code = textArea.getText();
            if (code != null && !code.trim().isEmpty()) {
                try {
                    // Simple indentation formatting
                    code = formatGroovyCode(code);
                    textArea.setText(code);
                } catch (Exception ex) {
                    log.error("Error formatting code", ex);
                    JOptionPane.showMessageDialog(
                            textArea,
                            "Error formatting code: " + ex.getMessage(),
                            "Format Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            } else {
                JOptionPane.showMessageDialog(
                        textArea,
                        "No code to format",
                        "Format Code",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        });
        popupMenu.add(formatCodeItem);


        // Add the Functions Dialog menu item
        JMenuItem functionsDialogItem = new JMenuItem("Functions Dialog");
        functionsDialogItem.addActionListener(e -> {
            ActionRouter.getInstance().doActionNow(
                    new ActionEvent(e.getSource(), e.getID(), ActionNames.FUNCTIONS));
        });
        popupMenu.add(functionsDialogItem);

        popupMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                boolean hasSelection = textArea.getSelectedText() != null && !textArea.getSelectedText().isEmpty();
                boolean aiActionsEnabled = hasSelection && refactorer != null;

                if (cutItem[0] != null) {
                    cutItem[0].setEnabled(hasSelection);
                }
                if (copyItem[0] != null) {
                    copyItem[0].setEnabled(hasSelection);
                }
                aiHelpItem.setEnabled(aiActionsEnabled);
                aiTryCatchFinallyItem.setEnabled(aiActionsEnabled);
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        textArea.setPopupMenu(popupMenu);

        // Mark the text area as having a context menu
        textArea.putClientProperty("contextMenuAdded", Boolean.TRUE);
        log.debug("Added context menu to JSR223 script editor");
    }

    /**
     * Very simple code formatter for Groovy scripts
     * This is a basic implementation and could be improved
     */
    private static String formatGroovyCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return code;
        }

        StringBuilder formatted = new StringBuilder();
        String[] lines = code.split("\n");
        int indentLevel = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            // Adjust indent level based on closing braces at the start of the line
            if (trimmed.startsWith("}") || trimmed.startsWith(")")) {
                indentLevel = Math.max(0, indentLevel - 1);
            }

            // Add the appropriate indentation
            if (!trimmed.isEmpty()) {
                for (int i = 0; i < indentLevel; i++) {
                    formatted.append("    "); // 4 spaces per indent level
                }
                formatted.append(trimmed).append("\n");
            } else {
                formatted.append("\n"); // Preserve empty lines
            }

            // Increase indent level for lines ending with opening braces
            if (trimmed.endsWith("{") || trimmed.endsWith("(")) {
                indentLevel++;
            }

            // Decrease indent level for lines ending with closing braces
            if (trimmed.endsWith("}") || trimmed.endsWith(")")) {
                indentLevel = Math.max(0, indentLevel - 1);
            }
        }

        return formatted.toString();
    }

    /**
     * Public method to add a context menu to the current JSR223 editor.
     * This can be called when a user interacts with a JSR223 component to ensure
     * it has a context menu without using timers that might interfere with typing.
     */
    public static void addContextMenuToCurrentEditor() {
        if (!initialized) {
            log.warn("Cannot add context menu - not initialized");
            return;
        }

        RSyntaxTextArea scriptEditor = CodeCommandHandler.findJSR223ScriptEditor();
        if (scriptEditor != null) {
            addContextMenu(scriptEditor, sharedAiService);
        }
    }
}
