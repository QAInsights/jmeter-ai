package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.service.ProviderStatus;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.Constants;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class WelcomeMessagesTest {

    private MockedStatic<AiConfig> aiConfig;

    @BeforeEach
    void setUp() {
        aiConfig = mockStatic(AiConfig.class);
        aiConfig.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
    }

    @AfterEach
    void tearDown() {
        if (aiConfig != null) {
            aiConfig.close();
        }
    }

    @Test
    void forStatus_nullOrNotReady_returnsSetupCta() {
        String msg = WelcomeMessages.forStatus(ProviderStatus.fromConfig());
        assertTrue(msg.contains("no API key is configured"));
        assertTrue(msg.contains("user.properties"));
        assertTrue(msg.contains("Ollama"));
        assertNotEquals(Constants.WELCOME_MESSAGE, msg);
    }

    @Test
    void forStatus_ready_returnsFullWelcome() {
        aiConfig.when(() -> AiConfig.getProperty(anyString(), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            String def = inv.getArgument(1);
            if ("openai.api.key".equals(key)) {
                return "sk-test";
            }
            return def;
        });
        String msg = WelcomeMessages.forStatus(ProviderStatus.fromConfig());
        assertEquals(Constants.WELCOME_MESSAGE, msg);
        assertTrue(msg.contains("@this"));
    }

    @Test
    void forStatus_nullStatus_treatedAsNotReady() {
        assertTrue(WelcomeMessages.forStatus(null).contains("no API key is configured"));
    }

    @Test
    void setupWelcome_mentionsSamplePropertiesAndRestart() {
        String setup = WelcomeMessages.setupWelcome();
        assertTrue(setup.contains("jmeter-ai-sample.properties"));
        assertTrue(setup.contains("Restart JMeter"));
    }
}
