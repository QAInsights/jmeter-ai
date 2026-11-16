package org.qainsights.jmeter.ai.opencode;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.qainsights.jmeter.ai.cli.CliProcessResult;
import org.qainsights.jmeter.ai.cli.CliProviderException;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Parses JSON event streams and error responses from OpenCode V1 and V2 CLIs. */
final class OpenCodeOutputParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenCodeOutputParser() {
    }

    static String readAnswer(CliProcessResult result, Duration timeout, String timeoutKey,
            String model, List<String> discoveredModels, List<String> configuredModels) {
        if (result.isTimedOut()) {
            throw new CliProviderException("OpenCode did not respond within "
                    + timeout.toSeconds() + " seconds and was stopped. Increase "
                    + timeoutKey + " if your prompts need longer.");
        }

        ParsedOutput output = parseOutput(result.getStdout());
        if (output.errorName() != null || output.errorMessage() != null) {
            throw new CliProviderException(describeError(output.errorName(), output.errorMessage(),
                    result, model, discoveredModels, configuredModels));
        }
        if (!result.isSuccess()) {
            throw new CliProviderException(describeFailure(result));
        }
        if (output.answer().isBlank()) {
            throw new CliProviderException(
                    "OpenCode returned an empty response. Try rephrasing your request.");
        }
        return output.answer().trim();
    }

    static String describeError(String errorName, String errorMessage, CliProcessResult result,
            String model, List<String> discoveredModels, List<String> configuredModels) {
        String name = errorName == null ? "" : errorName.toLowerCase(Locale.ROOT);
        String message = errorMessage == null ? "" : errorMessage;
        String lowerMessage = message.toLowerCase(Locale.ROOT).replace('_', ' ');
        if (name.contains("freetiererror") || name.contains("free-limit")) {
            return "OpenCode's free tier refused this request. Pick another free model, or add a provider "
                    + "with 'opencode auth login' in the AI CLI terminal tab.";
        }
        if (isAuthError(name, lowerMessage)) {
            return "OpenCode isn't signed in to that model's provider. Run 'opencode auth login' in the "
                    + "AI CLI terminal tab, or pick an opencode/ free model.";
        }
        if (isModelNotFoundError(name, lowerMessage)) {
            String modelId = model == null || model.isEmpty() ? OpenCodeCliProvider.DEFAULT_MODEL : model;
            return "OpenCode doesn't know the model '" + modelId
                    + "'. Pick one from the list or check 'opencode models'.";
        }
        return describeFallbackError(name, message, result, model, discoveredModels, configuredModels);
    }

    private static boolean isAuthError(String name, String lowerMessage) {
        return name.contains("provider.auth") || lowerMessage.contains("401")
                || lowerMessage.contains("unauthorized") || lowerMessage.contains("api key")
                || lowerMessage.contains("apikey") || lowerMessage.contains("not authenticated");
    }

    private static boolean isModelNotFoundError(String name, String lowerMessage) {
        return name.contains("modelnotfound") || name.contains("no-route")
                || lowerMessage.contains("model not found")
                || lowerMessage.contains("model unavailable");
    }

    private static String describeFallbackError(String name, String message, CliProcessResult result,
            String model, List<String> discoveredModels, List<String> configuredModels) {
        if (("unknownerror".equals(name) || "unknown".equals(name))
                && model != null && !model.isBlank()
                && discoveredModels != null && !discoveredModels.isEmpty()
                && !discoveredModels.contains(model)
                && (configuredModels == null || !configuredModels.contains(model))) {
            String modelHint = "'" + model
                    + "' isn't in the 'opencode models' list, so check the model ID.";
            return message.isBlank()
                    ? describeFailure(result) + " " + modelHint
                    : "OpenCode failed: " + firstLines(message) + " " + modelHint;
        }
        return message.isBlank() ? describeFailure(result) : "OpenCode failed: " + firstLines(message);
    }

    private static String describeFailure(CliProcessResult result) {
        String detail = result.getStderr() == null ? "" : result.getStderr().trim();
        if (detail.isEmpty() && result.getStdout() != null) {
            detail = result.getStdout().trim();
        }
        String suffix = detail.isEmpty() ? "" : " Details: " + firstLines(detail);
        return "OpenCode failed: exited with code " + result.getExitCode() + "." + suffix;
    }

    private static String firstLines(String text) {
        String[] lines = text.trim().split("\\R");
        int count = Math.min(lines.length, 3);
        return String.join(" ", Arrays.copyOfRange(lines, 0, count));
    }

    private static ParsedOutput parseOutput(String stdout) {
        if (stdout == null || stdout.isEmpty()) {
            return new ParsedOutput("", null, null);
        }
        StringBuilder answer = new StringBuilder();
        ParsedOutput errorOutput = null;
        for (String line : stdout.split("\\R")) {
            JsonNode event = parseJsonObject(line);
            if (event == null) {
                continue;
            }
            String type = event.path("type").asText("");
            if ("step_start".equals(type)) {
                answer.setLength(0);
            } else if ("text".equals(type)) {
                JsonNode text = event.path("part").path("text");
                if (text.isTextual()) {
                    answer.append(text.asText());
                }
            } else if ("error".equals(type)) {
                errorOutput = parseErrorEvent(event);
            }
        }
        return errorOutput == null
                ? new ParsedOutput(answer.toString(), null, null)
                : new ParsedOutput(answer.toString(), errorOutput.errorName(), errorOutput.errorMessage());
    }

    private static JsonNode parseJsonObject(String line) {
        if (line.isBlank()) {
            return null;
        }
        try {
            JsonNode event = JSON.readTree(line);
            return event != null && event.isObject() ? event : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static ParsedOutput parseErrorEvent(JsonNode event) {
        JsonNode error = event.path("error");
        boolean nestedError = error.isObject();
        JsonNode source = nestedError ? error : event;
        String errorName = firstNonBlank(source.path("name").asText(null),
                nestedError ? source.path("type").asText(null) : null,
                event.path("name").asText(null));
        String errorMessage = firstNonBlank(source.path("data").path("message").asText(null),
                source.path("message").asText(null),
                event.path("data").path("message").asText(null));
        return new ParsedOutput("", errorName, errorMessage);
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record ParsedOutput(String answer, String errorName, String errorMessage) { }
}
