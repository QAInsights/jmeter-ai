package org.qainsights.jmeter.ai.correlation;

import java.util.Objects;

public final class ExtractorSuggestion {
    private ExtractorType extractorType;
    private String expression;
    private String variableName;
    private String matchNo;

    public ExtractorSuggestion(ExtractorType extractorType, String expression, String variableName, String matchNo) {
        this.extractorType = Objects.requireNonNull(extractorType, "extractorType");
        this.expression = Objects.requireNonNullElse(expression, "");
        this.variableName = Objects.requireNonNullElse(variableName, "");
        this.matchNo = Objects.requireNonNullElse(matchNo, "1");
    }

    public ExtractorType getExtractorType() {
        return extractorType;
    }

    public void setExtractorType(ExtractorType extractorType) {
        this.extractorType = Objects.requireNonNull(extractorType, "extractorType");
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = Objects.requireNonNullElse(expression, "");
    }

    public String getVariableName() {
        return variableName;
    }

    public void setVariableName(String variableName) {
        this.variableName = Objects.requireNonNullElse(variableName, "");
    }

    public String getMatchNo() {
        return matchNo;
    }

    public void setMatchNo(String matchNo) {
        this.matchNo = Objects.requireNonNullElse(matchNo, "1");
    }
}
