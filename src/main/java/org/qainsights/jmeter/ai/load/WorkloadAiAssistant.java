package org.qainsights.jmeter.ai.load;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds private, counts-only prompts and validates optional workload suggestions. */
public final class WorkloadAiAssistant {
    private static final String PROMPT_RESOURCE = "/org/qainsights/jmeter/ai/load/workload-ai-prompt.txt";
    private static final Pattern PROMPT_TOKEN = Pattern.compile(
            "\\{\\{(DEFAULT_SAMPLES_PER_VCPU|FORM_VALUES|PLAN_SUMMARY|GOAL)}}");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private WorkloadAiAssistant() {
    }

    public static String buildPrompt(String goal, Map<String, ?> formValues, PlanSummary planSummary) {
        StringBuilder values = new StringBuilder();
        if (formValues != null) {
            formValues.forEach((key, value) -> values.append(key).append(": ")
                    .append(value == null ? "" : value).append('\n'));
        }
        String prompt;
        try (InputStream stream = WorkloadAiAssistant.class.getResourceAsStream(PROMPT_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("The workload AI prompt resource is missing.");
            }
            prompt = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the workload AI prompt resource.", e);
        }
        Map<String, String> replacements = Map.of(
                "DEFAULT_SAMPLES_PER_VCPU", Double.toString(WorkloadCalculator.DEFAULT_SAMPLES_PER_VCPU),
                "FORM_VALUES", values.toString().stripTrailing(),
                "PLAN_SUMMARY", (planSummary == null ? PlanSummary.empty() : planSummary).toPromptText(),
                "GOAL", goal == null ? "" : goal);
        Matcher matcher = PROMPT_TOKEN.matcher(prompt);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacements.get(matcher.group(1))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static Suggestion parse(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("The AI returned an empty suggestion.");
        }
        String json = stripFence(response.trim());
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("The AI response must be one JSON object.");
            }
            WorkloadInputs.TargetType targetType = targetType(text(root, "target_type"));
            double targetValue = requiredNumber(root, "target_value", true);
            Double rateCap = optionalNumber(root, "rate_cap_per_second");
            Double responseMs = optionalNumber(root, "response_ms");
            Double thinkMs = optionalNumber(root, "think_ms");
            Double rampSeconds = optionalNumber(root, "ramp_seconds");
            Double holdSeconds = optionalNumber(root, "hold_seconds");
            Double samplesPerVcpu = optionalNumber(root, "samples_per_vcpu");
            String summary = text(root, "summary");
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("The AI suggestion needs a summary.");
            }
            List<String> reasoning = stringList(root.get("reasoning"), Integer.MAX_VALUE);
            List<String> risks = stringList(root.get("risks"), 5).stream()
                    .map(risk -> risk.length() > 300 ? risk.substring(0, 300) : risk).toList();
            return new Suggestion(targetType, targetValue, rateCap, responseMs, thinkMs,
                    rampSeconds, holdSeconds, samplesPerVcpu, summary, reasoning, risks);
        } catch (IOException e) {
            throw new IllegalArgumentException("The AI response was not a single valid JSON object.", e);
        }
    }

    private static String stripFence(String response) {
        if (!response.startsWith("```")) {
            return response;
        }
        int firstLine = response.indexOf('\n');
        int closing = response.lastIndexOf("```");
        if (firstLine < 0 || closing <= firstLine || !response.substring(closing + 3).isBlank()) {
            throw new IllegalArgumentException("The AI response contains an incomplete code fence.");
        }
        return response.substring(firstLine + 1, closing).trim();
    }

    private static WorkloadInputs.TargetType targetType(String value) {
        if (value == null) {
            throw new IllegalArgumentException("The AI suggestion needs a supported target_type.");
        }
        return switch (value) {
            case "samples_per_second" -> WorkloadInputs.TargetType.REQUEST_RATE;
            case "iterations_per_second" -> WorkloadInputs.TargetType.ITERATION_RATE;
            case "messages_per_second" -> WorkloadInputs.TargetType.MESSAGE_RATE;
            case "users" -> WorkloadInputs.TargetType.USERS;
            default -> throw new IllegalArgumentException("Unsupported target_type: " + value);
        };
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(key + " must be text.");
        }
        return value.textValue();
    }

    private static double requiredNumber(JsonNode node, String key, boolean positive) {
        Double value = optionalNumber(node, key);
        if (value == null || (positive && value <= 0)) {
            throw new IllegalArgumentException(key + " must be greater than zero.");
        }
        return value;
    }

    private static Double optionalNumber(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException(key + " must be a number or null.");
        }
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number < 0) {
            throw new IllegalArgumentException(key + " must be finite and at least zero.");
        }
        return number;
    }

    private static List<String> stringList(JsonNode value, int maximum) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("reasoning and risks must be arrays of strings.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("reasoning and risks must contain only strings.");
            }
            if (result.size() < maximum) {
                result.add(item.textValue());
            }
        }
        return List.copyOf(result);
    }

    public record PlanSummary(SamplerMix samplerMix, int samplersPerIteration, int extractors,
                              int postProcessors, int scriptElements, int csvDataSets, int timers,
                              int assertions, boolean httpUsesHttps, List<Integer> jdbcPoolMaxValues) {
        public PlanSummary {
            samplerMix = samplerMix == null ? SamplerMix.classify(List.of()) : samplerMix;
            jdbcPoolMaxValues = jdbcPoolMaxValues == null ? List.of() : List.copyOf(jdbcPoolMaxValues);
        }

        public static PlanSummary empty() {
            return new PlanSummary(null, 0, 0, 0, 0, 0, 0, 0, false, List.of());
        }

        public String toPromptText() {
            Map<String, Integer> samplerCounts = new LinkedHashMap<>();
            for (SamplerMix.Kind kind : SamplerMix.Kind.values()) {
                samplerCounts.put(kind.label(), samplerMix.counts().getOrDefault(kind, 0));
            }
            return "sampler_counts: " + samplerCounts + "\n"
                    + "samplers_per_iteration: " + samplersPerIteration + "\n"
                    + "extractors: " + extractors + "\n"
                    + "post_processors: " + postProcessors + "\n"
                    + "jsr223_beanshell_elements: " + scriptElements + "\n"
                    + "csv_data_sets: " + csvDataSets + "\n"
                    + "timers: " + timers + "\n"
                    + "assertions: " + assertions + "\n"
                    + "http_uses_https: " + httpUsesHttps + "\n"
                    + "jdbc_pool_max_values: " + jdbcPoolMaxValues;
        }
    }

    public record Suggestion(WorkloadInputs.TargetType targetType, double targetValue, Double rateCap,
                             Double responseMs, Double thinkMs, Double rampSeconds, Double holdSeconds,
                             Double samplesPerVcpu, String summary, List<String> reasoning, List<String> risks) {
        public Suggestion {
            reasoning = reasoning == null ? List.of() : List.copyOf(reasoning);
            risks = risks == null ? List.of() : List.copyOf(risks);
        }
    }
}
