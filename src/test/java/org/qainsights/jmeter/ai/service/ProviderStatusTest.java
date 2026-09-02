package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.GatewayConfig;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class ProviderStatusTest {

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
        ProviderStatus status = ProviderStatus.fromConfig();
        assertFalse(status.isReady());
        assertFalse(status.hasAnyCloudProvider());
    }

    @Test
    void fromConfig_readyWithAnthropicKey() {
        props.put("anthropic.api.key", "sk-ant-test");
        ProviderStatus status = ProviderStatus.fromConfig();
        assertTrue(status.isReady());
        assertTrue(status.hasAnyCloudProvider());
    }

    @Test
    void fromConfig_readyWithHeaderOnlyGatewayCredentials() {
        props.put("openai.base.url", "https://openai.example/v1");
        props.put("openai.extra.headers", "X-Corp-Token=abc123");
        props.put("anthropic.base.url", "https://anthropic.example");
        props.put("anthropic.extra.headers", "X-Corp-Token=abc123");

        ProviderStatus status = ProviderStatus.fromConfig();

        assertTrue(status.isReady());
        assertTrue(status.hasAnyCloudProvider());
    }

    @Test
    void fromConfig_readyWhenOllamaServiceType() {
        props.put("jmeter.ai.service.type", "ollama");
        ProviderStatus status = ProviderStatus.fromConfig();
        assertTrue(status.isReady());
        assertTrue(status.isOllamaPreferred());
        assertFalse(status.hasAnyCloudProvider());
    }

    @Test
    void fromConfig_readyWithBedrockAccessPair() {
        props.put("bedrock.aws.access.key", "AKIA...");
        props.put("bedrock.aws.secret.key", "secret");
        assertTrue(ProviderStatus.fromConfig().isReady());
    }

    @Test
    void placeholderKey_notReady() {
        props.put("openai.api.key", "YOUR_OPENAI_API_KEY");
        assertFalse(ProviderStatus.fromConfig().isReady());
    }
}
