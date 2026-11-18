package org.qainsights.jmeter.ai.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
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
                || hasLocalAwsCredentials(System::getenv, System::getProperty, null);
    }

    static boolean hasLocalAwsCredentials(Function<String, String> env, Function<String, String> properties,
                                          Path home) {
        if (isUsableSecret(env.apply("AWS_BEARER_TOKEN_BEDROCK"))
                || isUsableSecret(properties.apply("aws.bearerTokenBedrock"))
                || (isUsableSecret(env.apply("AWS_ACCESS_KEY_ID")) && isUsableSecret(env.apply("AWS_SECRET_ACCESS_KEY")))
                || (isUsableSecret(properties.apply("aws.accessKeyId")) && isUsableSecret(properties.apply("aws.secretAccessKey")))
                || (isUsableSecret(env.apply("AWS_WEB_IDENTITY_TOKEN_FILE")) && isUsableSecret(env.apply("AWS_ROLE_ARN")))
                || (isUsableSecret(properties.apply("aws.webIdentityTokenFile")) && isUsableSecret(properties.apply("aws.roleArn")))
                || isUsableSecret(env.apply("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"))
                || isUsableSecret(env.apply("AWS_CONTAINER_CREDENTIALS_FULL_URI"))) {
            return true;
        }
        Map<String, Map<String, String>> profiles = new HashMap<>();
        readAwsFile(profiles, properties.apply("aws.configFile"), env.apply("AWS_CONFIG_FILE"), home,
                properties.apply("user.home"), "config", true);
        readAwsFile(profiles, properties.apply("aws.sharedCredentialsFile"), env.apply("AWS_SHARED_CREDENTIALS_FILE"), home,
                properties.apply("user.home"), "credentials", false);
        String profile = isUsableSecret(properties.apply("aws.profile")) ? properties.apply("aws.profile")
                : isUsableSecret(env.apply("AWS_PROFILE")) ? env.apply("AWS_PROFILE") : "default";
        return hasProfileCredentials(profiles, profile.trim(), new HashSet<>());
    }

    private static void readAwsFile(Map<String, Map<String, String>> profiles, String property, String environment,
                                    Path home, String userHome, String file, boolean config) {
        try {
            Path path = isUsableSecret(property) ? Path.of(property)
                    : isUsableSecret(environment) ? Path.of(environment)
                    : home != null ? home.resolve(".aws").resolve(file)
                    : isUsableSecret(userHome) ? Path.of(userHome, ".aws", file) : null;
            if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) return;
            String section = null;
            for (String raw : Files.readAllLines(path)) {
                String line = raw.trim();
                if (line.startsWith("#") || line.startsWith(";")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    if (config && section.startsWith("profile ")) section = section.substring(8).trim();
                    else if (config && !section.equals("default") && !section.startsWith("sso-session ")) section = null;
                    continue;
                }
                String[] setting = line.split("=", 2);
                if (section != null && setting.length == 2 && isUsableSecret(setting[1])
                        && !setting[1].trim().startsWith("#") && !setting[1].trim().startsWith(";")) {
                    profiles.computeIfAbsent(section, key -> new HashMap<>())
                            .put(setting[0].trim().toLowerCase(Locale.ROOT), setting[1].trim());
                }
            }
        } catch (IOException | java.io.UncheckedIOException | IllegalArgumentException | SecurityException e) {
            // Unreadable local configuration is not a credential source.
        }
    }

    private static boolean hasProfileCredentials(Map<String, Map<String, String>> profiles, String name, Set<String> visited) {
        if (!visited.add(name)) return false;
        Map<String, String> profile = profiles.getOrDefault(name, Map.of());
        if (hasSettings(profile, "aws_access_key_id", "aws_secret_access_key") || hasSettings(profile, "credential_process")) return true;
        if (hasSettings(profile, "role_arn")) {
            if (hasSettings(profile, "web_identity_token_file")) return true;
            if (hasSettings(profile, "source_profile")
                    && hasProfileCredentials(profiles, profile.get("source_profile"), visited)) return true;
            if ("Ec2InstanceMetadata".equals(profile.get("credential_source"))
                    || "EcsContainer".equals(profile.get("credential_source"))) return true;
        }
        if (!hasSettings(profile, "sso_account_id", "sso_role_name")) return false;
        Map<String, String> session = profile.containsKey("sso_session")
                ? profiles.getOrDefault("sso-session " + profile.get("sso_session"), Map.of()) : profile;
        return hasSettings(session, "sso_start_url", "sso_region");
    }

    private static boolean hasSettings(Map<String, String> profile, String... names) {
        for (String name : names) if (!isUsableSecret(profile.get(name))) return false;
        return true;
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
