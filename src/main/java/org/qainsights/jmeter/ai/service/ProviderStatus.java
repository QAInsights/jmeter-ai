package org.qainsights.jmeter.ai.service;

import java.util.function.BooleanSupplier;
import org.qainsights.jmeter.ai.agent.AgentJudgmentConfig;
import org.qainsights.jmeter.ai.apple.AppleFoundationHelper;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.GatewayConfig;

/**
 * Read-only snapshot of which AI providers look configured from JMeter
 * properties. No network calls: Ollama is treated as "available to try"
 * without probing, and Apple availability comes only from the helper's cached
 * status. This class never starts the helper process.
 */
public final class ProviderStatus {

    private final boolean anyCloudReady;
    private final boolean ollamaPreferred;
    private final boolean agentCapableReady;
    private final boolean appleAvailable;

    private ProviderStatus(boolean anyCloudReady, boolean ollamaPreferred,
                           boolean agentCapableReady, boolean appleAvailable) {
        this.anyCloudReady = anyCloudReady;
        this.ollamaPreferred = ollamaPreferred;
        this.agentCapableReady = agentCapableReady;
        this.appleAvailable = appleAvailable;
    }

    /** Loads status from the current {@link AiConfig} / JMeter properties. */
    public static ProviderStatus fromConfig() {
        return fromConfig(() -> AppleFoundationHelper.cachedStatus()
                .map(AppleFoundationHelper.Status::isAvailable)
                .orElse(false));
    }

    /** Loads status using the supplied Apple on-device availability value. */
    public static ProviderStatus fromConfig(BooleanSupplier appleOnDeviceAvailable) {
        boolean agentCapable = isApiKeyConfigured("anthropic.api.key")
                || isApiKeyConfigured("openai.api.key")
                || isApiKeyConfigured("google.api.key")
                || isApiKeyConfigured("deepseek.api.key")
                || isApiKeyConfigured("grok.api.key")
                || isApiKeyConfigured(AgentJudgmentConfig.OPENROUTER_API_KEY)
                || isApiKeyConfigured("meta.api.key")
                || GatewayConfig.hasOpenAiGatewayCredentials()
                || GatewayConfig.hasAnthropicGatewayCredentials();
        boolean cloud = agentCapable || isBedrockConfigured();
        String serviceType = AiConfig.getProperty("jmeter.ai.service.type", "");
        boolean ollamaPreferred = "ollama".equalsIgnoreCase(serviceType == null ? "" : serviceType.trim());
        boolean apple = Boolean.parseBoolean(AiConfig.getProperty("apple.enabled", "true"))
                && appleOnDeviceAvailable.getAsBoolean();
        return new ProviderStatus(cloud, ollamaPreferred, agentCapable, apple);
    }

    /**
     * True when the user has at least one cloud/API key (or Bedrock creds),
     * has explicitly chosen Ollama as the default service type, or Apple
     * on-device is available. Apple availability uses a cached local helper
     * process probe, not a network call.
     */
    public boolean isReady() {
        return anyCloudReady || ollamaPreferred || appleAvailable;
    }

    /** True when at least one non-placeholder cloud/API key is set. */
    public boolean hasAnyCloudProvider() {
        return anyCloudReady;
    }

    /** True when a provider that supports Agent Mode has a key (Bedrock and Ollama do not count). */
    public boolean hasAgentCapableProvider() {
        return agentCapableReady;
    }

    /** True when {@code jmeter.ai.service.type=ollama}. */
    public boolean isOllamaPreferred() {
        return ollamaPreferred;
    }

    /** True when Apple on-device is enabled and its local helper reports availability. */
    public boolean isAppleAvailable() {
        return appleAvailable;
    }

    /**
     * True when a property looks like a real API key (non-empty, not a
     * YOUR_* placeholder from the sample file).
     */
    public static boolean isApiKeyConfigured(String propertyKey) {
        String value = AiConfig.getProperty(propertyKey, "");
        return isUsableSecret(value);
    }

    static boolean isBedrockConfigured() {
        if (isApiKeyConfigured("bedrock.api.key")) {
            return true;
        }
        String access = AiConfig.getProperty("bedrock.aws.access.key", "");
        String secret = AiConfig.getProperty("bedrock.aws.secret.key", "");
        return isUsableSecret(access) && isUsableSecret(secret);
    }

    /**
     * Rejects blank values and any sample-file placeholder starting with
     * {@code YOUR_} (case-insensitive), for example {@code YOUR_API_KEY},
     * {@code YOUR_GOOGLE_API_KEY}, {@code YOUR_TOKEN} or
     * {@code YOUR_AWS_ACCESS_KEY}.
     */
    static boolean isUsableSecret(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return !trimmed.regionMatches(true, 0, "YOUR_", 0, 5);
    }
}
