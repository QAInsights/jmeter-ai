package org.qainsights.jmeter.ai.agent;

import java.util.List;

import org.qainsights.jmeter.ai.agent.loop.ChatModel;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * Creates a fresh, provider-specific {@link ChatModel} for a single agent run.
 * This is the one seam {@link JMeterAgent} needs to stay provider-neutral: each
 * provider (Claude, OpenAI, ...) supplies a factory that knows how to wire its
 * own SDK client, tool schema and seed messages.
 */
@FunctionalInterface
public interface AgentChatModelFactory {

    /**
     * @param specs                   the tools to advertise to the model
     * @param systemPrompt            the schema-grounded system prompt
     * @param priorConversationTurns  normalized alternating user/assistant turns
     *                                 (see {@link ConversationSeed#normalize}); may be empty
     * @return a stateful chat model scoped to one run
     */
    ChatModel create(List<ToolSpec> specs, String systemPrompt, List<String> priorConversationTurns);
}
