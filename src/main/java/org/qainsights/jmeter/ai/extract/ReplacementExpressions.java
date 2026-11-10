package org.qainsights.jmeter.ai.extract;

import java.util.BitSet;

public final class ReplacementExpressions {
    private ReplacementExpressions() {
    }

    public static int end(String text, int start) {
        int depth = 1;
        char quote = 0;
        boolean escaped = false;
        for (int i = start + 2; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i + 1;
            }
        }
        return -1;
    }

    public static BitSet protectedCharacters(String text) {
        BitSet protectedCharacters = new BitSet();
        int start = text.indexOf("${");
        while (start >= 0) {
            int end = end(text, start);
            if (end < 0) {
                protectedCharacters.set(start, text.length());
                break;
            }
            protectedCharacters.set(start, end);
            start = text.indexOf("${", end);
        }
        return protectedCharacters;
    }
}
