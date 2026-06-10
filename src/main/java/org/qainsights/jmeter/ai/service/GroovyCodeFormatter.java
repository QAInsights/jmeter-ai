package org.qainsights.jmeter.ai.service;

/**
 * Groovy-aware code formatter for JSR223 scripts.
 * <p>
 * Applies consistent indentation (4 spaces per level) while correctly handling
 * string literals, GStrings, comments, and Groovydoc to avoid misinterpreting
 * braces inside strings or comments as block delimiters.
 */
public class GroovyCodeFormatter {

    private static final String INDENT = "    ";

    public String format(String code) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        if (code.trim().isEmpty()) {
            return code;
        }

        String[] lines = code.split("\n", -1);
        StringBuilder result = new StringBuilder();
        int indentLevel = 0;
        Context ctx = new Context();

        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append('\n');
            }

            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            // Inside multi-line string or comment: preserve content as-is
            if (ctx.inTripleDQ || ctx.inTripleSQ || ctx.inMultiLineComment) {
                ContinuationResult cont = scanContinuation(line, ctx);
                ctx = cont.ctx;
                result.append(line);

                // If the multi-line context closed on this line,
                // scan the remainder for braces
                if (!ctx.isMultiLine() && cont.closePos >= 0) {
                    String remainder = line.substring(cont.closePos).trim();
                    if (!remainder.isEmpty()) {
                        LineResult lr = scanLine(remainder, ctx);
                        ctx = lr.ctx;
                        indentLevel = computeNextIndent(indentLevel, lr, remainder, false);
                    }
                }
                continue;
            }

            boolean startsWithClose = isClosing(trimmed.charAt(0));
            if (startsWithClose) {
                indentLevel = Math.max(0, indentLevel - 1);
            }

            LineResult lr = scanLine(trimmed, ctx);
            ctx = lr.ctx;

            appendIndented(result, trimmed, indentLevel);
            indentLevel = computeNextIndent(indentLevel, lr, trimmed, startsWithClose);
        }

        return result.toString();
    }

    private int computeNextIndent(int indentLevel, LineResult lr, String trimmed,
                                  boolean startsWithClose) {
        if (lr.ctx.isMultiLine()) {
            return indentLevel;
        }
        if (lr.netBraces > 0) {
            return indentLevel + lr.netBraces;
        }
        if (startsWithClose && trimmed.length() > 1 && isOpening(trimmed.charAt(trimmed.length() - 1))) {
            return indentLevel + 1;
        }
        if (!startsWithClose && lr.netBraces < 0) {
            return Math.max(0, indentLevel + lr.netBraces);
        }
        return indentLevel;
    }

    private boolean isClosing(char c) {
        return c == '}' || c == ']' || c == ')';
    }

    private boolean isOpening(char c) {
        return c == '{' || c == '[' || c == '(';
    }

    private void appendIndented(StringBuilder sb, String trimmed, int level) {
        for (int i = 0; i < level; i++) {
            sb.append(INDENT);
        }
        sb.append(trimmed);
    }

    private ContinuationResult scanContinuation(String line, Context ctx) {
        if (ctx.inTripleDQ) {
            return processTripleDQWithPos(line, 0);
        }
        if (ctx.inTripleSQ) {
            return processTripleSQWithPos(line, 0);
        }
        return processMLCommentWithPos(line, 0);
    }

    private LineResult scanLine(String line, Context initial) {
        Context ctx = initial.copy();
        int netBraces = 0;
        int i = 0;
        int len = line.length();

        while (i < len) {
            char c = line.charAt(i);

            // Multi-line comment start
            if (!ctx.inAny() && c == '/' && i + 1 < len && line.charAt(i + 1) == '*') {
                int end = line.indexOf("*/", i + 2);
                if (end >= 0) {
                    i = end + 2;
                    continue;
                }
                ctx.inMultiLineComment = true;
                return new LineResult(netBraces, ctx);
            }

            // Single-line comment
            if (!ctx.inAny() && c == '/' && i + 1 < len && line.charAt(i + 1) == '/') {
                return new LineResult(netBraces, ctx);
            }

            // Triple double-quoted string
            if (!ctx.inAny() && c == '"' && i + 2 < len && line.charAt(i + 1) == '"'
                    && line.charAt(i + 2) == '"') {
                ContinuationResult tr = processTripleDQWithPos(line, i + 3);
                ctx.inTripleDQ = tr.ctx.inTripleDQ;
                if (!tr.ctx.inTripleDQ && tr.closePos >= 0) {
                    i = tr.closePos;
                    continue;
                }
                return new LineResult(netBraces, ctx);
            }

            // Triple single-quoted string
            if (!ctx.inAny() && c == '\'' && i + 2 < len && line.charAt(i + 1) == '\''
                    && line.charAt(i + 2) == '\'') {
                ContinuationResult tr = processTripleSQWithPos(line, i + 3);
                ctx.inTripleSQ = tr.ctx.inTripleSQ;
                if (!tr.ctx.inTripleSQ && tr.closePos >= 0) {
                    i = tr.closePos;
                    continue;
                }
                return new LineResult(netBraces, ctx);
            }

            // Double-quoted string (GString)
            if (!ctx.inAny() && c == '"') {
                i++;
                while (i < len) {
                    char sc = line.charAt(i);
                    if (sc == '\\' && i + 1 < len) {
                        i += 2;
                        continue;
                    }
                    if (sc == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Single-quoted string
            if (!ctx.inAny() && c == '\'') {
                i++;
                while (i < len) {
                    char sc = line.charAt(i);
                    if (sc == '\\' && i + 1 < len) {
                        i += 2;
                        continue;
                    }
                    if (sc == '\'') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Slashy string (/.../)
            if (!ctx.inAny() && c == '/' && i + 1 < len && line.charAt(i + 1) != '/'
                    && line.charAt(i + 1) != '*' && !isDivisionContext(line, i)) {
                i++;
                while (i < len) {
                    char sc = line.charAt(i);
                    if (sc == '\\' && i + 1 < len) {
                        i += 2;
                        continue;
                    }
                    if (sc == '/') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Brace/bracket tracking (only outside strings and comments)
            if (!ctx.inAny()) {
                if (isOpening(c)) {
                    netBraces++;
                } else if (isClosing(c)) {
                    netBraces--;
                }
            }

            i++;
        }

        return new LineResult(netBraces, ctx);
    }

    private boolean isDivisionContext(String line, int pos) {
        for (int j = pos - 1; j >= 0; j--) {
            char ch = line.charAt(j);
            if (ch == ' ' || ch == '\t') {
                continue;
            }
            return Character.isLetterOrDigit(ch) || ch == ')' || ch == ']' || ch == '_';
        }
        return false;
    }

    private ContinuationResult processTripleDQWithPos(String line, int start) {
        int i = start;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                i += 2;
                continue;
            }
            if (c == '"' && i + 2 < line.length() && line.charAt(i + 1) == '"'
                    && line.charAt(i + 2) == '"') {
                return new ContinuationResult(new Context(), i + 3);
            }
            i++;
        }
        Context ctx = new Context();
        ctx.inTripleDQ = true;
        return new ContinuationResult(ctx, -1);
    }

    private ContinuationResult processTripleSQWithPos(String line, int start) {
        int i = start;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                i += 2;
                continue;
            }
            if (c == '\'' && i + 2 < line.length() && line.charAt(i + 1) == '\''
                    && line.charAt(i + 2) == '\'') {
                return new ContinuationResult(new Context(), i + 3);
            }
            i++;
        }
        Context ctx = new Context();
        ctx.inTripleSQ = true;
        return new ContinuationResult(ctx, -1);
    }

    private ContinuationResult processMLCommentWithPos(String line, int start) {
        int end = line.indexOf("*/", start);
        if (end >= 0) {
            return new ContinuationResult(new Context(), end + 2);
        }
        Context ctx = new Context();
        ctx.inMultiLineComment = true;
        return new ContinuationResult(ctx, -1);
    }

    static class Context {
        boolean inTripleDQ;
        boolean inTripleSQ;
        boolean inMultiLineComment;

        boolean isMultiLine() {
            return inTripleDQ || inTripleSQ || inMultiLineComment;
        }

        boolean inAny() {
            return inTripleDQ || inTripleSQ || inMultiLineComment;
        }

        Context copy() {
            Context c = new Context();
            c.inTripleDQ = this.inTripleDQ;
            c.inTripleSQ = this.inTripleSQ;
            c.inMultiLineComment = this.inMultiLineComment;
            return c;
        }
    }

    static class LineResult {
        final int netBraces;
        final Context ctx;

        LineResult(int netBraces, Context ctx) {
            this.netBraces = netBraces;
            this.ctx = ctx;
        }
    }

    static class ContinuationResult {
        final Context ctx;
        final int closePos;

        ContinuationResult(Context ctx, int closePos) {
            this.ctx = ctx;
            this.closePos = closePos;
        }
    }
}
