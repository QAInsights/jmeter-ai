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

    /** Adds the one-time Agent Mode tip when {@code nudge} says it is due. Has no side effects. */
    static String forCurrentConfig(AgentModeNudge nudge) {
        return forCurrentConfig(nudge, () -> AppleFoundationHelper.cachedStatus()
                .map(AppleFoundationHelper.Status::isAvailable)
                .orElse(false));
    }

    static String forCurrentConfig(AgentModeNudge nudge, BooleanSupplier appleAvailable) {
        ProviderStatus status = ProviderStatus.fromConfig(appleAvailable);
        String welcome = forStatus(status);
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
        if (status != null && status.isReady()) {
            return Constants.WELCOME_MESSAGE;
        }
        return setupWelcome();
    }

    static String setupWelcome() {
        return "# Welcome to Feather Wand\n\n"
                + "Your AI assistant for Apache JMeter is installed, but **no API key is configured yet**.\n\n"
                + "**To get started:**\n"
                + "1. Paste the quick-start settings from " + QUICKSTART_URL + " into JMeter's `bin/user.properties`\n"
                + "2. Uncomment one provider and add its key, for example `anthropic.api.key` (Ollama needs no key)\n"
                + "3. Restart JMeter and open this panel again\n\n"
                + "**Prefer free/local?** Install [Ollama](https://ollama.com/), pull a chat model, "
                + "set `jmeter.ai.service.type=ollama`, and restart. No cloud key needed.\n\n"
                + "Once a provider is ready, ask about your test plan or type `@` for commands.";
    }
}
