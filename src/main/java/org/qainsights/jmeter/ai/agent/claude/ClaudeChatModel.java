package org.qainsights.jmeter.ai.agent.claude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ChatModel;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigEnabled;

/**
 * Anthropic-backed {@link ChatModel}. Stateful for a single agent run: it owns
 * the growing message history and re-sends it (with the system prompt and tool
 * definitions) on each turn. The assistant's response is echoed back into the
 * history via {@link Message#toParam()}, and tool outcomes are appended as a
 * user turn of tool_result blocks. Create a new instance per run.
 */
public final class ClaudeChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ClaudeChatModel.class);

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
    private final Long thinkingBudget;
    private final String thinkingEffort;
    private final List<MessageParam> history;
    private String lastReasoning;

    public ClaudeChatModel(MessageService service, ClaudeToolAdapter adapter, List<ToolSpec> specs,
                           String systemPrompt, String model, long maxTokens) {
        this(service, adapter, specs, systemPrompt, model, maxTokens, Collections.emptyList());
    }

    /**
     * @param seedHistory prior conversation turns (e.g. from an earlier chat message) to
     *                     prepend before the new user message, giving the model multi-turn
     *                     memory across separate agent runs.
     */
    public ClaudeChatModel(MessageService service, ClaudeToolAdapter adapter, List<ToolSpec> specs,
                           String systemPrompt, String model, long maxTokens, List<MessageParam> seedHistory) {
        this(service, adapter, specs, systemPrompt, model, maxTokens, seedHistory, null);
    }

    /**
     * @param seedHistory    prior conversation turns to prepend before the new user message
     * @param thinkingBudget extended-thinking budget in tokens, or null to leave thinking
     *                       off. When set, max_tokens is bumped above the budget as the
     *                       API requires, and {@link Message#toParam()} keeps the thinking
     *                       blocks in the history (also required by the API).
     */
    public ClaudeChatModel(MessageService service, ClaudeToolAdapter adapter, List<ToolSpec> specs,
                           String systemPrompt, String model, long maxTokens, List<MessageParam> seedHistory,
                           Long thinkingBudget) {
        this(service, adapter, specs, systemPrompt, model, maxTokens, seedHistory, thinkingBudget, null);
    }

    /**
     * @param thinkingBudget extended-thinking budget in tokens (or the adaptive marker), or null
     * @param thinkingEffort effort level for adaptive-thinking models (fable family),
     *                       sent via {@code output_config}; may be null
     */
    public ClaudeChatModel(MessageService service, ClaudeToolAdapter adapter, List<ToolSpec> specs,
                           String systemPrompt, String model, long maxTokens, List<MessageParam> seedHistory,
                           Long thinkingBudget, String thinkingEffort) {
        this.service = service;
        this.adapter = adapter;
        this.specs = new ArrayList<>(specs);
        this.systemPrompt = systemPrompt;
        this.model = model;
        this.maxTokens = maxTokens;
        this.thinkingBudget = thinkingBudget;
        this.thinkingEffort = thinkingEffort;
        this.history = new ArrayList<>(seedHistory);
    }

    /** The thinking budget this run was created with, or null when thinking is off. */
    public Long getThinkingBudget() {
        return thinkingBudget;
    }

    @Override
    public String consumeLastReasoning() {
        String reasoning = lastReasoning;
        lastReasoning = null;
        return reasoning;
    }

    /**
     * Converts flat alternating user/assistant strings (already normalized by
     * {@code ConversationSeed}) into Anthropic seed messages.
     */
    public static List<MessageParam> toSeedHistory(List<String> alternatingTurns) {
        List<MessageParam> seed = new ArrayList<>();
        if (alternatingTurns == null) {
            return seed;
        }
        for (int i = 0; i < alternatingTurns.size(); i++) {
            MessageParam.Role role = (i % 2 == 0) ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;
            seed.add(MessageParam.builder().role(role).content(alternatingTurns.get(i)).build());
        }
        return seed;
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
        boolean thinkingOn = thinkingBudget != null && thinkingBudget > 0;
        // Adaptive-thinking models (fable family) take no budget and need no
        // max_tokens bump; a non-null thinkingBudget is just the "on" marker.
        boolean adaptive = thinkingOn
                && org.qainsights.jmeter.ai.service.reasoning.AnthropicThinking
                        .isAdaptiveThinkingModel(model);
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(thinkingOn && !adaptive ? Math.max(maxTokens, thinkingBudget + 1024) : maxTokens)
                .system(systemPrompt)
                .messages(history);
        if (adaptive) {
            params.thinking(ThinkingConfigAdaptive.builder()
                    .display(ThinkingConfigAdaptive.Display.SUMMARIZED)
                    .build());
            com.anthropic.models.messages.OutputConfig.Effort effort =
                    org.qainsights.jmeter.ai.service.reasoning.AnthropicThinking.toOutputEffort(thinkingEffort);
            if (effort != null) {
                params.outputConfig(com.anthropic.models.messages.OutputConfig.builder()
                        .effort(effort).build());
            }
            log.info("Agent run: adaptive thinking ENABLED for model {} (effort: {})",
                    model, thinkingEffort == null ? "default" : thinkingEffort);
        } else if (thinkingOn) {
            params.thinking(ThinkingConfigEnabled.builder().budgetTokens(thinkingBudget).build());
            log.info("Agent run: thinking ENABLED for model {} with budget {} tokens", model, thinkingBudget);
        }
        for (ToolSpec spec : specs) {
            params.addTool(adapter.toAnthropicTool(spec));
        }

        Message response = service.create(params.build());
        history.add(response.toParam());
        lastReasoning = org.qainsights.jmeter.ai.service.reasoning.AnthropicThinking
                .extractThinking(response.content());
        return adapter.toAssistantTurn(response.content());
    }
}
