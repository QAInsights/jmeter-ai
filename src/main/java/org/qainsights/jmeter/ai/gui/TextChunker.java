package org.qainsights.jmeter.ai.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits an already-complete response string into small chunks ("simulated tokens")
 * so it can be replayed progressively into the chat, giving a token-by-token
 * streaming feel for text whose full value can only be known after the fact (e.g.
 * the agent's final answer, which isn't known until the tool-calling loop completes).
 */
final class TextChunker {

    private static final Pattern WORD_AND_TRAILING_WHITESPACE = Pattern.compile("\\S+\\s*");

    private TextChunker() {
    }

    /**
     * @param text the full text to split; {@code null}/empty returns an empty list
     * @return chunks that, concatenated in order, reproduce {@code text} exactly
     *         (aside from any leading whitespace, which is dropped)
     */
    static List<String> chunk(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> chunks = new ArrayList<>();
        Matcher matcher = WORD_AND_TRAILING_WHITESPACE.matcher(text);
        while (matcher.find()) {
            chunks.add(matcher.group());
        }
        return chunks;
    }
}
