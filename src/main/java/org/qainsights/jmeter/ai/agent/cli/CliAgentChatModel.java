package org.qainsights.jmeter.ai.agent.cli;

import java.util.ArrayList;
import java.util.List;

import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ChatModel;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.cli.SubscriptionCliProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ChatModel} backed by a subscription CLI (Codex, Claude Code). The CLIs
 * are one-shot and keep no session for us, so this model owns the transcript and
 * replays it - system prompt, {@link CliToolProtocol} instructions, prior turns,
 * tool results - on every invocation. Stateful for a single agent run: create
 * one per run, like the SDK-backed models.
 */
public final class CliAgentChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CliAgentChatModel.class);

    private final SubscriptionCliProvider provider;
    private final String header;
    private final List<String> transcript = new ArrayList<>();
    private int turn;

    /**
     * @param specs        the tools advertised to the model
     * @param systemPrompt the schema-grounded JMeter agent prompt
     * @param priorTurns   earlier plain-text conversation turns, user first
     */
    public CliAgentChatModel(SubscriptionCliProvider provider, List<ToolSpec> specs, String systemPrompt,
                             List<String> priorTurns) {
        this.provider = provider;
        this.header = systemPrompt + "\n\n" + CliToolProtocol.instructions(specs);
        if (priorTurns != null) {
            for (int i = 0; i < priorTurns.size(); i++) {
                transcript.add((i % 2 == 0 ? "User: " : "Assistant: ") + priorTurns.get(i));
            }
        }
    }

    @Override
    public AssistantTurn start(String userMessage) {
        transcript.add("User: " + userMessage);
        return send();
    }

    @Override
    public AssistantTurn next(List<ToolOutcome> toolOutcomes) {
        StringBuilder results = new StringBuilder("Tool results:");
        for (ToolOutcome outcome : toolOutcomes) {
            results.append("\n- ").append(outcome.getName()).append(": ")
                    .append(outcome.isError() ? "FAILED " : "").append(outcome.getContent());
        }
        transcript.add(results.toString());
        return send();
    }

    private AssistantTurn send() {
        StringBuilder prompt = new StringBuilder(header).append("\n\n");
        for (String entry : transcript) {
            prompt.append(entry).append("\n\n");
        }
        prompt.append("Reply now with a single JSON object.");

        String reply = provider.execute(prompt.toString());
        transcript.add("Assistant: " + reply);
        AssistantTurn assistantTurn = CliToolProtocol.parse(reply, "call_" + turn++);
        log.info("{} agent turn {}: {} tool call(s)", provider.displayName(), turn,
                assistantTurn.getToolCalls().size());
        return assistantTurn;
    }
}
