package org.qainsights.jmeter.ai.service.reasoning;

import java.util.Locale;

import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * The user's current reasoning choices for the chat session: whether extended
 * thinking is switched on, and which effort level to request. One instance is
 * owned by the chat panel and injected into the services (and agent chat
 * models), so a change in the toolbar takes effect on the next request without
 * restarting anything.
 * <p>
 * Defaults come from properties: {@code jmeter.ai.thinking.enabled} (false) and
 * {@code jmeter.ai.thinking.effort} (medium). For Ollama, the pre-existing
 * {@code ollama.thinking.mode}/{@code ollama.thinking.level} properties act as
 * provider-specific defaults until the user touches the toolbar.
 */
public class ReasoningSettings {

    public static final String ENABLED_PROPERTY = "jmeter.ai.thinking.enabled";
    public static final String EFFORT_PROPERTY = "jmeter.ai.thinking.effort";
    public static final String DEFAULT_EFFORT = "medium";

    private boolean thinkingEnabled;
    private String effort;
    /** True once the user has explicitly flipped the toggle (vs. a programmatic default). */
    private boolean thinkingToggled;
    /** True once the user has explicitly picked an effort (vs. a programmatic default). */
    private boolean effortTouched;

    /** Creates settings initialised from the {@code jmeter.ai.thinking.*} properties. */
    public ReasoningSettings() {
        this.thinkingEnabled = Boolean.parseBoolean(
                AiConfig.getProperty(ENABLED_PROPERTY, "false"));
        this.effort = normalizeEffort(AiConfig.getProperty(EFFORT_PROPERTY, DEFAULT_EFFORT));
    }

    /** Creates settings with explicit values (mainly for tests). */
    public ReasoningSettings(boolean thinkingEnabled, String effort) {
        this.thinkingEnabled = thinkingEnabled;
        this.effort = normalizeEffort(effort);
    }

    public boolean isThinkingEnabled() {
        return thinkingEnabled;
    }

    public void setThinkingEnabled(boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
    }

    /** Sets the toggle as a deliberate user action (marks it touched). */
    public void userSetThinkingEnabled(boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
        this.thinkingToggled = true;
    }

    public boolean isThinkingToggled() {
        return thinkingToggled;
    }

    public String getEffort() {
        return effort;
    }

    /** Sets the effort level; null/blank values keep the current level. */
    public void setEffort(String effort) {
        if (effort == null || effort.isBlank()) {
            return;
        }
        this.effort = normalizeEffort(effort);
    }

    /** Sets the effort as a deliberate user action (marks it touched). */
    public void userSetEffort(String effort) {
        setEffort(effort);
        this.effortTouched = true;
    }

    public boolean isEffortTouched() {
        return effortTouched;
    }

    private static String normalizeEffort(String effort) {
        if (effort == null || effort.isBlank()) {
            return DEFAULT_EFFORT;
        }
        return effort.trim().toLowerCase(Locale.ROOT);
    }
}
