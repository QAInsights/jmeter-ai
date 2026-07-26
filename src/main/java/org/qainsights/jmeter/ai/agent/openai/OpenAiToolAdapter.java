package org.qainsights.jmeter.ai.agent.openai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.JsonSchemaMapper;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

/**
 * Translates between our provider-neutral tool model and the OpenAI
 * (openai-java) chat-completions API: {@link ToolSpec} &rarr;
 * {@link ChatCompletionFunctionTool} with a JSON function schema, an assistant
 * {@link ChatCompletionMessage} &rarr; {@link AssistantTurn}, and a
 * {@link ToolOutcome} &rarr; {@link ChatCompletionToolMessageParam}.
 * <p>
 * The OpenAI counterpart of {@code ClaudeToolAdapter}; both build their schemas
 * from the same {@link JsonSchemaMapper} so tools look identical to every provider.
 */
public final class OpenAiToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiToolAdapter.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    /** Converts a provider-neutral spec into an OpenAI function-tool definition. */
    public ChatCompletionFunctionTool toOpenAiTool(ToolSpec spec) {
        FunctionParameters parameters = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(JsonSchemaMapper.properties(spec)))
                .putAdditionalProperty("required", JsonValue.from(JsonSchemaMapper.required(spec)))
                .build();

        FunctionDefinition function = FunctionDefinition.builder()
                .name(spec.getName())
                .description(spec.getDescription())
                .parameters(parameters)
                .build();

        return ChatCompletionFunctionTool.builder().function(function).build();
    }

    /**
     * Flattens an assistant message into a neutral turn. Custom (non-function)
     * tool calls are ignored - the agent only ever registers function tools.
     */
    public AssistantTurn toAssistantTurn(ChatCompletionMessage message) {
        String text = message.content().orElse("");
        List<AssistantTurn.ToolCall> calls = new ArrayList<>();
        for (ChatCompletionMessageToolCall toolCall : message.toolCalls().orElse(Collections.emptyList())) {
            if (!toolCall.isFunction()) {
                log.warn("Ignoring non-function tool call from OpenAI: {}", toolCall);
                continue;
            }
            ChatCompletionMessageFunctionToolCall function = toolCall.asFunction();
            calls.add(new AssistantTurn.ToolCall(function.id(), function.function().name(),
                    toArguments(function.function().arguments())));
        }
        return new AssistantTurn(text, calls);
    }

    /**
     * Builds the {@code tool} role message that carries a tool call's outcome back
     * to the model. OpenAI has no per-result error flag (unlike Anthropic's
     * {@code is_error}), so failures are conveyed by the {@code ERROR [...]} prefix
     * {@link ToolOutcome#from} already puts in the content.
     */
    public ChatCompletionToolMessageParam toToolMessage(ToolOutcome outcome) {
        return ChatCompletionToolMessageParam.builder()
                .toolCallId(outcome.getToolCallId())
                .content(outcome.getContent())
                .build();
    }

    /** Parses a function call's JSON argument string into a map, tolerating junk. */
    private Map<String, Object> toArguments(String arguments) {
        if (arguments == null || arguments.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(arguments, MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            log.warn("Could not parse OpenAI tool call arguments as JSON: {}", arguments, e);
            return new LinkedHashMap<>();
        }
    }
}
