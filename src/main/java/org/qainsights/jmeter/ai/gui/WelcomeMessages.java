package org.qainsights.jmeter.ai.gui;

import org.qainsights.jmeter.ai.service.ProviderStatus;
import org.qainsights.jmeter.ai.utils.Constants;

/**
 * Builds the markdown welcome bubble for the chat transcript based on
 * whether any AI provider looks configured. Pure string logic, no Swing.
 */
public final class WelcomeMessages {

    private WelcomeMessages() {
    }

    /** Welcome for the live {@link ProviderStatus#fromConfig()} snapshot. */
    public static String forCurrentConfig() {
        return forStatus(ProviderStatus.fromConfig());
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
                + "1. Copy `jmeter-ai-sample.properties` into your `user.properties` (or `jmeter.properties`)\n"
                + "2. Set at least one key, for example `anthropic.api.key` or `openai.api.key`\n"
                + "3. Restart JMeter and open this panel again\n\n"
                + "**Prefer free/local?** Install [Ollama](https://ollama.com/), pull a chat model, "
                + "set `jmeter.ai.service.type=ollama`, and restart. No cloud key needed.\n\n"
                + "Once a provider is ready, ask about your test plan or type `@` for commands.";
    }
}
