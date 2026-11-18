package org.qainsights.jmeter.ai.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.qainsights.jmeter.ai.agent.AgentJudgmentConfig;
import org.qainsights.jmeter.ai.apple.AppleFoundationHelper;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.GatewayConfig;

/**
 * Read-only snapshot of which AI providers look configured from JMeter
 * properties. No network calls: Ollama is treated as "available to try"
 * without probing, CLI providers are represented by their loaded models, and
 * Apple availability comes only from the helper's cached status. This class
 * never starts the helper process.
 */
public final class ProviderStatus {

    private final boolean anyCloudReady;
    private final boolean ollamaPreferred;
    private final boolean agentCapableReady;
    private final boolean appleAvailable;
    private final boolean cliProviderReady;

    private ProviderStatus(boolean anyCloudReady, boolean ollamaPreferred,
                           boolean agentCapableReady, boolean appleAvailable,
                           boolean cliProviderReady) {
        this.anyCloudReady = anyCloudReady;
        this.ollamaPreferred = ollamaPreferred;
        this.agentCapableReady = agentCapableReady;
        this.appleAvailable = appleAvailable;
        this.cliProviderReady = cliProviderReady;
    }

    /** Loads status from the current {@link AiConfig} / JMeter properties. */
    public static ProviderStatus fromConfig() {
        return fromConfig(() -> AppleFoundationHelper.cachedStatus()
                .map(AppleFoundationHelper.Status::isAvailable)
                .orElse(false));
    }

    /** Loads status using the supplied Apple on-device availability value. */
    public static ProviderStatus fromConfig(BooleanSupplier appleOnDeviceAvailable) {
        return fromConfig(appleOnDeviceAvailable, false);
    }

    /** Loads status using the supplied Apple and CLI provider availability values. */
    public static ProviderStatus fromConfig(BooleanSupplier appleOnDeviceAvailable, boolean cliProviderReady) {
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
        return new ProviderStatus(cloud, ollamaPreferred, agentCapable, apple, cliProviderReady);
    }

    /**
     * True when the user has at least one cloud/API key (or Bedrock creds),
     * has explicitly chosen Ollama as the default service type, or Apple
     * on-device is available, or a CLI provider has loaded models. Apple
     * availability uses a cached local helper process probe, not a network call.
     */
    public boolean isReady() {
        return anyCloudReady || ollamaPreferred || appleAvailable || cliProviderReady;
    }

    /** True when at least one non-placeholder cloud/API key is set. */
    public boolean hasAnyCloudProvider() {
        return anyCloudReady;
    }

    /** True when an Agent Mode provider has a key or a CLI provider has loaded models. */
    public boolean hasAgentCapableProvider() {
        return agentCapableReady || cliProviderReady;
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

    public static boolean isBedrockConfigured() {
        if (isApiKeyConfigured("bedrock.api.key")) {
            return true;
        }
        String access = AiConfig.getProperty("bedrock.aws.access.key", "");
        String secret = AiConfig.getProperty("bedrock.aws.secret.key", "");
        return (isUsableSecret(access) && isUsableSecret(secret))
                || hasLocalAwsCredentials(System::getenv, System::getProperty,
                        Path.of(System.getProperty("user.home")));
    }

    static boolean hasLocalAwsCredentials(Function<String, String> env, Function<String, String> properties,
                                          Path home) {
        if (isUsableSecret(properties.apply("aws.profile"))
                || isUsableSecret(env.apply("AWS_PROFILE"))
                || isUsableSecret(env.apply("AWS_BEARER_TOKEN_BEDROCK"))
                || isUsableSecret(properties.apply("aws.bearerTokenBedrock"))
                || (isUsableSecret(env.apply("AWS_ACCESS_KEY_ID")) && isUsableSecret(env.apply("AWS_SECRET_ACCESS_KEY")))
                || (isUsableSecret(properties.apply("aws.accessKeyId")) && isUsableSecret(properties.apply("aws.secretAccessKey")))
                || (isUsableSecret(env.apply("AWS_WEB_IDENTITY_TOKEN_FILE")) && isUsableSecret(env.apply("AWS_ROLE_ARN")))
                || (isUsableSecret(properties.apply("aws.webIdentityTokenFile")) && isUsableSecret(properties.apply("aws.roleArn")))
                || isUsableSecret(env.apply("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"))
                || isUsableSecret(env.apply("AWS_CONTAINER_CREDENTIALS_FULL_URI"))) {
            return true;
        }
        return hasAwsFile(properties.apply("aws.sharedCredentialsFile"), env.apply("AWS_SHARED_CREDENTIALS_FILE"),
                home.resolve(".aws/credentials"))
                || hasAwsFile(properties.apply("aws.configFile"), env.apply("AWS_CONFIG_FILE"), home.resolve(".aws/config"));
    }

    private static boolean hasAwsFile(String property, String environment, Path fallback) {
        try {
            Path path = isUsableSecret(property) ? Path.of(property)
                    : isUsableSecret(environment) ? Path.of(environment) : fallback;
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) return false;
            try (var lines = Files.lines(path)) {
                return lines.anyMatch(line -> {
                    String[] setting = line.trim().split("=", 2);
                    if (setting.length != 2 || !isUsableSecret(setting[1])
                            || setting[1].trim().startsWith("#") || setting[1].trim().startsWith(";")) return false;
                    String key = setting[0].trim().toLowerCase(Locale.ROOT);
                    return key.equals("aws_access_key_id") || key.equals("credential_process")
                            || key.startsWith("sso_") || key.equals("role_arn") || key.equals("web_identity_token_file");
                });
            }
        } catch (IOException | java.io.UncheckedIOException | IllegalArgumentException | SecurityException e) {
            return false;
        }
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
