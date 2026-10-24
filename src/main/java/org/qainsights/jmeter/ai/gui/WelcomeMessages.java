package org.qainsights.jmeter.ai.gui;

import java.util.function.BooleanSupplier;
import org.qainsights.jmeter.ai.apple.AppleFoundationHelper;
import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.service.ProviderStatus;
import org.qainsights.jmeter.ai.utils.Constants;

/**
 * Builds the markdown welcome bubble for the chat transcript based on
 * whether any AI provider looks configured. Pure string logic, no Swing; the one-time
 * Agent Mode tip is only marked shown by {@link #onShown(String)} once it is displayed.
 */
public final class WelcomeMessages {

    static final String QUICKSTART_URL =
            "https://github.com/QAInsights/jmeter-ai/blob/main/jmeter-ai-quickstart.properties";

    private WelcomeMessages() {
    }

    /** Welcome for the live {@link ProviderStatus#fromConfig()} snapshot. */
    public static String forCurrentConfig() {
        return forCurrentConfig(new AgentModeNudge());
    }

    /** Welcome for the live configuration with an optional local Ollama detection. */
    public static String forCurrentConfig(boolean ollamaDetected) {
        return forCurrentConfig(new AgentModeNudge(), () -> AppleFoundationHelper.cachedStatus()
                .map(AppleFoundationHelper.Status::isAvailable)
                .orElse(false), ollamaDetected);
    }

    /** Adds the one-time Agent Mode tip when {@code nudge} says it is due. Has no side effects. */
    static String forCurrentConfig(AgentModeNudge nudge) {
        return forCurrentConfig(nudge, () -> AppleFoundationHelper.cachedStatus()
                .map(AppleFoundationHelper.Status::isAvailable)
                .orElse(false));
    }

    static String forCurrentConfig(AgentModeNudge nudge, BooleanSupplier appleAvailable) {
        return forCurrentConfig(nudge, appleAvailable, false);
    }

    static String forCurrentConfig(
            AgentModeNudge nudge, BooleanSupplier appleAvailable, boolean ollamaDetected) {
        ProviderStatus status = ProviderStatus.fromConfig(appleAvailable);
        String welcome = forStatus(status, ollamaDetected);
        if (nudge.shouldShow(status.hasAgentCapableProvider(), JMeterAgent.isEnabled())) {
            return welcome + AgentModeNudge.TIP;
        }
        return welcome;
    }

    /** Call after {@code welcome} is on screen: records the Agent Mode tip as shown if it was included. */
    public static void onShown(String welcome) {
        onShown(welcome, new AgentModeNudge());
    }

    static void onShown(String welcome, AgentModeNudge nudge) {
        if (welcome != null && welcome.contains(AgentModeNudge.TIP)) {
            nudge.markShown();
        }
    }

    /**
     * Ready: keep the full command-oriented welcome.
     * Not ready: short setup CTA (properties + Ollama tip).
     */
    public static String forStatus(ProviderStatus status) {
        return forStatus(status, false);
    }

    static String forStatus(ProviderStatus status, boolean ollamaDetected) {
        if (status != null && status.isReady()) {
            return Constants.WELCOME_MESSAGE;
        }
        return setupWelcome(ollamaDetected);
    }

    static String setupWelcome() {
        return setupWelcome(false);
    }

    public static String setupWelcome(boolean ollamaDetected) {
        return "# Welcome to Feather Wand\n\n"
                + "Your AI assistant for Apache JMeter is installed. No provider is connected yet.\n\n"
                + "Click **Connect a provider** below, pick a provider and paste its key.\n\n"
                + (ollamaDetected
                        ? "Ollama is running on this machine. Click **Connect a provider** to use it, no key needed.\n\n"
                        : "")
                + "**Prefer editing files?** Use the [quick-start file](" + QUICKSTART_URL + ")"
                + ", add a provider key to JMeter's `bin/user.properties`, then restart JMeter.\n\n"
                + (ollamaDetected ? "" : "**Prefer local?** Install [Ollama](https://ollama.com/), pull a chat model, "
                        + "then choose Ollama in Connect a provider. No cloud key needed.\n\n")
                + "Once connected, ask about your test plan or type `@` for commands.";
    }
}
