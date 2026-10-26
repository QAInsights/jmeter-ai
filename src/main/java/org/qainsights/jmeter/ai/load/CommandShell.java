package org.qainsights.jmeter.ai.load;

/** Shell syntax used for generated JMeter commands. */
public enum CommandShell {
    BASH("Bash (macOS/Linux)", "bash"),
    CMD("Windows cmd", "bat"),
    POWERSHELL("PowerShell", "powershell");

    private final String displayName;
    private final String languageTag;

    CommandShell(String displayName, String languageTag) {
        this.displayName = displayName;
        this.languageTag = languageTag;
    }

    public String displayName() {
        return displayName;
    }

    public String languageTag() {
        return languageTag;
    }

    public static CommandShell forOsName(String osName) {
        return osName != null && osName.startsWith("Windows") ? CMD : BASH;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
