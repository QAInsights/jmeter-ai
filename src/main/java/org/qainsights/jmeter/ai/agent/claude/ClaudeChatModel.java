package org.qainsights.jmeter.ai.agent.claude;

import java.util.ArrayList;
import java.util.List;

import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ChatModel;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;

/**
 * Anthropic-backed {@link ChatModel}. Stateful for a single agent run: it owns
 * the growing message history and re-sends it (with the system prompt and tool
 * definitions) on each turn. The assistant's response is echoed back into the
 * history via {@link Message#toParam()}, and tool outcomes are appended as a
 * user turn of tool_result blocks. Create a new instance per run.
 */
public final class ClaudeChatModel implements ChatModel {

    /** Seam over {@code client.messages().create(params)} for testability. */
    @FunctionalInterface
    public interface MessageService {
        Message create(MessageCreateParams params);
    }

    private final MessageService service;
    private final ClaudeToolAdapter adapter;
    private final List<ToolSpec> specs;
    private final String systemPrompt;
    private final String model;
    private final long maxTokens;
    private final List<MessageParam> history = new ArrayList<>();

    public ClaudeChatModel(MessageService service, ClaudeToolAdapter adapter, List<ToolSpec> specs,
                           String systemPrompt, String model, long maxTokens) {
        this.service = service;
        this.adapter = adapter;
        this.specs = new ArrayList<>(specs);
        this.systemPrompt = systemPrompt;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public AssistantTurn start(String userMessage) {
        history.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(userMessage)
                .build());
        return send();
    }

    @Override
    public AssistantTurn next(List<ToolOutcome> toolOutcomes) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ToolOutcome outcome : toolOutcomes) {
            blocks.add(ContentBlockParam.ofToolResult(adapter.toResultBlock(outcome)));
        }
        history.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(blocks)
                .build());
        return send();
    }

    private AssistantTurn send() {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .messages(history);
        for (ToolSpec spec : specs) {
            params.addTool(adapter.toAnthropicTool(spec));
        }

        Message response = service.create(params.build());
        history.add(response.toParam());
        return adapter.toAssistantTurn(response.content());
    }
}
