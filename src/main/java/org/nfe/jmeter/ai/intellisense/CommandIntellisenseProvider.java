package org.nfe.jmeter.ai.intellisense;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides intellisense/autocomplete for AI chat commands (e.g., @code, @wrap).
 */
public class CommandIntellisenseProvider {
    private final List<String> commands;

    public CommandIntellisenseProvider() {
        commands = new ArrayList<>();
        commands.add("@code");
        commands.add("@wrap");
        commands.add("@lint");
        commands.add("@usage");
        commands.add("@optimize");
        commands.add("@this");

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
}
