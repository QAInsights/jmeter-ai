package org.qainsights.jmeter.ai.agent.openai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ChatModel;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

/**
 * OpenAI-backed {@link ChatModel}. Stateful for a single agent run: it owns the
 * growing message history and re-sends it (with the system prompt and function
 * tool definitions) on each turn. The assistant's reply is echoed back into the
 * history via {@link ChatCompletionMessage#toParam()} (which preserves its tool
 * calls), and each tool outcome is appended as a {@code tool} role message.
 * Create a new instance per run.
 * <p>
 * Temperature is deliberately not set: several OpenAI models (o1/o3/o4/gpt-5)
 * reject any value other than the default. {@code reasoning_effort} is set only
 * where {@link OpenAiReasoningPolicy} says tool calling requires it.
 */
public final class OpenAiChatModel implements ChatModel {

    /** Seam over {@code client.chat().completions().create(params)} for testability. */
    @FunctionalInterface
    public interface CompletionService {
        ChatCompletion create(ChatCompletionCreateParams params);
    }

    private final CompletionService service;
    private final OpenAiToolAdapter adapter;
    private final List<ToolSpec> specs;
    private final String systemPrompt;
    private final String model;
    private final long maxTokens;
    private final Optional<ReasoningEffort> reasoningEffort;
    private final List<ChatCompletionMessageParam> history;

    public OpenAiChatModel(CompletionService service, OpenAiToolAdapter adapter, List<ToolSpec> specs,
                           String systemPrompt, String model, long maxTokens) {
        this(service, adapter, specs, systemPrompt, model, maxTokens, Collections.emptyList());
    }

    /**
     * @param seedHistory prior conversation turns (e.g. from an earlier chat message) to
     *                     prepend before the new user message, giving the model multi-turn
     *                     memory across separate agent runs.
     */
    public OpenAiChatModel(CompletionService service, OpenAiToolAdapter adapter, List<ToolSpec> specs,
                           String systemPrompt, String model, long maxTokens,
                           List<ChatCompletionMessageParam> seedHistory) {
        this(service, adapter, specs, systemPrompt, model, maxTokens, seedHistory, null);
    }

    /**
     * @param seedHistory       prior conversation turns to prepend before the new user message
     * @param reasoningSettings the user's reasoning choices (may be null); combined with the
     *                          tool-calling constraints by {@link OpenAiReasoningPolicy}
     */
    public OpenAiChatModel(CompletionService service, OpenAiToolAdapter adapter, List<ToolSpec> specs,
                           String systemPrompt, String model, long maxTokens,
                           List<ChatCompletionMessageParam> seedHistory,
                           org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings reasoningSettings) {
        this.service = service;
        this.adapter = adapter;
        this.specs = new ArrayList<>(specs);
        this.systemPrompt = systemPrompt;
        this.model = model;
        this.maxTokens = maxTokens;
        this.reasoningEffort = OpenAiReasoningPolicy.forToolCalling(model, reasoningSettings);
        this.history = new ArrayList<>(seedHistory);
    }

    /**
     * Converts flat alternating user/assistant strings (already normalized by
     * {@code ConversationSeed}) into OpenAI seed messages.
     */
    public static List<ChatCompletionMessageParam> toSeedHistory(List<String> alternatingTurns) {
        List<ChatCompletionMessageParam> seed = new ArrayList<>();
        if (alternatingTurns == null) {
            return seed;
        }
        for (int i = 0; i < alternatingTurns.size(); i++) {
            String turn = alternatingTurns.get(i);
            if (i % 2 == 0) {
                seed.add(userMessage(turn));
            } else {
                seed.add(ChatCompletionMessageParam.ofAssistant(
                        ChatCompletionAssistantMessageParam.builder().content(turn).build()));
            }
        }
        return seed;
    }

    @Override
    public AssistantTurn start(String userMessage) {
        history.add(userMessage(userMessage));
        return send();
    }

    @Override
    public AssistantTurn next(List<ToolOutcome> toolOutcomes) {
        for (ToolOutcome outcome : toolOutcomes) {
            history.add(ChatCompletionMessageParam.ofTool(adapter.toToolMessage(outcome)));
        }
        return send();
    }

    private AssistantTurn send() {
        ChatCompletionCreateParams.Builder params = ChatCompletionCreateParams.builder()
                .model(model)
                .maxCompletionTokens(maxTokens)
                .addSystemMessage(systemPrompt);
        if (reasoningEffort.isPresent()) {
            params.reasoningEffort(reasoningEffort.get());
        }
        for (ChatCompletionMessageParam message : history) {
            params.addMessage(message);
        }
        for (ToolSpec spec : specs) {
            params.addTool(adapter.toOpenAiTool(spec));
        }

        ChatCompletion completion = service.create(params.build());
        if (completion.choices().isEmpty()) {
            throw new IllegalStateException("OpenAI returned no choices for the agent request");
        }
        ChatCompletionMessage message = completion.choices().get(0).message();
        history.add(ChatCompletionMessageParam.ofAssistant(message.toParam()));
        return adapter.toAssistantTurn(message);
    }

    private static ChatCompletionMessageParam userMessage(String text) {
        return ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder().content(text).build());
    }
}
