package org.qainsights.jmeter.ai.correlation;

public enum ExtractorType {
    REGEX("Regex"),
    JSON_PATH("JSONPath"),
    BOUNDARY("Boundary");

    private final String displayName;

    ExtractorType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
