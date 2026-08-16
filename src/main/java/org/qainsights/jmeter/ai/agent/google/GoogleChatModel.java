package org.qainsights.jmeter.ai.agent.google;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ChatModel;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.service.reasoning.GoogleThinking;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;

/**
 * Google Gemini-backed {@link ChatModel}. Stateful for a single agent run: it
 * owns the growing content history and re-sends it (with the system
 * instruction and function declarations) on each turn. The model's response
 * content is echoed back into the history verbatim (preserving its
 * {@code functionCall} parts), and tool outcomes are appended as a
 * {@code user} turn of {@code functionResponse} parts - the same shape the
 * SDK's own automatic-function-calling loop builds internally. Create a new
 * instance per run.
 */
public final class GoogleChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatModel.class);

    /** Seam over {@code client.models.generateContent(model, contents, config)} for testability. */
    @FunctionalInterface
    public interface GenerateService {
        GenerateContentResponse generate(String model, List<Content> contents, GenerateContentConfig config);
    }

    private final GenerateService service;
    private final GoogleToolAdapter adapter;
    private final List<ToolSpec> specs;
    private final String systemPrompt;
    private final String model;
    private final int maxOutputTokens;
    private final ReasoningSettings reasoningSettings;
    private final List<Content> history;
    private String lastReasoning;

    public GoogleChatModel(GenerateService service, GoogleToolAdapter adapter, List<ToolSpec> specs,
                           String systemPrompt, String model, long maxTokens) {
        this(service, adapter, specs, systemPrompt, model, maxTokens, Collections.emptyList());
    }

    /**
     * @param seedHistory prior conversation turns (e.g. from an earlier chat message) to
     *                     prepend before the new user message, giving the model multi-turn
     *                     memory across separate agent runs.
     */
    public GoogleChatModel(GenerateService service, GoogleToolAdapter adapter, List<ToolSpec> specs,
                           String systemPrompt, String model, long maxTokens, List<Content> seedHistory) {
        this(service, adapter, specs, systemPrompt, model, maxTokens, seedHistory, null);
    }

    /**
     * @param seedHistory       prior conversation turns to prepend before the new user message
     * @param reasoningSettings the user's reasoning choices (may be null); applied via
     *                          {@link GoogleThinking} when the model supports thinking
     */
    public GoogleChatModel(GenerateService service, GoogleToolAdapter adapter, List<ToolSpec> specs,
                           String systemPrompt, String model, long maxTokens, List<Content> seedHistory,
                           ReasoningSettings reasoningSettings) {
        this.service = service;
        this.adapter = adapter;
        this.specs = new ArrayList<>(specs);
        this.systemPrompt = systemPrompt;
        this.model = model;
        this.maxOutputTokens = (int) maxTokens;
        this.reasoningSettings = reasoningSettings;
        this.history = new ArrayList<>(seedHistory);
    }

    @Override
    public String consumeLastReasoning() {
        String reasoning = lastReasoning;
        lastReasoning = null;
        return reasoning;
    }

    /**
     * Converts flat alternating user/assistant strings (already normalized by
     * {@code ConversationSeed}) into Gemini seed contents.
     */
    public static List<Content> toSeedHistory(List<String> alternatingTurns) {
        List<Content> seed = new ArrayList<>();
        if (alternatingTurns == null) {
            return seed;
        }
        for (int i = 0; i < alternatingTurns.size(); i++) {
            String role = (i % 2 == 0) ? "user" : "model";
            seed.add(Content.builder().role(role).parts(Part.fromText(alternatingTurns.get(i))).build());
        }
        return seed;
    }

    @Override
    public AssistantTurn start(String userMessage) {
        history.add(Content.builder().role("user").parts(Part.fromText(userMessage)).build());
        return send();
    }

    @Override
    public AssistantTurn next(List<ToolOutcome> toolOutcomes) {
        List<Part> responseParts = new ArrayList<>();
        for (ToolOutcome outcome : toolOutcomes) {
            responseParts.add(adapter.toFunctionResponsePart(outcome));
        }
        history.add(Content.builder().role("user").parts(responseParts).build());
        return send();
    }

    private AssistantTurn send() {
        GenerateContentConfig.Builder config = GenerateContentConfig.builder()
                .maxOutputTokens(maxOutputTokens)
                .systemInstruction(Content.builder().parts(Part.fromText(systemPrompt)).build());

        if (!specs.isEmpty()) {
            List<FunctionDeclaration> declarations = new ArrayList<>();
            for (ToolSpec spec : specs) {
                declarations.add(adapter.toFunctionDeclaration(spec));
            }
            config.tools(Tool.builder().functionDeclarations(declarations).build());
        }
        GoogleThinking.configFor(reasoningSettings, model).ifPresent(thinking -> {
            config.thinkingConfig(thinking);
            log.info("Agent run: thinking config applied for model {}: {}", model, thinking);
        });

        // A defensive copy, so the request reflects exactly the history at call time even
        // though this method keeps mutating the `history` field afterwards (appending the
        // model's reply, and later the next turn's tool outcomes).
        GenerateContentResponse response = service.generate(model, new ArrayList<>(history), config.build());

        response.promptFeedback().flatMap(feedback -> feedback.blockReason()).ifPresent(reason -> {
            throw new IllegalStateException("Gemini blocked the agent request: " + reason);
        });

        List<Candidate> candidates = response.candidates().orElse(Collections.<Candidate>emptyList());
        if (candidates.isEmpty()) {
            throw new IllegalStateException("Gemini returned no candidates for the agent request");
        }
        Candidate candidate = candidates.get(0);
        Optional<Content> modelContent = candidate.content();
        List<Part> parts = modelContent
                .map(content -> content.parts().orElse(Collections.<Part>emptyList()))
                .orElse(Collections.emptyList());
        // An empty candidate (e.g. a safety/recitation block, or a mismatched tool-call id
        // rejected server-side) must not look like a successful, silent "no-op" turn -
        // AgentLoop treats empty text + no tool calls as completion, which would surface as a
        // bare "Done." in the transcript instead of the actual failure.
        if (parts.isEmpty()) {
            String finishReason = candidate.finishReason().map(Object::toString).orElse("unknown");
            throw new IllegalStateException(
                    "Gemini returned an empty response for the agent request (finishReason=" + finishReason + ")");
        }
        modelContent.ifPresent(history::add);

        lastReasoning = extractThoughts(parts);
        return adapter.toAssistantTurn(parts);
    }

    /** Concatenated thought-part text from the response's parts, or null when there was none. */
    private static String extractThoughts(List<Part> parts) {
        StringBuilder thoughts = new StringBuilder();
        for (Part part : parts) {
            if (part.thought().orElse(false)) {
                part.text().ifPresent(thoughts::append);
            }
        }
        return thoughts.length() == 0 ? null : thoughts.toString();
    }
}
