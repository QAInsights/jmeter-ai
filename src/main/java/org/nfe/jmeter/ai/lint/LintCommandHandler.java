package org.nfe.jmeter.ai.lint;

import org.nfe.jmeter.ai.service.AiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles the @lint command in the chat panel.
 * This class processes the lint command to rename elements in the test plan.
 */
public class LintCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(LintCommandHandler.class);
    private static final Pattern LINT_COMMAND_PATTERN = Pattern.compile("@lint(?:\\s+(.+))?");
    private final AiService aiService;
    private boolean isProcessing = false;
    
    /**
     * Constructor.
     * 
     * @param aiService The AI service to use for processing
     */
    public LintCommandHandler(AiService aiService) {
        this.aiService = aiService;
    }
    
    /**
     * Processes the @lint command.
     * 
     * @param message The message containing the @lint command
     * @return The result of the lint operation, or an error message
     */
    public String processLintCommand(String message) {
        if (isProcessing) {
            return "I'm already processing a lint command. Please wait.";
        }
        
        try {
            isProcessing = true;
            
            // Log the exact message for debugging
            log.info("Received lint message: '{}'", message);
            
            // Make sure the message starts with @lint
            if (!message.trim().startsWith("@lint")) {
                message = "@lint " + message.trim();
            }
            
            // Extract the command after @lint
            Matcher matcher = LINT_COMMAND_PATTERN.matcher(message.trim());
            if (!matcher.find()) {
                log.error("Failed to match pattern for message: '{}'", message);
                return "Invalid @lint command format. Please use '@lint [command]'.";
            }
            
            // Get the command, defaulting to "rename" if none specified
            String command = matcher.group(1);
            if (command == null || command.trim().isEmpty()) {
                command = "rename";
            }
            log.info("Processing @lint command with action: {}", command);
            
            // Process the lint command
            return processLintAction(command);
        } catch (Exception e) {
            log.error("Error processing @lint command", e);
            return "Error processing @lint command: " + e.getMessage();
        } finally {
            isProcessing = false;
        }
    }
    
    /**
     * Processes the lint action.
     * 
     * @param action The lint action to perform
     * @return The result of the lint action
     */
    private String processLintAction(String action) {
        // Create an ElementRenamer and execute the rename operation with the user's command
        ElementRenamer renamer = new ElementRenamer(aiService);
        return renamer.renameElements(action);
    }
    
    /**
     * Undoes the last rename operation performed by the ElementRenamer.
     * 
     * @return A message indicating the result of the undo operation
     */
    public String undoLastRename() {
        try {
            log.info("Undoing last rename operation");
            return ElementRenamer.undoLastRename();
        } catch (Exception e) {
            log.error("Error undoing last rename operation", e);
            return "Error undoing last rename operation: " + e.getMessage();
        }
    }
    
    /**
     * Redoes the last undone rename operation performed by the ElementRenamer.
     * 
     * @return A message indicating the result of the redo operation
     */
    public String redoLastUndo() {
        try {
            log.info("Redoing last undone rename operation");
            return ElementRenamer.redoLastUndo();
        } catch (Exception e) {
            log.error("Error redoing last undone rename operation", e);
            return "Error redoing last undone rename operation: " + e.getMessage();
        }
    }
}
