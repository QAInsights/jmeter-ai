package org.qainsights.jmeter.ai.load;

public enum WorkloadShape {
    LOAD("Load"),
    ENDURANCE("Endurance"),
    STEP("Step"),
    SPIKE("Spike"),
    STRESS("Stress"),
    CUSTOM("Custom");

    private final String displayName;

    WorkloadShape(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public boolean usesOpenModelThreadGroup() {
        return this == STEP || this == SPIKE || this == STRESS || this == CUSTOM;
    }
}
