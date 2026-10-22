package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qainsights.jmeter.ai.apple.AppleFoundationHelper;
import org.qainsights.jmeter.ai.cli.CliProcessResult;
import org.qainsights.jmeter.ai.cli.CliProcessRunner;
import org.qainsights.jmeter.ai.cli.DefaultCliProcessRunner;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class AppleFoundationAiService implements AiService {
    public static final String MODEL_PREFIX = "apple:";
    public static final String MODEL_ID = "apple:on-device";
    static final String MAX_HISTORY_KEY = "apple.max.history.size";
    private static final Logger log = LoggerFactory.getLogger(AppleFoundationAiService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CliProcessRunner runner;
    private final Path helperPath;

    public AppleFoundationAiService() {
        this(new DefaultCliProcessRunner(), AppleFoundationHelper.resolveExecutable().orElse(null));
    }

    AppleFoundationAiService(CliProcessRunner runner, Path helperPath) {
        this.runner = runner;
        this.helperPath = helperPath;
    }

    @Override
    public String getName() {
        return "Apple on-device";
    }

    @Override
    public String generateResponse(List<String> conversation) {
        return generateResponse(conversation, null);
    }

    @Override
    public String generateResponse(List<String> conversation, String model) {
        StringBuilder response = new StringBuilder();
        request(conversation, response::append);
        return response.toString();
    }

    @Override
    public Runnable generateStreamResponse(List<String> conversation, String model,
                                           Consumer<String> tokenConsumer, Runnable onComplete,
                                           Consumer<Exception> onError) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            try {
                request(conversation, delta -> {
                    if (!cancelled.get()) {
                        tokenConsumer.accept(delta);
                    }
                });
                if (!cancelled.get()) {
                    onComplete.run();
                }
            } catch (RuntimeException e) {
                if (!cancelled.get()) {
                    log.warn("Apple on-device request failed: {}", e.getMessage());
                    onError.accept(e);
                }
            }
        }, "apple-foundation-request");
        worker.setDaemon(true);
        worker.start();
        return () -> {
            cancelled.set(true);
            worker.interrupt();
        };
    }

    private void request(List<String> conversation, Consumer<String> deltaConsumer) {
        String instructions = systemPrompt();
        List<String> history = HistoryWindow.maskFailedReplies(
                HistoryWindow.lastTurns(conversation == null ? List.of() : conversation, maxHistorySize()));
        AtomicBoolean emittedDeltas = new AtomicBoolean(false);
        try {
            invoke(buildPrompt(history), instructions, delta -> {
                emittedDeltas.set(true);
                deltaConsumer.accept(delta);
            });
        } catch (AppleFoundationException e) {
            if (!"context_exceeded".equals(e.getCode())) {
                throw e;
            }
            if (emittedDeltas.get()) {
                throw conversationTooLong(e);
            }
            try {
                String lastUserMessage = lastUserMessage(history);
                invoke(buildPrompt(List.of(lastUserMessage)), instructions, deltaConsumer);
            } catch (AppleFoundationException retryError) {
                if ("context_exceeded".equals(retryError.getCode())) {
                    throw conversationTooLong(retryError);
                }
                throw retryError;
            }
        }
    }

    private static AppleFoundationException conversationTooLong(AppleFoundationException cause) {
        return new AppleFoundationException(
                "This conversation is too long for Apple's on-device model. Start a New conversation and try again.",
                "context_exceeded", cause);
    }

    private void invoke(String prompt, String instructions, Consumer<String> deltaConsumer) {
        if (helperPath == null) {
            throw unavailable("The Apple on-device helper is not available on this platform.");
        }
        ObjectNode request = MAPPER.createObjectNode();
        request.put("instructions", instructions);
        request.put("prompt", prompt);
        request.set("temperature", nullableNumber(parseTemperature(
                AiConfig.getProperty("apple.temperature", ""))));
        request.set("maxTokens", nullableNumber(parseMaxTokens(
                AiConfig.getProperty("apple.max.tokens", ""))));
        AtomicInteger lineCount = new AtomicInteger();
        AtomicReference<AppleFoundationException> failure = new AtomicReference<>();
        String input;
        try {
            input = MAPPER.writeValueAsString(request);
        } catch (Exception e) {
            throw new AppleFoundationException("Could not encode the Apple on-device request.", "other", e);
        }

        CliProcessResult result;
        try {
            result = runner.run(List.of(helperPath.toString(), "respond"), input,
                    Duration.ofSeconds(180), line -> {
                        lineCount.incrementAndGet();
                        readLine(line, deltaConsumer, failure);
                    });
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AppleFoundationException("The Apple on-device request was interrupted.", "other", e);
            }
            throw unavailable("Could not run the Apple on-device helper: " + e.getMessage());
        }
        if (result.isTimedOut()) {
            throw new AppleFoundationException("The Apple on-device request timed out. Try again.", "other");
        }
        if (lineCount.get() == 0 && !result.getStdout().isBlank()) {
            for (String line : result.getStdout().split("\\R")) {
                readLine(line, deltaConsumer, failure);
            }
        }
        AppleFoundationException helperFailure = failure.get();
        if (helperFailure != null) {
            throw helperFailure;
        }
        if (!result.isSuccess()) {
            throw new AppleFoundationException("The Apple on-device helper exited with code "
                    + result.getExitCode() + ".", "other");
        }
    }

    private static void readLine(String line, Consumer<String> deltaConsumer,
                                 AtomicReference<AppleFoundationException> failure) {
        try {
            JsonNode json = MAPPER.readTree(line);
            if (json.hasNonNull("delta")) {
                String delta = json.get("delta").asText();
                if (!delta.isEmpty()) {
                    deltaConsumer.accept(delta);
                }
            } else if (json.hasNonNull("error")) {
                String code = json.path("code").asText("other");
                failure.set(new AppleFoundationException(messageFor(code, json.get("error").asText()), code));
            }
        } catch (Exception e) {
            failure.set(new AppleFoundationException("The Apple on-device helper returned invalid output.", "other", e));
        }
    }

    private static String messageFor(String code, String helperMessage) {
        return switch (code) {
            case "context_exceeded" -> helperMessage;
            case "guardrail" -> "Apple's safety filter blocked this response.";
            case "unsupported_language" -> "Apple's on-device model does not support the language or locale in this request.";
            case "unavailable" -> helperMessage + " Requirements: macOS 26 or later, Apple silicon, and Apple Intelligence enabled in System Settings.";
            case "rate_limited" -> "Apple's on-device model is temporarily rate limited. Try again shortly.";
            default -> helperMessage == null || helperMessage.isBlank()
                    ? "The Apple on-device request failed." : helperMessage;
        };
    }

    private static AppleFoundationException unavailable(String detail) {
        return new AppleFoundationException(detail
                + " Requirements: macOS 26 or later, Apple silicon, and Apple Intelligence enabled in System Settings.",
                "unavailable");
    }

    static String buildPrompt(List<String> history) {
        if (history.isEmpty()) {
            return "User: Hello, how can you help me with JMeter?\n\n"
                    + "Answer the last user message. Reply with the answer text only.";
        }
        StringBuilder prompt = new StringBuilder();
        int offset = (history.size() - 1) % 2 == 0 ? 0 : 1;
        for (int i = 0; i < history.size(); i++) {
            boolean user = (i + offset) % 2 == 0;
            prompt.append(user ? "User: " : "Assistant: ").append(history.get(i)).append("\n\n");
        }
        return prompt.append("Answer the last user message. Reply with the answer text only.").toString();
    }

    private static String lastUserMessage(List<String> history) {
        int offset = (history.size() - 1) % 2 == 0 ? 0 : 1;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ((i + offset) % 2 == 0) {
                return history.get(i);
            }
        }
        return history.isEmpty() ? "" : history.get(history.size() - 1);
    }

    private static String systemPrompt() {
        String configured = AiConfig.getProperty("apple.system.prompt", "");
        return configured == null || configured.isBlank()
                ? Constants.DEFAULT_JMETER_SYSTEM_PROMPT : configured;
    }

    private static int maxHistorySize() {
        return parsePositiveInt(AiConfig.getProperty(MAX_HISTORY_KEY, "6"), 6);
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Double parseTemperature(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double temperature = Double.parseDouble(value.trim());
            return Double.isFinite(temperature) && temperature >= 0 && temperature <= 2
                    ? temperature : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseMaxTokens(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int maxTokens = Integer.parseInt(value.trim());
            return maxTokens > 0 ? maxTokens : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonNode nullableNumber(Number value) {
        return value == null ? MAPPER.getNodeFactory().nullNode() : MAPPER.valueToTree(value);
    }
}
