package org.qainsights.jmeter.ai.intellisense;

import org.junit.jupiter.api.Test;
import org.nfe.jmeter.ai.intellisense.CommandIntellisenseProvider;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CommandIntellisenseProvider
 */
public class CommandIntellisenseProviderTest {

    @Test
    public void testGetSuggestionsWithExactMatch() {
        CommandIntellisenseProvider provider = new CommandIntellisenseProvider();
        List<String> suggestions = provider.getSuggestions("@code");
        
        assertEquals(1, suggestions.size());
        assertEquals("@code", suggestions.get(0));
    }
    
    @Test
    public void testGetSuggestionsWithPartialMatch() {
        CommandIntellisenseProvider provider = new CommandIntellisenseProvider();
        List<String> suggestions = provider.getSuggestions("@c");
        
        assertTrue(suggestions.contains("@code"));
        // Should not contain commands that don't start with @c
        assertFalse(suggestions.contains("@wrap"));
    }
    
    @Test
    public void testGetSuggestionsWithNoMatch() {
        CommandIntellisenseProvider provider = new CommandIntellisenseProvider();
        List<String> suggestions = provider.getSuggestions("@xyz");
        
        assertTrue(suggestions.isEmpty());
    }
    
    @Test
    public void testGetSuggestionsWithAtSymbolOnly() {
        CommandIntellisenseProvider provider = new CommandIntellisenseProvider();
        List<String> suggestions = provider.getSuggestions("@");
        
        // Should return all available commands
        assertTrue(suggestions.contains("@code"));
        assertTrue(suggestions.contains("@wrap"));
        assertTrue(suggestions.contains("@lint"));
        assertTrue(suggestions.contains("@usage"));
        assertTrue(suggestions.contains("@optimize"));
        assertTrue(suggestions.contains("@this"));
    }
}
