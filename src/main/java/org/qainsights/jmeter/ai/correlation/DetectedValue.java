package org.qainsights.jmeter.ai.correlation;

import java.util.Objects;

final class DetectedValue {
    private final String value;
    private final String location;
    private final String tokenName;

    DetectedValue(String value, String location, String tokenName) {
        this.value = Objects.requireNonNullElse(value, "");
        this.location = Objects.requireNonNullElse(location, "");
        this.tokenName = Objects.requireNonNullElse(tokenName, "");
    }

    String getValue() {
        return value;
    }

    String getLocation() {
        return location;
    }

    String getTokenName() {
        return tokenName;
    }
}
