package org.qainsights.jmeter.ai.opencode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.claudecode.OpenCodeCliAdapter;
import org.qainsights.jmeter.ai.cli.CliProcessResult;
import org.qainsights.jmeter.ai.cli.CliProcessRunner;
import org.qainsights.jmeter.ai.cli.CliProviderException;
import org.qainsights.jmeter.ai.utils.AiConfig;

class OpenCodeCliProviderV2Test {

    private MockedStatic<AiConfig> config;
    private RecordingRunner runner;

    @BeforeEach
    void setUp() {
        config = mockStatic(AiConfig.class);
        config.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        runner = new RecordingRunner();
    }

    @AfterEach
    void tearDown() {
        config.close();
    }

    @Test
    void usesV1FlagsWhenAuthStatusDetectsV1() {
        runner.queue(CliProcessResult.of(0, "1.19.4"));
        runner.queue(CliProcessResult.of(0, textEvent("V1_OK")));
        OpenCodeCliProvider provider = new OpenCodeCliProvider(new StubAdapter("opencode"), runner);

        assertEquals(OpenCodeAuthStatus.READY, provider.getAuthStatus());
        assertEquals("V1_OK", provider.execute("prompt", "opencode/big-pickle"));

        List<String> command = runner.commands.get(1);
        Path workingDir = runner.workingDirectories.get(0);
        assertEquals(List.of("opencode", "run", "--pure", "--format", "json",
                "--dir", workingDir.toString(), "-m", "opencode/big-pickle"), command);
        assertTrue(runner.workingDirExisted.get(0));
        assertTrue(runner.workingDirEmpty.get(0));
        assertFalse(Files.exists(workingDir));
    }

    @Test
    void omitsV1FlagsWhenAuthStatusDetectsV2() {
        runner.queue(CliProcessResult.of(0, "opencode v2.2.6"));
        runner.queue(CliProcessResult.of(0, textEvent("V2_OK")));
        OpenCodeCliProvider provider = new OpenCodeCliProvider(new StubAdapter("opencode"), runner);

        assertEquals(OpenCodeAuthStatus.READY, provider.getAuthStatus());
        assertEquals("V2_OK", provider.execute("prompt", "opencode/big-pickle"));

        List<String> command = runner.commands.get(1);
        Path workingDir = runner.workingDirectories.get(0);
        assertEquals(List.of("opencode", "run", "--format", "json", "-m", "opencode/big-pickle"), command);
        assertTrue(runner.workingDirExisted.get(0));
        assertTrue(runner.workingDirEmpty.get(0));
        assertFalse(Files.exists(workingDir));
    }

    @Test
    void fallsBackToV2WhenCachedV1FlagsAreRejected() {
        runner.queue(CliProcessResult.of(0, "1.19.4"));
        runner.queue(new CliProcessResult(1,
                "ERRORS\n  Unrecognized flag: --pure in command opencode run\n"
                        + "  Unrecognized flag: --dir in command opencode run\n",
                "", false, 1L));
        runner.queue(CliProcessResult.of(0, textEvent("RETRIED")));
        runner.queue(CliProcessResult.of(0, textEvent("CACHED_V2")));
        OpenCodeCliProvider provider = new OpenCodeCliProvider(new StubAdapter("opencode"), runner);

        assertEquals(OpenCodeAuthStatus.READY, provider.getAuthStatus());
        assertEquals("RETRIED", provider.execute("prompt", "opencode/big-pickle"));
        assertEquals("CACHED_V2", provider.execute("prompt", "opencode/big-pickle"));

        assertTrue(runner.commands.get(1).contains("--pure"));
        assertEquals(List.of("opencode", "run", "--format", "json", "-m", "opencode/big-pickle"),
                runner.commands.get(2));
        assertEquals(List.of("opencode", "run", "--format", "json", "-m", "opencode/big-pickle"),
                runner.commands.get(3));
        assertEquals(runner.workingDirectories.get(0), runner.workingDirectories.get(1));
        assertFalse(Files.exists(runner.workingDirectories.get(0)));
        assertFalse(Files.exists(runner.workingDirectories.get(2)));
    }

    @Test
    void refreshResetsDetectedVersion() {
        runner.queue(CliProcessResult.of(0, "1.19.4"));
        runner.queue(CliProcessResult.of(0, "known/model\n"));
        runner.queue(CliProcessResult.of(0, textEvent("AFTER_REFRESH")));
        OpenCodeCliProvider provider = new OpenCodeCliProvider(new StubAdapter("opencode"), runner);

        assertEquals(OpenCodeAuthStatus.READY, provider.getAuthStatus());
        provider.refresh();
        assertEquals("AFTER_REFRESH", provider.execute("prompt"));

        assertEquals(List.of("opencode", "run", "--format", "json"), runner.commands.get(2));
    }

    @Test
    void mapsV2AuthAndFreeLimitErrors() {
        runner.queue(v2Error("provider.auth", "This API key has expired."));
        OpenCodeCliProvider provider = new OpenCodeCliProvider(new StubAdapter("opencode"), runner);

        CliProviderException authFailure = assertThrows(CliProviderException.class,
                () -> provider.execute("prompt"));
        assertEquals("OpenCode isn't signed in to that model's provider. Run 'opencode auth login' in the "
                + "AI CLI terminal tab, or pick an opencode/ free model.", authFailure.getMessage());

        runner.queue(v2Error("provider.free-limit", "Free usage limit reached."));
        CliProviderException freeLimitFailure = assertThrows(CliProviderException.class,
                () -> provider.execute("prompt"));
        assertEquals("OpenCode's free tier refused this request. Pick another free model, or add a provider "
                + "with 'opencode auth login' in the AI CLI terminal tab.", freeLimitFailure.getMessage());
    }

    @Test
    void mapsV2NoRouteAndUnknownErrors() {
        runner.queue(CliProcessResult.of(0, "known/model\n"));
        runner.queue(v2Error("provider.no-route", "Model unavailable: nope/missing-model"));
        runner.queue(v2Error("unknown", "Unexpected server error."));
        OpenCodeCliProvider provider = new OpenCodeCliProvider(new StubAdapter("opencode"), runner);
        provider.listModels();

        CliProviderException noRoute = assertThrows(CliProviderException.class,
                () -> provider.execute("prompt", "nope/missing-model"));
        assertEquals("OpenCode doesn't know the model 'nope/missing-model'. Pick one from the list or check "
                + "'opencode models'.", noRoute.getMessage());

        CliProviderException unknown = assertThrows(CliProviderException.class,
                () -> provider.execute("prompt", "nope/missing-model"));
        assertEquals("OpenCode failed: Unexpected server error. "
                + "'nope/missing-model' isn't in the 'opencode models' list, so check the model ID.",
                unknown.getMessage());
    }

    private static CliProcessResult v2Error(String type, String message) {
        String json = "{\"type\":\"error\",\"timestamp\":1,\"sessionID\":\"ses_1\","
                + "\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
        return new CliProcessResult(1, json, "", false, 1L);
    }

    private static String textEvent(String text) {
        return "{\"type\":\"step_start\"}\n{\"type\":\"text\",\"part\":{\"text\":\"" + text + "\"}}\n";
    }

    private static final class StubAdapter extends OpenCodeCliAdapter {
        private final String binary;

        StubAdapter(String binary) {
            this.binary = binary;
        }

        @Override
        public boolean detect() {
            detectedPath = binary;
            return binary != null;
        }
    }

    private static final class RecordingRunner implements CliProcessRunner {

        private final Deque<CliProcessResult> results = new ArrayDeque<>();
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Path> workingDirectories = new ArrayList<>();
        private final List<Boolean> workingDirExisted = new ArrayList<>();
        private final List<Boolean> workingDirEmpty = new ArrayList<>();

        void queue(CliProcessResult result) {
            results.add(result);
        }

        @Override
        public CliProcessResult run(List<String> command, String stdin, Duration timeout) {
            commands.add(List.copyOf(command));
            return pollResult();
        }

        @Override
        public CliProcessResult run(List<String> command, String stdin, Duration timeout,
                                    Path workingDirectory, Map<String, String> environment) {
            commands.add(List.copyOf(command));
            workingDirectories.add(workingDirectory);
            workingDirExisted.add(Files.isDirectory(workingDirectory));
            try (var children = Files.list(workingDirectory)) {
                workingDirEmpty.add(children.findAny().isEmpty());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return pollResult();
        }

        @Override
        public CliProcessResult run(List<String> command, String stdin, Duration timeout,
                                    Consumer<String> stdoutLineConsumer) {
            return run(command, stdin, timeout);
        }

        private CliProcessResult pollResult() {
            CliProcessResult result = results.poll();
            return result == null ? CliProcessResult.of(0, "") : result;
        }
    }
}
