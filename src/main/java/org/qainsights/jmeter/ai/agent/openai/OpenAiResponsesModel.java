package org.qainsights.jmeter.ai.agent.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ChatModel;
import org.qainsights.jmeter.ai.agent.loop.ProviderTokenUsage;
import org.qainsights.jmeter.ai.agent.loop.TokenUsageTracker;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.service.reasoning.OpenAiReasoning;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseStatus;

/**
 * OpenAI Responses API-backed {@link ChatModel}. The Chat Completions API does
 * not accept function tools together with a non-default reasoning effort for
 * newer GPT models, so those agent runs use this transport instead.
 */
public final class OpenAiResponsesModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesModel.class);

    @FunctionalInterface
    public interface ResponseService {
        Response create(ResponseCreateParams params);
    }

    private final ResponseService service;
    private final OpenAiToolAdapter adapter;
    private List<ToolSpec> specs;
    private final String systemPrompt;
    private final String model;
    private final long maxTokens;
    private final Optional<ReasoningEffort> reasoningEffort;
    private final List<ResponseInputItem> inputItems;
    private final TokenUsageTracker usage = new TokenUsageTracker("openai");

    public OpenAiResponsesModel(ResponseService service, OpenAiToolAdapter adapter, List<ToolSpec> specs,
                                String systemPrompt, String model, long maxTokens,
                                List<ResponseInputItem> seedHistory,
                                ReasoningSettings reasoningSettings) {
        this.service = service;
        this.adapter = adapter;
        this.specs = new ArrayList<>(specs);
        this.systemPrompt = systemPrompt;
        this.model = model;
        this.maxTokens = maxTokens;
        this.reasoningEffort = OpenAiReasoning.effortFor(reasoningSettings, model);
        this.inputItems = new ArrayList<>(seedHistory);
    }

    public static List<ResponseInputItem> toSeedHistory(List<String> alternatingTurns) {
        List<ResponseInputItem> seed = new ArrayList<>();
        if (alternatingTurns == null) {
            return seed;
        }
        for (int i = 0; i < alternatingTurns.size(); i++) {
            EasyInputMessage.Role role = i % 2 == 0
                    ? EasyInputMessage.Role.USER : EasyInputMessage.Role.ASSISTANT;
            seed.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                    .role(role)
                    .content(alternatingTurns.get(i))
                    .build()));
        }
        return seed;
    }

    @Override
    public AssistantTurn start(String userMessage) {
        inputItems.add(userMessage(userMessage));
        return send();
    }

    @Override
    public AssistantTurn followUp(String userMessage) {
        return start(userMessage);
    }

    @Override
    public void updateToolSpecs(List<ToolSpec> specs) {
        this.specs = new ArrayList<>(specs);
    }

    @Override
    public AssistantTurn next(List<ToolOutcome> toolOutcomes) {
        for (ToolOutcome outcome : toolOutcomes) {
            inputItems.add(adapter.toFunctionCallOutput(outcome));
        }
        return send();
    }

    private AssistantTurn send() {
        ResponseCreateParams.Builder params = ResponseCreateParams.builder()
                .model(model)
                .instructions(systemPrompt)
                .maxOutputTokens(maxTokens)
                .store(false)
                .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
                .inputOfResponse(new ArrayList<>(inputItems));
        if (reasoningEffort.isPresent()) {
            params.reasoning(Reasoning.builder().effort(reasoningEffort.get()).build());
            log.info("Agent run: reasoning.effort={} for model {}", reasoningEffort.get(), model);
        }
        for (ToolSpec spec : specs) {
            params.addTool(adapter.toResponsesTool(spec));
        }

        ResponseCreateParams request = params.build();
        var mapper = com.openai.core.ObjectMappers.jsonMapper();
        return usage.track(TokenUsageTracker.RequestSize.json(mapper, request._body(), specs.size()),
                () -> service.create(request), reply -> reply.usage()
                        .map(value -> ProviderTokenUsage.openAiResponses(mapper.valueToTree(value)))
                        .orElse(TokenUsageTracker.Counts.UNREPORTED), this::acceptResponse);
    }

    private AssistantTurn acceptResponse(Response response) {
        if (response.status().isPresent()
                && !ResponseStatus.COMPLETED.asString().equals(response.status().get().asString())) {
            String reason = response.incompleteDetails()
                    .flatMap(details -> details.reason().map(Object::toString))
                    .orElse("no completion reason reported");
            throw new IllegalStateException("OpenAI Responses API returned status "
                    + response.status().get().asString() + ": " + reason);
        }
        if (response.output().isEmpty()) {
            throw new IllegalStateException("OpenAI Responses API returned no output for the agent request");
        }
        StringBuilder text = new StringBuilder();
        List<AssistantTurn.ToolCall> calls = new ArrayList<>();
        for (ResponseOutputItem item : response.output()) {
            toInputItem(item).ifPresent(inputItems::add);
            if (item.isMessage()) {
                String part = adapter.outputText(item.asMessage());
                if (!part.isEmpty()) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(part);
                }
            } else if (item.isFunctionCall()) {
                calls.add(adapter.toToolCall(item.asFunctionCall()));
            }
        }
        return new AssistantTurn(text.toString(), calls);
    }

    private static Optional<ResponseInputItem> toInputItem(ResponseOutputItem item) {
        if (item.isReasoning()) {
            return Optional.of(ResponseInputItem.ofReasoning(item.asReasoning()));
        }
        if (item.isMessage()) {
            return Optional.of(ResponseInputItem.ofResponseOutputMessage(item.asMessage()));
        }
        if (item.isFunctionCall()) {
            return Optional.of(ResponseInputItem.ofFunctionCall(item.asFunctionCall()));
        }
        log.debug("Not replaying unsupported OpenAI Responses output item: {}", item);
        return Optional.empty();
    }

    private static ResponseInputItem userMessage(String text) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .content(text)
                .build());
    }

    @Override
    public TokenUsageTracker usageTracker() {
        return usage;
    }

    /** True when this model should be used instead of Chat Completions. */
    public static boolean applies(String model,
                                  ReasoningSettings reasoningSettings) {
        if (requiresResponsesForTools(model)) {
            return true;
        }
        Optional<ReasoningEffort> effort = OpenAiReasoning.effortFor(reasoningSettings, model);
        return effort.isPresent() && effort.get() != ReasoningEffort.NONE;
    }

    /** GPT-6 models reject function tools on Chat Completions unless reasoning is explicitly off. */
    private static boolean requiresResponsesForTools(String model) {
        return model != null && model.trim().toLowerCase(Locale.ROOT).startsWith("gpt-6");
    }
}
