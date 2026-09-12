package org.qainsights.jmeter.ai.service;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.JMeterAgent;

import com.anthropic.client.AnthropicClient;
import com.openai.client.OpenAIClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class JMeterAgentOpenAiCompatibleTest {

    @Test
    void openAiCompatibleProviders_areAvailableToAgentMode() {
        GrokAiService grok = new GrokAiService(mock(OpenAIClient.class), "http://localhost", "grok-2",
                0.7f, 10, 4096L, "test");
        MetaMuseAiService metaMuse = new MetaMuseAiService(mock(OpenAIClient.class), "http://localhost",
                "muse-spark-1.1", 0.7f, 10, 4096L, "test");
        DeepseekAiService deepseek = new DeepseekAiService(mock(OpenAIClient.class), null, false,
                "http://localhost", "deepseek-chat", 0.7f, 10, 4096L, "test");

        assertNotNull(JMeterAgent.forService(grok));
        assertNotNull(JMeterAgent.forService(metaMuse));
        assertNotNull(JMeterAgent.forService(deepseek));
        assertNotNull(JMeterAgent.chatModelFactoryFor(grok));
        assertNotNull(JMeterAgent.chatModelFactoryFor(metaMuse));
        assertNotNull(JMeterAgent.chatModelFactoryFor(deepseek));
    }

    @Test
    void deepseekAnthropicFormat_isAvailableToAgentMode() {
        DeepseekAiService deepseek = new DeepseekAiService(null, mock(AnthropicClient.class), true,
                "http://localhost", "deepseek-chat", 0.7f, 10, 4096L, "test");

        assertNotNull(JMeterAgent.forService(deepseek));
        assertNotNull(JMeterAgent.chatModelFactoryFor(deepseek));
    }

    @Test
    void unsupportedProvider_isNotAvailableToAgentMode() {
        assertNull(JMeterAgent.forService(new AiService() {
            @Override
            public String generateResponse(List<String> conversation) {
                return "";
            }

            @Override
            public String generateResponse(List<String> conversation, String model) {
                return "";
            }

            @Override
            public String getName() {
                return "unsupported";
            }
        }));
    }
}
