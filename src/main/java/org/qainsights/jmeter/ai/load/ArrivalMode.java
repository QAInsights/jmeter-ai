package org.qainsights.jmeter.ai.load;

public enum ArrivalMode {
    RANDOM("Random (Poisson)", "random_arrivals"),
    EVEN("Even", "even_arrivals");

    private final String displayName;
    private final String scheduleName;

    ArrivalMode(String displayName, String scheduleName) {
        this.displayName = displayName;
        this.scheduleName = scheduleName;
    }

    public String scheduleName() {
        return scheduleName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
