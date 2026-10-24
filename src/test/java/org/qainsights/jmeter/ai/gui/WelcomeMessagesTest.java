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
        String msg = WelcomeMessages.forStatus(ProviderStatus.fromConfig(() -> false));
        assertTrue(msg.contains("Click **Connect a provider** below"));
        assertTrue(msg.contains("pick a provider and paste its key"));
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
        String msg = WelcomeMessages.forStatus(ProviderStatus.fromConfig(() -> false));
        assertEquals(Constants.WELCOME_MESSAGE, msg);
        assertTrue(msg.contains("@this"));
    }

    @Test
    void forCurrentConfig_appleAvailableWithoutKeys_returnsFullWelcome() {
        String msg = WelcomeMessages.forCurrentConfig(new AgentModeNudge(), () -> true);

        assertEquals(Constants.WELCOME_MESSAGE, msg);
    }

    @Test
    void forStatus_nullStatus_treatedAsNotReady() {
        assertTrue(WelcomeMessages.forStatus(null).contains("Connect a provider"));
    }

    @Test
    void setupWelcome_linksQuickstartPropertiesAndRestart() {
        String setup = WelcomeMessages.setupWelcome();
        assertTrue(setup.contains("jmeter-ai-quickstart.properties"));
        assertTrue(setup.contains(WelcomeMessages.QUICKSTART_URL));
        assertTrue(setup.contains("restart JMeter"));
        assertTrue(setup.contains("Prefer local?"));
    }

    @Test
    void setupWelcomeExplainsDetectedOllamaAndConnectAction() {
        String setup = WelcomeMessages.setupWelcome(true);

        assertTrue(setup.startsWith("# Welcome to Feather Wand"));
        assertTrue(setup.contains("Click **Connect a provider** below"));
        assertTrue(setup.contains(
                "Ollama is running on this machine. Click **Connect a provider** to use it, no key needed."));
        assertTrue(setup.contains("Prefer editing files?"));
        assertFalse(setup.contains("Prefer local?"));
    }
}
