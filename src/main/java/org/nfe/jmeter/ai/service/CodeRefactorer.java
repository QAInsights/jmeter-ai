package org.nfe.jmeter.ai.service;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.nfe.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.List;

/**
 * Handles code refactoring operations using AI.
 */
public class CodeRefactorer {
    private static final Logger log = LoggerFactory.getLogger(CodeRefactorer.class);

    // Prompt template for refactoring code
    private static final String REFACTOR_PROMPT = "Refactor the following code following best practices to improve its structure, readability, and maintainability. "
            + "IMPORTANT: Provide ONLY the refactored code without any explanations, introductions, commentary, or markdown formatting. "
            + "DO NOT include backticks, code block markers, or any additional text before or after the code. "
            + "Just return the raw refactored code that can be directly pasted into a code editor.\n\n"
            + "Focus on:\n"
            + "- Proper code organization and separation of concerns\n"
            + "- Meaningful naming conventions\n"
            + "- Reducing code duplication\n"
            + "- Improving error handling\n\n"
            + "CODE TO REFACTOR:";

    private static final String REFACTOR_TRY_CATCH_FINALLY_PROMPT = "Refactor the following code to use try, catch, finally blocks to handle errors. "
            + "IMPORTANT: Provide ONLY the refactored code without any explanations, introductions, commentary, or markdown formatting. "
            + "DO NOT include backticks, code block markers, or any additional text before or after the code. "
            + "Just return the raw refactored code that can be directly pasted into a code editor.\n\n"
            + "Focus on:\n"
            + "- Using try, catch, finally blocks to handle errors\n"
            + "- Meaningful naming conventions\n"
            + "- Improving error handling\n\n"
            + "CODE TO REFACTOR:";

    private final AiService aiService;

    /**
     * Constructs a new CodeRefactorer with the specified AI service.
     *
     * @param aiService The AI service to use for refactoring
     */
    public CodeRefactorer(AiService aiService) {
        this.aiService = aiService;
    }

    /**
     * Refactors the selected code in the given text area.
     *
     * @param textArea The text area containing the code to refactor
     * @return true if refactoring was successful, false otherwise
     */
    public boolean refactorSelectedCode(RSyntaxTextArea textArea) {
        return processRefactoring(textArea, REFACTOR_PROMPT, "Refactor Code");
    }

    /**
     * Refactors the selected code to use try-catch-finally blocks.
     *
     * @param textArea The text area containing the code to refactor
     * @return true if refactoring was successful, false otherwise
     */
    public boolean refactorTryCatchFinally(RSyntaxTextArea textArea) {
        return processRefactoring(textArea, REFACTOR_TRY_CATCH_FINALLY_PROMPT, "Refactor Try, Catch, Finally");
    }

    /**
     * Processes the refactoring operation using the specified prompt and message
     * title.
     *
     * @param textArea       The text area containing the code to refactor
     * @param promptTemplate The prompt template to use for refactoring
     * @param messageTitle   The title to use for dialog messages
     * @return true if refactoring was successful, false otherwise
     */
    private boolean processRefactoring(RSyntaxTextArea textArea, String promptTemplate, String messageTitle) {
        String selectedText = textArea.getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            JOptionPane.showMessageDialog(
                    textArea,
                    "Please select some code first",
                    messageTitle,
                    JOptionPane.INFORMATION_MESSAGE);
            return false;
        }

        try {
            String prompt = promptTemplate + "\n\n" + selectedText;

            // Get the model based on the AI service type
            String aiServiceType = AiConfig.getProperty("jmeter.ai.service.type", "openai");
            String model;

            if ("openai".equalsIgnoreCase(aiServiceType)) {
                model = AiConfig.getProperty("openai.default.model", "gpt-4o");
            } else {
                model = AiConfig.getProperty("anthropic.model", "claude-3-sonnet-20240229");
            }

            String refactoredCode = aiService.generateResponse(List.of(prompt), model);

            // Clean up any remaining markdown or code block markers
            refactoredCode = cleanUpCodeResponse(refactoredCode);

            // Replace only the selected text
            textArea.replaceSelection(refactoredCode);
            return true;
        } catch (Exception ex) {
            log.error("Error during code refactoring", ex);
            JOptionPane.showMessageDialog(
                    textArea,
                    "Error during refactoring: " + ex.getMessage(),
                    "Refactoring Error",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /**
     * Cleans up the AI response to remove markdown formatting and code block
     * markers.
     *
     * @param response The response from the AI service
     * @return The cleaned up code response
     */
    private String cleanUpCodeResponse(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }

        // Remove markdown code block markers
        String cleaned = response;

        // Remove "```groovy" or "```java" or any language identifier at the start
        cleaned = cleaned.replaceAll("^```\\w*\\s*\\n", "");

        // Remove closing "```" at the end
        cleaned = cleaned.replaceAll("```\\s*$", "");

        // Remove any triple backticks anywhere
        cleaned = cleaned.replace("```", "");

        // If the response starts with "Here's the refactored code:" or similar phrases,
        // remove them
        cleaned = cleaned.replaceAll("(?i)^.*?(here'?s\\s+the\\s+refactored\\s+code:?\\s*\\n)", "");

        // Remove explanations or notes that might appear after the code
        cleaned = cleaned.replaceAll("(?i)\\n\\s*\\n.*?(note|explanation|changes|improvements):?.*$", "");

        return cleaned.trim();
    }
}