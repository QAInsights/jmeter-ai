package org.qainsights.jmeter.ai.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.RequestOptions;
import com.openai.models.decisions.Decision;
import com.openai.models.decisions.DecisionChoiceOption;
import com.openai.models.decisions.DecisionChoiceValue;
import com.openai.models.decisions.DecisionCreateParams;
import com.openai.services.blocking.DecisionService;
import org.qainsights.jmeter.ai.utils.GatewayConfig;

/**
 * {@link JudgmentProvider} backed by the OpenAI Decisions API ({@code POST /v1/decisions}).
 * Each call sends one {@code choice} question whose options are the criteria keys.
 */
public final class OpenAiDecisionsJudgmentProvider implements JudgmentProvider {
    public static final String DEFAULT_MODEL = "gpt-6-luna";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    static final String QUESTION_NAME = "route";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DecisionService decisions;
    private final String model;
    private final Duration timeout;

    public OpenAiDecisionsJudgmentProvider(String apiKey, String model, Duration timeout) {
        this(GatewayConfig.apply(OpenAIOkHttpClient.builder(), apiKey).build().decisions(),
                model, timeout);
    }

    OpenAiDecisionsJudgmentProvider(DecisionService decisions, String model, Duration timeout) {
        this.decisions = decisions;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHOICE);
    }

    @Override
    public ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria) {
        if (instructions == null || instructions.isBlank() || criteria == null || criteria.size() < 2) {
            throw new DecisionsException("A choice judgment needs instructions and at least two criteria");
        }
        DecisionCreateParams params = request(state, instructions, criteria);
        Decision decision;
        try {
            decision = decisions.create(params, RequestOptions.builder().timeout(timeout).build());
        } catch (RuntimeException e) {
            throw new DecisionsException("OpenAI Decisions request failed", e);
        }
        try {
            return parseChoice(decision, criteria);
        } catch (DecisionsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DecisionsException("OpenAI Decisions response was malformed", e);
        }
    }

    DecisionCreateParams request(Object state, String instructions, Map<String, String> criteria) {
        DecisionCreateParams.Question.Choice.Builder question = DecisionCreateParams.Question.Choice.builder()
                .name(QUESTION_NAME)
                .instructions(instructions);
        criteria.forEach((value, description) -> question.addChoice(
                DecisionChoiceOption.builder().value(value).description(description).build()));
        return DecisionCreateParams.builder()
                .model(model)
                .input(input(state))
                .addQuestion(question.build())
                .build();
    }

    private static String input(Object state) {
        try {
            return MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new DecisionsException("Could not serialize the judgment input", e);
        }
    }

    private static ChoiceAnswer parseChoice(Decision decision, Map<String, String> criteria) {
        List<Decision.Answer> answers = decision.answers();
        if (answers.isEmpty()) {
            throw new DecisionsException("OpenAI Decisions response contained no answers");
        }
        Decision.Answer answer = answers.get(0);
        if (answer.isRefusal()) {
            throw new DecisionsException("OpenAI Decisions declined to answer");
        }
        if (!answer.isChoice()) {
            throw new DecisionsException("OpenAI Decisions response did not contain a choice answer");
        }
        Decision.Answer.Choice choice = answer.asChoice();
        String selected = stringValue(choice.choice());
        if (selected == null || !criteria.containsKey(selected)) {
            throw new DecisionsException("OpenAI Decisions response contained an unknown choice");
        }
        double confidence = choice.confidence();
        if (!validProbability(confidence)) {
            throw new DecisionsException("OpenAI Decisions response contained invalid confidence");
        }
        Map<String, Double> probabilities = new LinkedHashMap<>();
        criteria.keySet().forEach(option -> probabilities.put(option, 0.0));
        for (Decision.Answer.Choice.Probability entry : choice.probabilities()) {
            String option = stringValue(entry.value());
            if (option == null || !criteria.containsKey(option)) {
                continue;
            }
            if (!validProbability(entry.probability())) {
                throw new DecisionsException("OpenAI Decisions response contained invalid probabilities");
            }
            probabilities.put(option, entry.probability());
        }
        return new ChoiceAnswer(selected, probabilities, confidence);
    }

    private static String stringValue(DecisionChoiceValue value) {
        return value != null && value.isString() ? value.asString() : null;
    }

    private static boolean validProbability(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    public static final class DecisionsException extends RuntimeException {
        public DecisionsException(String message) {
            super(message);
        }

        public DecisionsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
