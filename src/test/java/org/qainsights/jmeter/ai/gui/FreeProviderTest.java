package org.qainsights.jmeter.ai.gui;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.service.AiServiceHolder;
import org.qainsights.jmeter.ai.service.ProviderSetupOption;
import org.qainsights.jmeter.ai.service.StarterAiService;
import org.qainsights.jmeter.ai.utils.Models;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.qainsights.jmeter.ai.service.MetaMuseAiService;
import org.qainsights.jmeter.ai.service.BedrockAiService;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FreeProviderTest {
    @BeforeAll static void initJMeter() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    @Test void defaultsToFreeOnlyWhenNoOtherModelIsAvailable() {
        assertEquals(StarterAiService.MODEL_ID, Models.defaultChatModel(List.of(StarterAiService.MODEL_ID), "claude-default"));
        assertEquals("openai:gpt-4o", Models.defaultChatModel(List.of(StarterAiService.MODEL_ID, "openai:gpt-4o"), "claude-default"));
        assertEquals("claude-sonnet", Models.defaultChatModel(List.of(StarterAiService.MODEL_ID, "openai:gpt-4o", "claude-sonnet"), "claude-sonnet"));
        assertNull(Models.defaultChatModel(List.of(), "claude-default"));
    }

    @Test void freeIsFirstSetupOptionAndDisappearsWhenDisabled() {
        String before = JMeterUtils.getProperty("jmeter.ai.starter.enabled");
        try {
            JMeterUtils.setProperty("jmeter.ai.starter.enabled", "true");
            assertEquals(ProviderSetupOption.FREE, ProviderSetupDialog.navigationProviderOptions(() -> false).get(0));
            assertFalse(ProviderSetupOption.FREE.requiresKey());
            JMeterUtils.setProperty("jmeter.ai.starter.enabled", "false");
            assertFalse(ProviderSetupDialog.navigationProviderOptions(() -> false).contains(ProviderSetupOption.FREE));
        } finally {
            if (before == null) JMeterUtils.getJMeterProperties().remove("jmeter.ai.starter.enabled");
            else JMeterUtils.setProperty("jmeter.ai.starter.enabled", before);
        }
    }

    @Test void noKeyDiscoveryListsFreeWithoutCallingCloudCatalogs() {
        assertNoKeyDiscovery();
    }

    @Test void regionOnlyAwsConfigKeepsFreeDefault(@TempDir java.nio.file.Path home) throws Exception {
        java.nio.file.Files.createDirectories(home.resolve(".aws"));
        java.nio.file.Files.writeString(home.resolve(".aws/config"), "[default]\nregion = us-east-1\n");
        String before = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            assertNoKeyDiscovery();
        } finally {
            System.setProperty("user.home", before);
        }
    }

    private void assertNoKeyDiscovery() {
        java.util.Properties before = (java.util.Properties) JMeterUtils.getJMeterProperties().clone();
        try {
            JMeterUtils.getJMeterProperties().clear();
            JMeterUtils.setProperty("apple.enabled", "false");
            AiServiceHolder holder = new AiServiceHolder();
            ClaudeService claude = mock(ClaudeService.class);
            OpenAiService openai = mock(OpenAiService.class);
            MetaMuseAiService meta = mock(MetaMuseAiService.class);
            BedrockAiService bedrock = mock(BedrockAiService.class);
            holder.setClaudeService(claude);
            holder.setOpenAiService(openai);
            holder.setMetaMuseService(meta);
            holder.setBedrockService(bedrock);
            List<String> models = Models.loadAllModels(holder);
            assertEquals(List.of(StarterAiService.MODEL_ID), models);
            assertEquals(StarterAiService.MODEL_ID, Models.defaultChatModel(models, "claude-default"));
            verifyNoInteractions(claude, openai, meta, bedrock);
            JMeterUtils.setProperty("jmeter.ai.starter.enabled", "false");
            assertTrue(Models.loadAllModels(holder).isEmpty());
        } finally {
            JMeterUtils.getJMeterProperties().clear();
            JMeterUtils.getJMeterProperties().putAll(before);
        }
    }

    @Test void freeIsChatOnlyAndRoutesToItsOwnService() {
        StarterAiService service = mock(StarterAiService.class);
        AiServiceHolder holder = new AiServiceHolder();
        holder.setStarterService(service);
        AiResponseRouter router = new AiResponseRouter(holder);
        List<String> history = List.of("Hi");
        when(service.generateResponse(history)).thenReturn("Hello");
        assertEquals("Hello", router.getAiResponse(StarterAiService.MODEL_ID, history));
        assertSame(service, router.resolveAiService(StarterAiService.MODEL_ID));
        assertNull(JMeterAgent.forService(service));
        assertFalse(CommandDispatcher.isClaudeModel(StarterAiService.MODEL_ID));
        assertFalse(CommandDispatcher.isAgentCapableModel(StarterAiService.MODEL_ID));
        assertFalse(CommandDispatcher.shouldUseAgent(true, true, StarterAiService.MODEL_ID));
        InteractionModeSelector selector = new InteractionModeSelector(true);
        selector.setModelSupportsAgent(AiChatPanel.supportsAgent(StarterAiService.MODEL_ID));
        assertFalse(selector.isAgentSelected());
        assertFalse(selector.agentButton().isEnabled());
        assertEquals("Feather Wand Free", ModelDisplay.parse(StarterAiService.MODEL_ID)[0]);
        assertTrue(WelcomeMessages.freeWelcome().contains(StarterAiService.PRIVACY_NOTICE));
    }
}
