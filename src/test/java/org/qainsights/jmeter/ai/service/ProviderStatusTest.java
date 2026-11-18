package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.GatewayConfig;

import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class ProviderStatusTest {

    @TempDir Path home;

    private MockedStatic<AiConfig> aiConfig;
    private final Map<String, String> props = new HashMap<>();

    @BeforeEach
    void setUp() {
        props.clear();
        aiConfig = mockStatic(AiConfig.class);
        aiConfig.when(() -> AiConfig.getProperty(anyString(), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            String def = inv.getArgument(1);
            return props.getOrDefault(key, def);
        });
    }

    @AfterEach
    void tearDown() {
        if (aiConfig != null) {
            aiConfig.close();
        }
    }

    @Test
    void isUsableSecret_rejectsPlaceholdersAndBlank() {
        assertFalse(ProviderStatus.isUsableSecret(null));
        assertFalse(ProviderStatus.isUsableSecret(""));
        assertFalse(ProviderStatus.isUsableSecret("  "));
        assertFalse(ProviderStatus.isUsableSecret("YOUR_API_KEY"));
        assertFalse(ProviderStatus.isUsableSecret("YOUR_API_KEY_HERE"));
        assertFalse(ProviderStatus.isUsableSecret("YOUR_GOOGLE_API_KEY"));
        assertTrue(ProviderStatus.isUsableSecret("sk-real-key"));
    }

    @Test
    void isUsableSecret_rejectsAnyYourPrefixedPlaceholder() {
        assertFalse(ProviderStatus.isUsableSecret("YOUR_AWS_ACCESS_KEY"));
        assertFalse(ProviderStatus.isUsableSecret("YOUR_TOKEN"));
        assertFalse(ProviderStatus.isUsableSecret("YOUR_KEY"));
        assertFalse(ProviderStatus.isUsableSecret("your_api_key"));
    }

    @Test
    void fromConfig_notReadyWithoutKeys() {
        ProviderStatus status = ProviderStatus.fromConfig(() -> false);
        assertFalse(status.isReady());
        assertFalse(status.hasAnyCloudProvider());
        assertFalse(status.hasAgentCapableProvider());
    }

    @Test
    void fromConfig_cliProviderReadyWithoutKeysIsReadyAndAgentCapable() {
        ProviderStatus status = ProviderStatus.fromConfig(() -> false, true);

        assertTrue(status.isReady());
        assertTrue(status.hasAgentCapableProvider());
        assertFalse(status.hasAnyCloudProvider());
    }

    @Test
    void fromConfig_cliProviderNotReadyKeepsExistingReadinessBehavior() {
        ProviderStatus status = ProviderStatus.fromConfig(() -> false, false);

        assertFalse(status.isReady());
        assertFalse(status.hasAgentCapableProvider());
    }

    @Test
    void fromConfig_readyWithAppleOnDeviceAvailableWithoutCloudProviders() {
        ProviderStatus status = ProviderStatus.fromConfig(() -> true);

        assertTrue(status.isReady());
        assertTrue(status.isAppleAvailable());
        assertFalse(status.hasAgentCapableProvider());
        assertFalse(status.hasAnyCloudProvider());
    }

    @Test
    void fromConfig_notReadyWhenAppleOnDeviceUnavailableWithoutCloudProviders() {
        ProviderStatus status = ProviderStatus.fromConfig(() -> false);

        assertFalse(status.isReady());
        assertFalse(status.isAppleAvailable());
    }

    @Test
    void fromConfig_doesNotProbeAppleWhenDisabled() {
        props.put("apple.enabled", "false");
        AtomicBoolean supplierCalled = new AtomicBoolean();

        ProviderStatus status = ProviderStatus.fromConfig(() -> {
            supplierCalled.set(true);
            return true;
        });

        assertFalse(status.isReady());
        assertFalse(status.isAppleAvailable());
        assertFalse(supplierCalled.get());
    }

    @Test
    void fromConfig_readyWithAnthropicKey() {
        props.put("anthropic.api.key", "sk-ant-test");
        ProviderStatus status = ProviderStatus.fromConfig(() -> false);
        assertTrue(status.isReady());
        assertTrue(status.hasAnyCloudProvider());
    }

    @Test
    void fromConfig_readyWithHeaderOnlyGatewayCredentials() {
        props.put("openai.base.url", "https://openai.example/v1");
        props.put("openai.extra.headers", "X-Corp-Token=abc123");
        props.put("anthropic.base.url", "https://anthropic.example");
        props.put("anthropic.extra.headers", "X-Corp-Token=abc123");

        ProviderStatus status = ProviderStatus.fromConfig(() -> false);

        assertTrue(status.isReady());
        assertTrue(status.hasAnyCloudProvider());
    }

    @Test
    void fromConfig_readyWhenOllamaServiceType() {
        props.put("jmeter.ai.service.type", "ollama");
        ProviderStatus status = ProviderStatus.fromConfig(() -> false);
        assertTrue(status.isReady());
        assertTrue(status.isOllamaPreferred());
        assertFalse(status.hasAnyCloudProvider());
    }

    @Test
    void fromConfig_readyWithBedrockAccessPair() {
        props.put("bedrock.aws.access.key", "AKIA...");
        props.put("bedrock.aws.secret.key", "secret");
        assertTrue(ProviderStatus.fromConfig(() -> false).isReady());
    }

    @Test
    void placeholderKey_notReady() {
        props.put("openai.api.key", "YOUR_OPENAI_API_KEY");
        assertFalse(ProviderStatus.fromConfig(() -> false).isReady());
    }

    @Test
    void localAwsDetectionDoesNotConfigureAnEmptyMachine() {
        assertFalse(ProviderStatus.hasLocalAwsCredentials(key -> null, key -> null, home));
        assertFalse(ProviderStatus.hasLocalAwsCredentials(
                Map.of("AWS_ACCESS_KEY_ID", "YOUR_ACCESS_KEY", "AWS_SECRET_ACCESS_KEY", "YOUR_SECRET")::get,
                key -> null, home));
        assertFalse(ProviderStatus.hasLocalAwsCredentials(
                Map.of("AWS_ACCESS_KEY_ID", "access-only")::get, key -> null, home));
    }

    @Test
    void localAwsDetectionAcceptsProfileAndCredentialEnvironmentSources() {
        for (Map<String, String> env : java.util.List.of(
                Map.of("AWS_PROFILE", "work"),
                Map.of("AWS_ACCESS_KEY_ID", "access", "AWS_SECRET_ACCESS_KEY", "secret"),
                Map.of("AWS_BEARER_TOKEN_BEDROCK", "token"),
                Map.of("AWS_WEB_IDENTITY_TOKEN_FILE", "token-path", "AWS_ROLE_ARN", "role"),
                Map.of("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "/credentials"),
                Map.of("AWS_CONTAINER_CREDENTIALS_FULL_URI", "http://localhost/credentials"))) {
            assertTrue(ProviderStatus.hasLocalAwsCredentials(env::get, key -> null, home));
        }
        assertTrue(ProviderStatus.hasLocalAwsCredentials(key -> null, Map.of("aws.profile", "work")::get, home));
        assertTrue(ProviderStatus.hasLocalAwsCredentials(key -> null,
                Map.of("aws.accessKeyId", "access", "aws.secretAccessKey", "secret")::get, home));
    }

    @Test
    void localAwsDetectionAcceptsSharedFilesAndOverridesWithoutReadingSecrets() throws Exception {
        Files.createDirectories(home.resolve(".aws"));
        for (String file : java.util.List.of("credentials", "config")) {
            Path path = home.resolve(".aws").resolve(file);
            Files.createFile(path);
            assertTrue(ProviderStatus.hasLocalAwsCredentials(key -> null, key -> null, home));
            Files.delete(path);
        }
        Path custom = Files.createFile(home.resolve("custom-aws"));
        for (String key : java.util.List.of("AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE")) {
            assertTrue(ProviderStatus.hasLocalAwsCredentials(Map.of(key, custom.toString())::get, k -> null, home));
        }
        for (String key : java.util.List.of("aws.sharedCredentialsFile", "aws.configFile")) {
            assertTrue(ProviderStatus.hasLocalAwsCredentials(k -> null, Map.of(key, custom.toString())::get, home));
        }
        assertFalse(ProviderStatus.hasLocalAwsCredentials(
                Map.of("AWS_CONFIG_FILE", home.resolve("missing").toString())::get, key -> null, home));
    }
}
