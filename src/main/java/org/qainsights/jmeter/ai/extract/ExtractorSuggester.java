package org.qainsights.jmeter.ai.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds extractors for a value the user highlighted in a response, then keeps only the ones
 * that JMeter's own extractor code proves return exactly that value.
 */
public final class ExtractorSuggester {

    static final int MAX_MATCH_NUMBER = 25;
    private static final int MAX_LEFT = 24;
    private static final int MAX_PER_KIND = 3;
    private static final int MAX_VALUE_LENGTH = 4000;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern CSS_IDENT = Pattern.compile("-?[A-Za-z_][A-Za-z0-9_-]*");
    private static final Pattern NAME_ATTR = Pattern.compile("name\\s*=\\s*[\"']([\\w.\\-\\[\\]]+)[\"'][^<>]*$");
    private static final Pattern LAST_WORD = Pattern.compile("([A-Za-z_][\\w.\\-]*)[^A-Za-z0-9_]*$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private ExtractorSuggester() {
    }

    /** The highlighted text as the value to extract: trimmed, without one pair of wrapping quotes. */
    public static String normalize(String selection) {
        if (selection == null) {
            return "";
        }
        String v = selection.trim();
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'') && v.charAt(v.length() - 1) == v.charAt(0)) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    /**
     * @param top      the top-level result the plan's sampler produces (what the extractor runs on)
     * @param selected the result shown when the user highlighted the value (may be a sub-result)
     * @param value    the value to extract, already {@link #normalize normalized}
     */
    public static List<ExtractorSuggestion> suggest(SampleResult top, SampleResult selected, String value) {
        List<ExtractorSuggestion> verified = new ArrayList<>();
        if (top == null || selected == null || value == null || value.isEmpty()
                || value.length() > MAX_VALUE_LENGTH || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return verified;
        }
        boolean scopeAll = selected != top;
        Map<String, ExtractorSuggestion> seen = new LinkedHashMap<>();
        for (ExtractorSuggestion s : candidates(selected, value)) {
            s.setScopeAll(scopeAll);
            if (verify(s, top, value)) {
                seen.putIfAbsent(s.type() + "|" + s.describe(), s);
            }
        }
        verified.addAll(seen.values());
        return verified;
    }

    static List<ExtractorSuggestion> candidates(SampleResult result, String value) {
        List<ExtractorSuggestion> out = new ArrayList<>();
        String body = result.getResponseDataAsString();
        String headers = result.getResponseHeaders();
        if (body != null && body.contains(value)) {
            String trimmed = body.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                out.addAll(jsonCandidates(trimmed, value));
            }
            if (trimmed.startsWith("<")) {
                out.addAll(cssCandidates(body, value));
            }
            addTextCandidates(out, body, value, false);
        }
        if (headers != null && headers.contains(value)) {
            addTextCandidates(out, headers, value, true);
        }
        return out;
    }

    // ---------------------------------------------------------------- JSON

    static List<ExtractorSuggestion> jsonCandidates(String body, String value) {
        List<ExtractorSuggestion> out = new ArrayList<>();
        try {
            walkJson(JSON.readTree(body), "$", "value", value, out);
        } catch (Exception e) {
            // not JSON after all
        }
        return out;
    }

    private static void walkJson(JsonNode node, String path, String key, String value, List<ExtractorSuggestion> out) {
        if (out.size() >= MAX_PER_KIND || node == null) {
            return;
        }
        if (node.isValueNode()) {
            if (value.equals(node.asText())) {
                out.add(ExtractorSuggestion.json(path, variableName(key)));
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walkJson(node.get(i), path + "[" + i + "]", key, value, out);
            }
        } else if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey();
                String segment = IDENTIFIER.matcher(k).matches()
                        ? "." + k
                        : "['" + k.replace("\\", "\\\\").replace("'", "\\'") + "']";
                walkJson(e.getValue(), path + segment, k, value, out);
            }
        }
    }

    // ---------------------------------------------------------------- HTML

    static List<ExtractorSuggestion> cssCandidates(String body, String value) {
        List<ExtractorSuggestion> out = new ArrayList<>();
        Document doc;
        try {
            doc = Jsoup.parse(body);
        } catch (Exception e) {
            return out;
        }
        for (Element el : doc.getAllElements()) {
            if (out.size() >= MAX_PER_KIND) {
                break;
            }
            for (Attribute a : el.attributes()) {
                if (value.equals(a.getValue())) {
                    String selector = selectorFor(el, a.getKey());
                    if (selector != null) {
                        out.add(ExtractorSuggestion.css(selector, a.getKey(), cssVariableName(el, a.getKey())));
                    }
                }
            }
            if (value.equals(el.ownText().trim())) {
                String selector = selectorFor(el, null);
                if (selector != null) {
                    out.add(ExtractorSuggestion.css(selector, "", cssVariableName(el, null)));
                }
            }
        }
        return out;
    }

    private static String selectorFor(Element el, String valueAttr) {
        String tag = el.tagName();
        if (!"name".equals(valueAttr) && !el.attr("name").isEmpty()) {
            return tag + "[name=\"" + cssString(el.attr("name")) + "\"]";
        }
        if (!"id".equals(valueAttr) && !el.id().isEmpty()) {
            return CSS_IDENT.matcher(el.id()).matches() ? tag + "#" + el.id() : tag + "[id=\"" + cssString(el.id()) + "\"]";
        }
        for (String key : new String[] {"property", "itemprop", "data-name", "data-key"}) {
            if (!key.equals(valueAttr) && !el.attr(key).isEmpty()) {
                return tag + "[" + key + "=\"" + cssString(el.attr(key)) + "\"]";
            }
        }
        return null;
    }

    private static String cssString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String cssVariableName(Element el, String valueAttr) {
        for (String key : new String[] {"name", "id", "property", "itemprop", "data-name", "data-key"}) {
            if (!key.equals(valueAttr) && !el.attr(key).isEmpty()) {
                return variableName(el.attr(key));
            }
        }
        return variableName(valueAttr == null ? el.tagName() : valueAttr);
    }

    // ---------------------------------------------------------------- Regex and Boundary

    private static void addTextCandidates(List<ExtractorSuggestion> out, String text, String value, boolean headers) {
        int idx = text.indexOf(value);
        int lineStart = Math.max(text.lastIndexOf('\n', idx - 1), text.lastIndexOf('\r', idx - 1)) + 1;
        String before = text.substring(Math.max(lineStart, idx - MAX_LEFT), idx);
        if (before.isEmpty()) {
            return;
        }
        int end = idx + value.length();
        boolean atLineEnd = end >= text.length() || text.charAt(end) == '\n' || text.charAt(end) == '\r';
        String rightChar = atLineEnd ? null : String.valueOf(text.charAt(end));
        String group = atLineEnd ? "([^\\r\\n]+)" : "(.+?)";

        String left = wholeWords(before, shortestLeft(text, value, before, group, rightChar));
        String regex = perl5Escape(left) + group + (rightChar == null ? "" : perl5Escape(rightChar));
        String var = textVariableName(before);
        out.add(ExtractorSuggestion.regex(headers, regex, var));

        String right = rightChar;
        if (right == null && end < text.length()) {
            right = text.startsWith("\r\n", end) ? "\r\n" : String.valueOf(text.charAt(end));
        }
        if (right != null) {
            out.add(ExtractorSuggestion.boundary(headers, left, right, var));
        }
    }

    /** The shortest left context whose first match is the value, so the regex stays readable. */
    private static String shortestLeft(String text, String value, String before, String group, String rightChar) {
        for (int len = Math.min(3, before.length()); len <= before.length(); len++) {
            String left = before.substring(before.length() - len);
            Pattern p = Pattern.compile(Pattern.quote(left) + group + (rightChar == null ? "" : Pattern.quote(rightChar)));
            Matcher m = p.matcher(text);
            if (m.find() && value.equals(m.group(1))) {
                return left;
            }
        }
        return before;
    }

    /** Grows {@code left} back to a word start so it reads as {@code SESSION=}, not {@code ON=}. */
    private static String wholeWords(String before, String left) {
        int start = before.length() - left.length();
        while (start > 0 && Character.isLetterOrDigit(before.charAt(start)) && isWordChar(before.charAt(start - 1))) {
            start--;
        }
        return before.substring(start);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }

    static String perl5Escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String textVariableName(String before) {
        Matcher name = NAME_ATTR.matcher(before);
        if (name.find()) {
            return variableName(name.group(1));
        }
        Matcher word = LAST_WORD.matcher(before);
        return variableName(word.find() ? word.group(1) : "value");
    }

    static String variableName(String raw) {
        String v = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (v.isEmpty()) {
            return "value";
        }
        return Character.isDigit(v.charAt(0)) ? "v_" + v : v;
    }

    // ---------------------------------------------------------------- verification

    /** Runs the real extractor on {@code result}, trying match numbers until it returns {@code value}. */
    static boolean verify(ExtractorSuggestion s, SampleResult result, String value) {
        for (int n = 1; n <= MAX_MATCH_NUMBER; n++) {
            s.setMatchNumber(n);
            String got = run(s.toElement(s.variableName()), result, s.variableName());
            if (value.equals(got)) {
                return true;
            }
            if (got == null || got.equals(s.variableName() + "_NOT_FOUND")) {
                return false;
            }
        }
        return false;
    }

    static String run(TestElement extractor, SampleResult result, String refName) {
        JMeterContext ctx = JMeterContextService.getContext();
        SampleResult previous = ctx.getPreviousResult();
        JMeterVariables previousVars = ctx.getVariables();
        try {
            JMeterVariables vars = new JMeterVariables();
            ctx.setVariables(vars);
            ctx.setPreviousResult(result);
            ((AbstractTestElement) extractor).setThreadContext(ctx);
            ((PostProcessor) extractor).process();
            return vars.get(refName);
        } catch (RuntimeException | LinkageError e) {
            return null;
        } finally {
            ctx.setVariables(previousVars);
            ctx.setPreviousResult(previous);
        }
    }
}
