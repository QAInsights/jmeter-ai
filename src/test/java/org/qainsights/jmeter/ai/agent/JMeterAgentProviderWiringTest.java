package org.qainsights.jmeter.ai.agent;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.AiService;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link JMeterAgent}'s provider selection and factory validation. */
class JMeterAgentProviderWiringTest {

    /** A provider with no tool-calling adapter (e.g. Ollama, Bedrock, DeepSeek, Grok). */
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
    void providerResolverReturnsNullForUnsupportedServices() {
        UnsupportedService service = new UnsupportedService();

        assertNull(JMeterAgentProviderResolver.forService(service));
        assertNull(JMeterAgentProviderResolver.chatModelFactoryFor(service));
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
    void claudeFactory_allowsInjectedServicesToDisableCaching() {
        var finished = new IllegalStateException("captured request");
        AgentChatModelFactory factory = JMeterAgent.claudeFactory(params -> {
            var request = com.anthropic.core.ObjectMappers.jsonMapper()
                    .<com.fasterxml.jackson.databind.JsonNode>valueToTree(params._body());
            assertEquals("system", request.path("system").asText());
            assertTrue(request.path("cache_control").isMissingNode());
            throw finished;
        }, "claude", 1024, null, false);

        var model = factory.create(List.of(), "system", List.of());
        assertSame(finished, assertThrows(IllegalStateException.class, () -> model.start("inspect")));
    }

    @Test
    void openAiFactory_buildsAChatModelPerRun() {
        AgentChatModelFactory factory = JMeterAgent.openAiFactory(params -> {
            throw new IllegalStateException("not called");
        }, "gpt-4o", 1024);

        assertNotNull(factory.create(java.util.Collections.emptyList(), "system",
                java.util.Collections.emptyList()));
    }

    @Test
    void googleFactory_buildsAChatModelPerRun() {
        AgentChatModelFactory factory = JMeterAgent.googleFactory((model, contents, config) -> {
            throw new IllegalStateException("not called");
        }, "gemini-2.5-flash", 1024);

        assertNotNull(factory.create(java.util.Collections.emptyList(), "system",
                java.util.Collections.emptyList()));
    }
}
