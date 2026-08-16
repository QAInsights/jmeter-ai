package org.qainsights.jmeter.ai.agent.google;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.JsonSchemaMapper;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;

/**
 * Translates between our provider-neutral tool model and the Google Gen AI
 * (google-genai) function-calling API: {@link ToolSpec} &rarr;
 * {@link FunctionDeclaration} with a JSON schema, a model turn's parts &rarr;
 * {@link AssistantTurn}, and a {@link ToolOutcome} &rarr; a
 * {@code functionResponse} {@link Part}.
 * <p>
 * The Gemini counterpart of {@code ClaudeToolAdapter}/{@code OpenAiToolAdapter};
 * all three build their schemas from the same {@link JsonSchemaMapper} so a
 * tool looks identical to every provider.
 */
public final class GoogleToolAdapter {

    /**
     * Ids this adapter fabricated in {@link #toAssistantTurn} because the model's
     * {@code functionCall} had none. Tracked so {@link #toFunctionResponsePart} can tell a
     * real call id from a synthetic one: echoing a synthetic id back to Gemini as a
     * {@code functionResponse.id} makes some model versions reject the follow-up turn with a
     * 400, since the id never existed on the original call.
     */
    private final Set<String> syntheticCallIds = new HashSet<>();

    /** Converts a provider-neutral spec into a Gemini function declaration. */
    public FunctionDeclaration toFunctionDeclaration(ToolSpec spec) {
        Map<String, Schema> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> property : JsonSchemaMapper.properties(spec).entrySet()) {
            properties.put(property.getKey(), toSchema(property.getValue()));
        }
        Schema parameters = Schema.builder()
                .type("OBJECT")
                .properties(properties)
                .required(JsonSchemaMapper.required(spec))
                .build();

        return FunctionDeclaration.builder()
                .name(spec.getName())
                .description(spec.getDescription())
                .parameters(parameters)
                .build();
    }

    /**
     * Flattens a model turn's parts into a neutral turn. Thought parts are
     * skipped here - {@code GoogleChatModel} extracts those separately for the
     * reasoning consumer.
     */
    public AssistantTurn toAssistantTurn(List<Part> parts) {
        StringBuilder text = new StringBuilder();
        List<AssistantTurn.ToolCall> calls = new ArrayList<>();
        int callIndex = 0;
        for (Part part : parts) {
            if (part.thought().orElse(false)) {
                continue;
            }
            // Text and functionCall are inspected independently (not else-if): Gemini
            // sometimes attaches an empty/placeholder text part alongside a functionCall,
            // and dropping the call there would silently end the tool loop on the prose.
            if (part.text().isPresent()) {
                text.append(part.text().get());
            }
            if (part.functionCall().isPresent()) {
                FunctionCall call = part.functionCall().get();
                // Gemini rarely populates FunctionCall.id() outside the Live API; fall back to
                // a positional id so every call still has one to correlate its outcome with.
                String id = call.id().orElse(null);
                if (id == null) {
                    id = "call_" + callIndex;
                    syntheticCallIds.add(id);
                }
                calls.add(new AssistantTurn.ToolCall(id, call.name().orElse(""),
                        call.args().orElse(Collections.emptyMap())));
                callIndex++;
            }
        }
        return new AssistantTurn(text.toString(), calls);
    }

    /**
     * Builds the {@code functionResponse} part carrying a tool call's outcome back to the
     * model. Gemini has no per-result error flag (unlike Anthropic's {@code is_error}), so
     * failures are conveyed by the {@code ERROR [...]} prefix {@link ToolOutcome#from}
     * already puts in the content, and surfaced under an {@code error} key here too.
     * <p>
     * The response echoes {@link ToolOutcome#getToolCallId()} as {@code functionResponse.id}
     * only when that id came from a real {@code FunctionCall.id()} (tracked via
     * {@link #syntheticCallIds}); Gemini 3.x requires a matching id when the call had one, but
     * some model versions 400 if an id is present on the response when the call had none.
     */
    public Part toFunctionResponsePart(ToolOutcome outcome) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(outcome.isError() ? "error" : "output", outcome.getContent());
        FunctionResponse.Builder functionResponse = FunctionResponse.builder()
                .name(outcome.getName())
                .response(response);
        String callId = outcome.getToolCallId();
        if (callId != null && !syntheticCallIds.contains(callId)) {
            functionResponse.id(callId);
        }
        return Part.builder().functionResponse(functionResponse).build();
    }

    /** Recursively converts a plain JSON-Schema fragment (from {@link JsonSchemaMapper}) into a {@link Schema}. */
    @SuppressWarnings("unchecked")
    private Schema toSchema(Object jsonSchema) {
        Map<String, Object> fragment = (Map<String, Object>) jsonSchema;
        Schema.Builder schema = Schema.builder();

        // JsonSchemaMapper emits lowercase JSON-Schema keywords (string/object/array/...);
        // Gemini's Type enum is uppercase (STRING/OBJECT/ARRAY/...) on the wire.
        Object type = fragment.get("type");
        if (type != null) {
            schema.type(String.valueOf(type).toUpperCase(Locale.ROOT));
        }
        Object description = fragment.get("description");
        if (description != null) {
            schema.description(String.valueOf(description));
        }
        Object enumValues = fragment.get("enum");
        if (enumValues instanceof List) {
            List<String> values = new ArrayList<>();
            for (Object value : (List<?>) enumValues) {
                values.add(String.valueOf(value));
            }
            schema.enum_(values);
        }
        Object items = fragment.get("items");
        if (items != null) {
            schema.items(toSchema(items));
        }
        return schema.build();
    }
}
