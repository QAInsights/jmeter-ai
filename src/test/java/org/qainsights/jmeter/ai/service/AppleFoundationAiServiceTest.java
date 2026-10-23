package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.cli.CliProcessResult;
import org.qainsights.jmeter.ai.cli.CliProcessRunner;
import org.qainsights.jmeter.ai.utils.ProviderErrors;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AppleFoundationAiServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static java.util.Properties properties;

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        properties = JMeterUtils.getJMeterProperties();
    }

    @BeforeEach
    void clearAppleProperties() {
        properties.remove("apple.system.prompt");
        properties.remove("apple.max.history.size");
        properties.remove("apple.temperature");
        properties.remove("apple.max.tokens");
    }

    @AfterAll
    static void clearProperties() {
        properties.remove("apple.system.prompt");
        properties.remove("apple.max.history.size");
        properties.remove("apple.temperature");
        properties.remove("apple.max.tokens");
    }

    @Test
    void streamAssemblesDeltasInOrderAndCompletes() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(script(0, "{\"delta\":\"Hello\"}", "{\"delta\":\" world\"}",
                "{\"done\":true}"));
        AppleFoundationAiService service = service(runner);
        StringBuilder result = new StringBuilder();
        CountDownLatch complete = new CountDownLatch(1);
        CountDownLatch error = new CountDownLatch(1);

        service.generateStreamResponse(List.of("Hi"), "on-device", result::append,
                complete::countDown, e -> error.countDown());

        assertTrue(complete.await(5, TimeUnit.SECONDS));
        assertEquals(1, error.getCount());
        assertEquals("Hello world", result.toString());
        assertEquals("Apple on-device", service.getName());
    }

    @Test
    void nonStreamingResponseConcatenatesDeltas() {
        ScriptedRunner runner = new ScriptedRunner(script(0, "{\"delta\":\"one\"}",
                "{\"delta\":\" two\"}", "{\"done\":true}"));
        assertEquals("one two", service(runner).generateResponse(List.of("question")));
    }

    @Test
    void streamingHelperErrorCallsOnErrorWithoutCompleting() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(
                script(1, "{\"error\":\"filtered\",\"code\":\"guardrail\"}"));
        CountDownLatch errorLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(1);
        List<Exception> errors = new ArrayList<>();

        service(runner).generateStreamResponse(List.of("question"), "on-device", ignored -> {},
                completeLatch::countDown, error -> {
                    errors.add(error);
                    errorLatch.countDown();
                });

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, completeLatch.getCount());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).getMessage().contains("safety filter"));
    }

    @Test
    void mapsHelperErrorCodesToUserMessages() {
        assertError("guardrail", "blocked", "safety filter");
        assertError("unsupported_language", "unsupported", "does not support the language");
        assertError("unavailable", "not ready", "macOS 26");
        assertError("rate_limited", "busy", "rate limited");
        assertError("other", "unknown error", "unknown error");
    }

    @Test
    void contextExceededRetriesWithOnlyLastUserMessage() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(
                script(1, "{\"error\":\"too long\",\"code\":\"context_exceeded\"}"),
                script(0, "{\"delta\":\"ok\"}", "{\"done\":true}"));

        assertEquals("ok", service(runner).generateResponse(List.of(
                "first question", "first answer", "last question")));

        assertEquals(2, runner.requests.size());
        String retryPrompt = runner.requests.get(1).path("prompt").asText();
        assertTrue(retryPrompt.contains("last question"));
        assertFalse(retryPrompt.contains("first question"));
        assertFalse(retryPrompt.contains("first answer"));
    }

    @Test
    void contextExceededAfterPartialOutputDoesNotRetry() {
        ScriptedRunner runner = new ScriptedRunner(script(1, "{\"delta\":\"partial\"}",
                "{\"error\":\"fixture context overflow.\",\"code\":\"context_exceeded\"}"));

        AppleFoundationException error = assertThrows(AppleFoundationException.class,
                () -> service(runner).generateResponse(List.of("question")));

        assertTrue(error.getMessage().contains("too long"));
        assertTrue(error.getMessage().contains("Start a New conversation"));
        String described = ProviderErrors.describe(error);
        assertTrue(described.contains("Start a New conversation"));
        assertFalse(described.contains("fixture context overflow."));
        assertEquals(1, runner.requests.size());
    }

    @Test
    void repeatedContextExceededAsksUserToStartNewConversation() {
        ScriptedRunner runner = new ScriptedRunner(
                script(1, "{\"error\":\"too long\",\"code\":\"context_exceeded\"}"),
                script(1, "{\"error\":\"fixture context overflow.\",\"code\":\"context_exceeded\"}"));

        AppleFoundationException error = assertThrows(AppleFoundationException.class,
                () -> service(runner).generateResponse(List.of("question")));
        assertTrue(error.getMessage().contains("Start a New conversation"));
        String described = ProviderErrors.describe(error);
        assertTrue(described.contains("Start a New conversation"));
        assertFalse(described.contains("fixture context overflow."));
        assertEquals(2, runner.requests.size());
    }

    @Test
    void promptUsesHistoryWindowMasksFailedRepliesAndKeepsInstructionsSeparate() {
        properties.setProperty("apple.max.history.size", "3");
        properties.setProperty("apple.system.prompt", "Private system instructions");
        properties.setProperty("apple.temperature", "0.7");
        properties.setProperty("apple.max.tokens", "128");
        ScriptedRunner runner = new ScriptedRunner(script(0, "{\"delta\":\"ok\"}", "{\"done\":true}"));

        service(runner).generateResponse(List.of("old user", "Error: failed reply", "middle user",
                "middle assistant", "recent user", "recent assistant", "last user"));

        JsonNode request = runner.requests.get(0);
        String prompt = request.path("prompt").asText();
        assertTrue(prompt.contains("User: recent user"));
        assertTrue(prompt.contains("Assistant: recent assistant"));
        assertTrue(prompt.contains("User: last user"));
        assertFalse(prompt.contains("old user"));
        assertFalse(prompt.contains("Private system instructions"));
        assertEquals("Private system instructions", request.path("instructions").asText());
        assertEquals(0.7, request.path("temperature").asDouble());
        assertEquals(128, request.path("maxTokens").asInt());

        properties.setProperty("apple.max.history.size", "6");
        runner = new ScriptedRunner(script(0, "{\"delta\":\"ok\"}", "{\"done\":true}"));
        service(runner).generateResponse(List.of("question", "Error: failed reply", "follow-up"));
        assertTrue(runner.requests.get(0).path("prompt").asText()
                .contains(HistoryWindow.FAILED_REPLY));
    }

    @Test
    void cancellationSuppressesSubsequentTokensAndCallbacks() throws Exception {
        CountDownLatch firstToken = new CountDownLatch(1);
        CountDownLatch secondToken = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        ScriptedRunner runner = new ScriptedRunner(new Script(0,
                List.of("{\"delta\":\"first\"}", "{\"delta\":\"second\"}", "{\"done\":true}"),
                firstToken, secondToken));
        AppleFoundationAiService service = service(runner);
        List<String> tokens = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();

        Runnable cancel = service.generateStreamResponse(List.of("question"), "on-device", tokens::add,
                completed::countDown, errors::add);

        assertTrue(firstToken.await(5, TimeUnit.SECONDS));
        cancel.run();
        assertTrue(secondToken.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("first"), tokens);
        assertTrue(errors.isEmpty());
        assertEquals(1, completed.getCount());
    }

    private static void assertError(String code, String errorText, String expectedMessage) {
        ScriptedRunner runner = new ScriptedRunner(script(1, "{\"error\":\"" + errorText
                + "\",\"code\":\"" + code + "\"}"));
        AppleFoundationException error = assertThrows(AppleFoundationException.class,
                () -> service(runner).generateResponse(List.of("question")));
        assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }

    private static AppleFoundationAiService service(ScriptedRunner runner) {
        return new AppleFoundationAiService(runner, Path.of("fake-helper"));
    }

    private static Script script(int exitCode, String... lines) {
        return new Script(exitCode, List.of(lines), null, null);
    }

    private static final class Script {
        private final int exitCode;
        private final List<String> lines;
        private final CountDownLatch firstLine;
        private final CountDownLatch secondLine;

        private Script(int exitCode, List<String> lines, CountDownLatch firstLine,
                       CountDownLatch secondLine) {
            this.exitCode = exitCode;
            this.lines = lines;
            this.firstLine = firstLine;
            this.secondLine = secondLine;
        }
    }

    private static final class ScriptedRunner implements CliProcessRunner {
        private final Deque<Script> scripts = new ArrayDeque<>();
        private final List<JsonNode> requests = new ArrayList<>();

        private ScriptedRunner(Script... scripts) {
            this.scripts.addAll(List.of(scripts));
        }

        @Override
        public CliProcessResult run(List<String> command, String stdin, Duration timeout) {
            return run(command, stdin, timeout, ignored -> {});
        }

        @Override
        public CliProcessResult run(List<String> command, String stdin, Duration timeout,
                                    Consumer<String> stdoutLineConsumer) {
            try {
                requests.add(MAPPER.readTree(stdin));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            Script script = scripts.removeFirst();
            for (int i = 0; i < script.lines.size(); i++) {
                stdoutLineConsumer.accept(script.lines.get(i));
                if (i == 0 && script.firstLine != null) {
                    script.firstLine.countDown();
                    try {
                        script.secondLine.await();
                    } catch (InterruptedException ignored) {
                        // The cancellation test intentionally lets the fake emit a late line.
                    }
                }
                if (i == 1 && script.secondLine != null) {
                    script.secondLine.countDown();
                }
            }
            return CliProcessResult.of(script.exitCode, String.join("\n", script.lines));
        }
    }
}
