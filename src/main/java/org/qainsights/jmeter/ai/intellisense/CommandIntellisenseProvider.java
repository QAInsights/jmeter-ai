package org.qainsights.jmeter.ai.intellisense;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides intellisense/autocomplete for AI chat commands (e.g., @code, @wrap).
 */
public class CommandIntellisenseProvider {
    private final List<String> commands;

    /** One-line descriptions shown under each command in the suggestion popup. */
    private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();
    static {
        DESCRIPTIONS.put("@code", "Disabled - use the JSR223 editor context menu");
        DESCRIPTIONS.put("@wrap", "Group HTTP samplers under Transaction Controllers");
        DESCRIPTIONS.put("@lint", "Rename elements with meaningful names");
        DESCRIPTIONS.put("@usage", "View AI usage statistics");
        DESCRIPTIONS.put("@optimize", "Get optimization suggestions for the plan");
        DESCRIPTIONS.put("@this", "Inspect the currently selected element");
        DESCRIPTIONS.put("@testplan", "Ask questions about the whole test plan");
    }

    public CommandIntellisenseProvider() {
        commands = new ArrayList<>();
        commands.add("@code");
        commands.add("@wrap");
        commands.add("@lint");
        commands.add("@usage");
        commands.add("@optimize");
        commands.add("@this");
        commands.add("@testplan");

        // Add more commands here as needed
    }

    public List<String> getSuggestions(String prefix) {
        List<String> suggestions = new ArrayList<>();
        for (String cmd : commands) {
            if (cmd.startsWith(prefix)) {
                suggestions.add(cmd);
            }
        }
        return suggestions;
    }

    /**
     * Returns the one-line description for a command, or an empty string if
     * the command is unknown (e.g. non-command suggestions).
     */
    public static String getDescription(String command) {
        return DESCRIPTIONS.getOrDefault(command, "");
    }
}
