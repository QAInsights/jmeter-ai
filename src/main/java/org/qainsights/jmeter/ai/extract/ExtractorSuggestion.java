package org.qainsights.jmeter.ai.extract;

import org.apache.jmeter.extractor.BoundaryExtractor;
import org.apache.jmeter.extractor.HtmlExtractor;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.testelement.AbstractScopedTestElement;
import org.apache.jmeter.testelement.TestElement;

/** One extractor that was checked against a real response and returns exactly the selected value. */
public final class ExtractorSuggestion {

    public enum Type {
        JSON("JSON Extractor"),
        CSS("CSS Selector Extractor"),
        REGEX("Regular Expression Extractor"),
        BOUNDARY("Boundary Extractor");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Type type;
    private final boolean headers;
    private final String expression;
    private final String attribute;
    private final String rightBoundary;
    private final String variableName;
    private int matchNumber = 1;
    private boolean scopeAll;

    ExtractorSuggestion(Type type, boolean headers, String expression, String attribute,
            String rightBoundary, String variableName) {
        this.type = type;
        this.headers = headers;
        this.expression = expression;
        this.attribute = attribute;
        this.rightBoundary = rightBoundary;
        this.variableName = variableName;
    }

    static ExtractorSuggestion json(String path, String var) {
        return new ExtractorSuggestion(Type.JSON, false, path, null, null, var);
    }

    static ExtractorSuggestion css(String selector, String attribute, String var) {
        return new ExtractorSuggestion(Type.CSS, false, selector, attribute, null, var);
    }

    static ExtractorSuggestion regex(boolean headers, String regex, String var) {
        return new ExtractorSuggestion(Type.REGEX, headers, regex, null, null, var);
    }

    static ExtractorSuggestion boundary(boolean headers, String left, String right, String var) {
        return new ExtractorSuggestion(Type.BOUNDARY, headers, left, null, right, var);
    }

    public Type type() {
        return type;
    }

    public boolean usesHeaders() {
        return headers;
    }

    public String expression() {
        return expression;
    }

    public String attribute() {
        return attribute;
    }

    public String rightBoundary() {
        return rightBoundary;
    }

    public String variableName() {
        return variableName;
    }

    public int matchNumber() {
        return matchNumber;
    }

    void setMatchNumber(int matchNumber) {
        this.matchNumber = matchNumber;
    }

    void setScopeAll(boolean scopeAll) {
        this.scopeAll = scopeAll;
    }

    /** Short human summary, e.g. {@code $.data.token} or {@code input[name="csrf"] @value}. */
    public String describe() {
        String where = headers ? " (headers)" : "";
        String match = matchNumber > 1 ? ", match " + matchNumber : "";
        switch (type) {
            case CSS:
                return expression + (attribute == null || attribute.isEmpty() ? " (text)" : " @" + attribute) + match;
            case BOUNDARY:
                return "between " + visible(expression) + " and " + visible(rightBoundary) + where + match;
            default:
                return expression + where + match;
        }
    }

    private static String visible(String s) {
        return "\"" + s.replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    /** A JMeter extractor element configured for this suggestion, ready to add to the tree. */
    public TestElement toElement(String refName) {
        String name = refName == null || refName.isBlank() ? variableName : refName.trim();
        String fallback = name + "_NOT_FOUND";
        AbstractScopedTestElement el;
        String guiClass;
        switch (type) {
            case JSON: {
                JSONPostProcessor e = new JSONPostProcessor();
                e.setRefNames(name);
                e.setJsonPathExpressions(expression);
                e.setMatchNumbers(String.valueOf(matchNumber));
                e.setDefaultValues(fallback);
                el = e;
                guiClass = "org.apache.jmeter.extractor.json.jsonpath.gui.JSONPostProcessorGui";
                break;
            }
            case CSS: {
                HtmlExtractor e = new HtmlExtractor();
                e.setRefName(name);
                e.setExpression(expression);
                e.setAttribute(attribute == null ? "" : attribute);
                e.setExtractor(HtmlExtractor.EXTRACTOR_JSOUP);
                e.setMatchNumber(matchNumber);
                e.setDefaultValue(fallback);
                el = e;
                guiClass = "org.apache.jmeter.extractor.gui.HtmlExtractorGui";
                break;
            }
            case BOUNDARY: {
                BoundaryExtractor e = new BoundaryExtractor();
                e.setRefName(name);
                e.setLeftBoundary(expression);
                e.setRightBoundary(rightBoundary);
                e.setMatchNumber(matchNumber);
                e.setDefaultValue(fallback);
                e.setUseField(headers ? RegexExtractor.USE_HDRS : RegexExtractor.USE_BODY);
                el = e;
                guiClass = "org.apache.jmeter.extractor.gui.BoundaryExtractorGui";
                break;
            }
            default: {
                RegexExtractor e = new RegexExtractor();
                e.setRefName(name);
                e.setRegex(expression);
                e.setTemplate("$1$");
                e.setMatchNumber(matchNumber);
                e.setDefaultValue(fallback);
                e.setUseField(headers ? RegexExtractor.USE_HDRS : RegexExtractor.USE_BODY);
                el = e;
                guiClass = "org.apache.jmeter.extractor.gui.RegexExtractorGui";
                break;
            }
        }
        if (scopeAll) {
            el.setScopeAll();
        }
        el.setName(type.label() + " - " + name);
        el.setProperty(TestElement.TEST_CLASS, el.getClass().getName());
        el.setProperty(TestElement.GUI_CLASS, guiClass);
        return el;
    }
}
