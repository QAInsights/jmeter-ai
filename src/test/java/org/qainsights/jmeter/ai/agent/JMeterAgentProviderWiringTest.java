package org.qainsights.jmeter.ai.agent;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.AiService;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link JMeterAgent}'s provider selection and factory validation. */
class JMeterAgentProviderWiringTest {

    /** A provider with no tool-calling adapter (e.g. Ollama, Gemini, Bedrock). */
    private static final class UnsupportedService implements AiService {
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
    }

    @Test
    void forService_unsupportedProvider_returnsNullSoTheCallerCanDegrade() {
        assertNull(JMeterAgent.forService(new UnsupportedService()));
    }

    @Test
    void forService_nullService_returnsNull() {
        assertNull(JMeterAgent.forService(null));
    }

    @Test
    void constructor_nullChatModelFactory_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JMeterAgent(null, 5, null));
    }

    @Test
    void claudeFactory_buildsAChatModelPerRun() {
        AgentChatModelFactory factory = JMeterAgent.claudeFactory(params -> {
            throw new IllegalStateException("not called");
        }, "claude", 1024);

        assertNotNull(factory.create(java.util.Collections.emptyList(), "system",
                java.util.Collections.emptyList()));
    }

    @Test
    void openAiFactory_buildsAChatModelPerRun() {
        AgentChatModelFactory factory = JMeterAgent.openAiFactory(params -> {
            throw new IllegalStateException("not called");
        }, "gpt-4o", 1024);

        assertNotNull(factory.create(java.util.Collections.emptyList(), "system",
                java.util.Collections.emptyList()));
    }
}
