package org.qainsights.jmeter.ai.param;

import java.util.BitSet;

final class ParameterJson {
    private ParameterJson() {
    }

    record Input(String text, BitSet expressions) {
        boolean protectedToken(long offset) {
            return expressions.get((int) offset);
        }
    }

    static Input mask(String text) {
        StringBuilder masked = new StringBuilder(text);
        BitSet expressions = new BitSet();
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                int end = org.qainsights.jmeter.ai.extract.ReplacementExpressions.end(text, i);
                if (end < 0) {
                    break;
                }
                if (end - i >= 4) {
                    masked.replace(i, end, "null" + " ".repeat(end - i - 4));
                    expressions.set(i, end);
                    i = end - 1;
                }
            }
        }
        return new Input(masked.toString(), expressions);
    }
}
