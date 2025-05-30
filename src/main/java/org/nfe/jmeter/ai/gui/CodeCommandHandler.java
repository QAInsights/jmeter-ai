package org.nfe.jmeter.ai.gui;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.nfe.jmeter.ai.service.AiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import javax.swing.JScrollPane;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles the @code command in the chat panel.
 * This class processes code from JSR223 elements and sends it to the AI for
 * improvement.
 */
public class CodeCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(CodeCommandHandler.class);

    // Store the last selected text and its editor
    private static String lastSelectedText = null;
    private static RSyntaxTextArea lastScriptEditor = null;
    private static final Pattern CODE_COMMAND_PATTERN = Pattern.compile("@code(?:\\s+(.+))?");
    private final AiService aiService;
    private boolean isProcessing = false;

    /**
     * Stores the currently selected text in the JSR223 editor.
     * This should be called when the user clicks in the chat box.
     */
    public static void storeSelectedText() {
        RSyntaxTextArea scriptEditor = findJSR223ScriptEditor();
        if (scriptEditor != null) {
            String selectedText = scriptEditor.getSelectedText();
            if (selectedText != null && !selectedText.isEmpty()) {
                lastSelectedText = selectedText;
                lastScriptEditor = scriptEditor;
            } else {
                // Keep the last stored text if nothing is currently selected
                if (lastScriptEditor == scriptEditor && lastSelectedText != null && !lastSelectedText.isEmpty()) {
                    lastSelectedText = lastSelectedText;
                }
            }
        } else {
            log.info("No JSR223 script editor found");
            // Try one more time with the all windows search as a fallback
            scriptEditor = findRSyntaxTextAreaInAllWindows();
            if (scriptEditor != null) {
                String selectedText = scriptEditor.getSelectedText();
                if (selectedText != null && !selectedText.isEmpty()) {
                    lastSelectedText = selectedText;
                    lastScriptEditor = scriptEditor;
                }
            }
        }
    }

    /**
     * Constructor.
     * 
     * @param claudeService The Claude service to use for AI processing
     */
    public CodeCommandHandler(AiService aiService) {
        this.aiService = aiService;
    }

    /**
     * Processes the @code command.
     * 
     * @param message The message containing the @code command
     * @return The AI response, or null if the command couldn't be processed
     */
    public String processCodeCommand(String message) {
        if (isProcessing) {
            return "I'm already processing a code command. Please wait.";
        }

        try {
            isProcessing = true;

            // Make sure the message starts with @code
            if (!message.trim().startsWith("@code")) {
                message = "@code " + message.trim();
            }

            // Extract the command after @code
            Matcher matcher = CODE_COMMAND_PATTERN.matcher(message.trim());
            if (!matcher.find()) {
                log.error("Failed to match pattern for message: '{}'", message);
                return "Invalid @code command format. Please use '@code [command]'.";
            }

            // Get the command, defaulting to "improve" if none specified
            String command = matcher.group(1);
            if (command == null || command.trim().isEmpty()) {
                command = "improve";
            }

            // Find the JSR223 element
            RSyntaxTextArea scriptEditor = findJSR223ScriptEditor();
            if (scriptEditor == null) {
                log.error("Could not find JSR223 script editor");
                return "No JSR223 element is currently selected. Please select a JSR223 element first.";
            }

            // Get the selected text - either from the editor or from the stored value
            String selectedText = scriptEditor.getSelectedText();

            // If no text is currently selected, use the last stored text if available
            if ((selectedText == null || selectedText.isEmpty())) {
                if (lastSelectedText != null && !lastSelectedText.isEmpty()) {
                    // Use the last stored text even if the editor is different
                    // This allows for more flexibility when switching between editors
                    selectedText = lastSelectedText;

                    // Update the last script editor to the current one
                    lastScriptEditor = scriptEditor;
                } else {
                    // If we still don't have text, try to get all text from the editor
                    selectedText = scriptEditor.getText();
                    if (selectedText != null && !selectedText.trim().isEmpty()) {
                        // Do nothing, we already have the text
                    }
                }
            }

            if (selectedText == null || selectedText.isEmpty()) {
                log.warn("No text is selected or available in the JSR223 editor");
                return "No text is selected in the JSR223 editor. Please select some code first or type some code in the editor.";
            }

            // Process the code with AI
            String aiResponse = processCodeWithAI(command, selectedText);
            if (aiResponse == null) {
                return "Failed to process the code. Please try again.";
            }

            // No longer automatically replacing text in the JSR223 editor
            // Users can manually copy and paste code from the chat response if needed
            log.info("Returning AI response to chat panel without modifying JSR223 editor");

            // Return the full AI response to display in the chat panel
            return aiResponse;
        } catch (Exception e) {
            log.error("Error processing @code command", e);
            return "Error processing @code command: " + e.getMessage();
        } finally {
            isProcessing = false;
        }
    }

    /**
     * Processes the code with the AI service.
     * 
     * @param command      The command to process
     * @param selectedText The selected text to process
     * @return The full AI response, or null if processing failed
     */
    private String processCodeWithAI(String command, String selectedText) {
        try {
            // Create a prompt for the AI
            String prompt = "I have the following code in a JSR223 element in JMeter. " +
                    "Please " + command + ":\n\n```\n" + selectedText + "\n```\n\n" +
                    "Include the improved code wrapped in ```code blocks``` so it can be automatically extracted.";

            // Send the prompt to the AI
            String response = aiService.generateResponse(java.util.Collections.singletonList(prompt));
            if (response == null || response.isEmpty()) {
                log.error("Empty response from AI service");
                return null;
            }

            // Return the full response
            return response;
        } catch (Exception e) {
            log.error("Error processing code with AI", e);
            return null;
        }
    }

    /**
     * Finds the RSyntaxTextArea in the currently selected JSR223 element.
     * 
     * @return The RSyntaxTextArea, or null if not found
     */
    public static RSyntaxTextArea findJSR223ScriptEditor() {
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                return null;
            }

            JMeterTreeNode node = guiPackage.getTreeListener().getCurrentNode();
            if (node == null) {
                return null;
            }

            // Check if this is a JSR223 element
            String className = node.getTestElement().getClass().getName();
            if (!className.contains("JSR223")) {
                return null;
            }

            // Get the GUI component
            JMeterGUIComponent guiComp = guiPackage.getCurrentGui();
            if (!(guiComp instanceof TestBeanGUI)) {
                return null;
            }

            TestBeanGUI testBeanGUI = (TestBeanGUI) guiComp;

            // First attempt: Find the RSyntaxTextArea in the component hierarchy
            RSyntaxTextArea scriptEditor = findRSyntaxTextArea(testBeanGUI);
            if (scriptEditor != null) {
                return scriptEditor;
            }

            // Second attempt: Try to find it in the parent container
            Container parent = testBeanGUI.getParent();
            if (parent != null) {
                scriptEditor = findRSyntaxTextArea(parent);
                if (scriptEditor != null) {
                    return scriptEditor;
                }
            }

            // Third attempt: Try to find it in the main frame
            if (guiPackage.getMainFrame() != null) {
                scriptEditor = findRSyntaxTextArea(guiPackage.getMainFrame());
                if (scriptEditor != null) {
                    return scriptEditor;
                }
            }

            // Final attempt: Search all windows for the RSyntaxTextArea
            scriptEditor = findRSyntaxTextAreaInAllWindows();
            if (scriptEditor != null) {
                return scriptEditor;
            }

            return null;
        } catch (Exception e) {
            log.error("Error finding JSR223 script editor", e);
            return null;
        }
    }

    /**
     * Recursively searches for RSyntaxTextArea in the component hierarchy.
     * 
     * @param container The container to search in
     * @return The RSyntaxTextArea, or null if not found
     */
    public static RSyntaxTextArea findRSyntaxTextArea(Container container) {
        // First, try to find the component by name - often used in JMeter for script
        // areas
        for (Component component : container.getComponents()) {
            String componentName = component.getName();
            if (componentName != null &&
                    (componentName.contains("script") || componentName.contains("Script") ||
                            componentName.contains("code") || componentName.contains("Code"))) {
                if (component instanceof RSyntaxTextArea) {
                    return (RSyntaxTextArea) component;
                }
            }
        }

        // Regular recursive search
        for (Component component : container.getComponents()) {
            if (component instanceof RSyntaxTextArea) {
                return (RSyntaxTextArea) component;
            } else if (component.getClass().getName().contains("JSyntaxTextArea")) {
                // JMeter uses a custom JSyntaxTextArea which extends RSyntaxTextArea
                return (RSyntaxTextArea) component;
            } else if (component instanceof JScrollPane ||
                    component.getClass().getName().contains("JTextScrollPane")) {
                // Special handling for scroll panes, which often contain the text area
                try {
                    // Use reflection to get the viewport and view since JTextScrollPane might not
                    // be in our classpath
                    Container viewport = null;
                    if (component instanceof JScrollPane) {
                        viewport = ((JScrollPane) component).getViewport();
                    } else {
                        // Try to get viewport through reflection
                        java.lang.reflect.Method getViewportMethod = component.getClass().getMethod("getViewport");
                        viewport = (Container) getViewportMethod.invoke(component);
                    }

                    if (viewport != null) {
                        Component viewComponent = viewport.getComponent(0);
                        if (viewComponent instanceof RSyntaxTextArea ||
                                viewComponent.getClass().getName().contains("JSyntaxTextArea")) {
                            return (RSyntaxTextArea) viewComponent;
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error getting viewport from scroll pane", e);
                }
            } else if (component instanceof Container) {
                RSyntaxTextArea result = findRSyntaxTextArea((Container) component);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Searches for RSyntaxTextArea in all open windows.
     * This is a more aggressive approach when the standard search fails.
     * 
     * @return The first RSyntaxTextArea found, or null if none found
     */
    public static RSyntaxTextArea findRSyntaxTextAreaInAllWindows() {

        // Get all windows
        Window[] windows = Window.getWindows();

        // Search each window
        for (Window window : windows) {
            if (window.isVisible()) {
                RSyntaxTextArea textArea = findRSyntaxTextArea(window);
                if (textArea != null) {
                    log.info("Found RSyntaxTextArea in window: {}", window);
                    return textArea;
                }
            }
        }

        log.info("No RSyntaxTextArea found in any window");
        return null;
    }

    /**
     * Extracts code blocks from the AI response.
     * Looks for code wrapped in ```code blocks```.
     * 
     * @param response The AI response
     * @return The extracted code, or null if no code blocks are found
     */
    private String extractCodeFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        // Pattern to match code blocks: ```[optional language identifier]\n[code]\n```
        Pattern pattern = Pattern.compile("```(?:(?!```)[\\s\\S])*?```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(response);

        StringBuilder extractedCode = new StringBuilder();
        boolean foundCode = false;

        while (matcher.find()) {
            foundCode = true;
            String codeBlock = matcher.group();

            // Remove the ``` delimiters and any language identifier
            String code = codeBlock.replaceAll("^```[^\n]*\n", "").replaceAll("\n```$", "");

            if (extractedCode.length() > 0) {
                extractedCode.append("\n\n");
            }
            extractedCode.append(code);
        }

        if (foundCode) {
            return extractedCode.toString();
        } else {
            return null;
        }
    }
}
