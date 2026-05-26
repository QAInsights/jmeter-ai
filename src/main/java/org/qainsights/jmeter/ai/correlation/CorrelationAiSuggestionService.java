package org.qainsights.jmeter.ai.correlation;

import com.google.genai.Client;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.DeepseekAiService;
import org.qainsights.jmeter.ai.service.GoogleAiService;
import org.qainsights.jmeter.ai.service.OllamaAiService;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class CorrelationAiSuggestionService {
    private static final Logger log = LoggerFactory.getLogger(CorrelationAiSuggestionService.class);
    private static final int MAX_CANDIDATES_PER_REQUEST = 25;

    public void enhance(List<CorrelationCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        Optional<AiService> service = createConfiguredService();
        if (service.isEmpty()) {
            log.info("No configured AI service found for correlation suggestion enhancement; using deterministic suggestions");
            return;
        }
        try {
            String response = service.get().generateResponse(List.of(buildPrompt(candidates.subList(0, Math.min(candidates.size(), MAX_CANDIDATES_PER_REQUEST)))));
            applyResponse(response, candidates);
        } catch (Exception e) {
            log.warn("Correlation AI suggestion enhancement failed; using deterministic suggestions", e);
        }
    }

    private static Optional<AiService> createConfiguredService() {
        String serviceType = AiConfig.getProperty("jmeter.ai.service.type", "openai");
        try {
            if ("openai".equalsIgnoreCase(serviceType) && isConfigured("openai.api.key", "openai.default.model")) {
                return Optional.of(new OpenAiService());
            }
            if ("anthropic".equalsIgnoreCase(serviceType) && isConfigured("anthropic.api.key", "anthropic.model")) {
                return Optional.of(new ClaudeService());
            }
            if ("deepseek".equalsIgnoreCase(serviceType) && isConfigured("deepseek.api.key", "deepseek.default.model")) {
                return Optional.of(new DeepseekAiService());
            }
            if ("google".equalsIgnoreCase(serviceType) && isConfigured("google.api.key", "google.default.model")) {
                String apiKey = AiConfig.getProperty("google.api.key", "");
                return Optional.of(new GoogleAiService(Client.builder().apiKey(apiKey).build()));
            }
            if ("ollama".equalsIgnoreCase(serviceType)) {
                String model = AiConfig.getProperty("ollama.default.model", "llama3.1");
                if (model != null && !model.isBlank()) {
                    return Optional.of(new OllamaAiService());
                }
            }
        } catch (Exception e) {
            log.warn("Unable to initialize configured AI service for correlation suggestions: {}", serviceType, e);
        }
        return Optional.empty();
    }

    private static boolean isConfigured(String apiKeyProperty, String modelProperty) {
        String apiKey = AiConfig.getProperty(apiKeyProperty, "");
        String model = AiConfig.getProperty(modelProperty, "");
        return apiKey != null && !apiKey.isBlank() && !"YOUR_API_KEY".equals(apiKey) && model != null && !model.isBlank();
    }

    private static String buildPrompt(List<CorrelationCandidate> candidates) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are assisting a JMeter correlation review workflow. ")
                .append("Return extractor suggestions only as pipe-delimited lines with this exact schema: ")
                .append("index|extractorType|variableName|expression|matchNo. ")
                .append("extractorType must be one of Regex, JSONPath, Boundary. ")
                .append("Do not include markdown, explanations, or token values. ")
                .append("Use the provided current expression if it is already suitable.\n");
        for (int index = 0; index < candidates.size(); index++) {
            CorrelationCandidate candidate = candidates.get(index);
            ExtractorSuggestion suggestion = candidate.getSuggestion();
            prompt.append(index)
                    .append(". sampler=").append(candidate.getSourceResponse().getLabel())
                    .append(", responseLocation=").append(candidate.getResponseLocation())
                    .append(", requestLocation=").append(candidate.getRequestLocation())
                    .append(", currentType=").append(suggestion.getExtractorType().getDisplayName())
                    .append(", currentVariable=").append(suggestion.getVariableName())
                    .append(", currentExpression=").append(suggestion.getExpression())
                    .append('\n');
        }
        return prompt.toString();
    }

    private static void applyResponse(String response, List<CorrelationCandidate> candidates) {
        for (String line : safe(response).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.contains("|")) {
                continue;
            }
            String[] parts = trimmed.split("\\|", 5);
            if (parts.length != 5) {
                continue;
            }
            int index = parseIndex(parts[0]);
            if (index < 0 || index >= candidates.size()) {
                continue;
            }
            Optional<ExtractorType> extractorType = parseExtractorType(parts[1]);
            String variableName = sanitizeVariableName(parts[2]);
            String expression = parts[3].trim();
            String matchNo = parts[4].trim();
            if (extractorType.isEmpty() || variableName.isBlank() || expression.isBlank()) {
                continue;
            }
            ExtractorSuggestion suggestion = candidates.get(index).getSuggestion();
            suggestion.setExtractorType(extractorType.get());
            suggestion.setVariableName(variableName);
            suggestion.setExpression(expression);
            suggestion.setMatchNo(matchNo.isBlank() ? "1" : matchNo);
        }
    }

    private static int parseIndex(String value) {
        try {
            return Integer.parseInt(value.trim().replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Optional<ExtractorType> parseExtractorType(String value) {
        String normalized = safe(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if ("regex".equals(normalized) || "regularexpression".equals(normalized)) {
            return Optional.of(ExtractorType.REGEX);
        }
        if ("jsonpath".equals(normalized) || "json".equals(normalized)) {
            return Optional.of(ExtractorType.JSON_PATH);
        }
        if ("boundary".equals(normalized)) {
            return Optional.of(ExtractorType.BOUNDARY);
        }
        return Optional.empty();
    }

    private static String sanitizeVariableName(String value) {
        String sanitized = safe(value).trim().replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return sanitized.isBlank() ? "correlated_value" : sanitized;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
