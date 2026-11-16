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
import org.qainsights.jmeter.ai.opencode.OpenCodeCliProvider.CliGeneration;
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
    void executesWithV1FlagsWhenProbedWithoutAuthStatus() {
        runner.queue(CliProcessResult.of(0, "1.19.4"));
        runner.queue(CliProcessResult.of(0, textEvent("V1_OK")));
        OpenCodeCliProvider provider = new OpenCodeCliProvider(new StubAdapter("opencode"), runner);

        assertEquals("V1_OK", provider.execute("prompt", "opencode/big-pickle"));
        assertEquals(List.of("opencode", "--version"), runner.commands.get(0));
        Path workingDir = runner.workingDirectories.get(0);
        assertEquals(List.of("opencode", "run", "--pure", "--format", "json",
                "--dir", workingDir.toString(), "-m", "opencode/big-pickle"), runner.commands.get(1));
        assertTrue(runner.workingDirExisted.get(0));
        assertTrue(runner.workingDirEmpty.get(0));
        assertFalse(Files.exists(workingDir));
    }

    @Test
    void executesWithV2FlagsWhenProbedWithoutAuthStatus() {
        runner.queue(CliProcessResult.of(0, "opencode v2.2.6"));
        runner.queue(CliProcessResult.of(0, textEvent("V2_OK")));
        OpenCodeCliProvider provider = new OpenCodeCliProvider(new StubAdapter("opencode"), runner);

        assertEquals("V2_OK", provider.execute("prompt", "opencode/big-pickle"));
        assertEquals(List.of("opencode", "--version"), runner.commands.get(0));
        assertEquals(List.of("opencode", "run", "--format", "json", "-m", "opencode/big-pickle"),
                runner.commands.get(1));
        assertTrue(runner.workingDirExisted.get(0));
        assertTrue(runner.workingDirEmpty.get(0));
        assertFalse(Files.exists(runner.workingDirectories.get(0)));
    }

    @Test
    void authStatusCachesGenerationForSubsequentExecuteCalls() {
        runner.queue(CliProcessResult.of(0, "1.19.4"));
        runner.queue(CliProcessResult.of(0, textEvent("FIRST")));
        runner.queue(CliProcessResult.of(0, textEvent("SECOND")));
        OpenCodeCliProvider v1Provider = new OpenCodeCliProvider(new StubAdapter("opencode"), runner);

        assertEquals(OpenCodeAuthStatus.READY, v1Provider.getAuthStatus());
        assertEquals("FIRST", v1Provider.execute("p1"));
        assertEquals("SECOND", v1Provider.execute("p2"));
        assertEquals(3, runner.commands.size());
        assertTrue(runner.commands.get(1).contains("--pure"));
        assertTrue(runner.commands.get(2).contains("--pure"));

        RecordingRunner v2Runner = new RecordingRunner();
        v2Runner.queue(CliProcessResult.of(0, "opencode v2.2.6"));
        v2Runner.queue(CliProcessResult.of(0, textEvent("V2")));
        OpenCodeCliProvider v2Provider = new OpenCodeCliProvider(new StubAdapter("opencode"), v2Runner);

        assertEquals(OpenCodeAuthStatus.READY, v2Provider.getAuthStatus());
        assertEquals("V2", v2Provider.execute("p3"));
        assertEquals(2, v2Runner.commands.size());
        assertEquals(List.of("opencode", "run", "--format", "json"), v2Runner.commands.get(1));
    }

    @Test
    void refreshReprobesVersionAndUpdatesGeneration() {
        runner.queue(CliProcessResult.of(0, "1.19.4"));
        runner.queue(CliProcessResult.of(0, "1.19.4"));
        runner.queue(CliProcessResult.of(0, "known/model\n"));
        runner.queue(CliProcessResult.of(0, textEvent("STILL_V1")));
        runner.queue(CliProcessResult.of(0, "opencode v2.2.6"));
        runner.queue(CliProcessResult.of(0, "known/model\n"));
        runner.queue(CliProcessResult.of(0, textEvent("NOW_V2")));
        OpenCodeCliProvider provider = new OpenCodeCliProvider(new StubAdapter("opencode"), runner);

        assertEquals(OpenCodeAuthStatus.READY, provider.getAuthStatus());
        provider.refresh();
        assertEquals("STILL_V1", provider.execute("prompt"));
        assertTrue(runner.commands.get(3).contains("--pure"));

        provider.refresh();
        assertEquals("NOW_V2", provider.execute("prompt"));
        assertEquals(List.of("opencode", "run", "--format", "json"), runner.commands.get(6));
    }

    @Test
    void ignoresToolchainNoiseAndClearsStaleV1OnMissOrFailure() {
        assertEquals(CliGeneration.V2, OpenCodeCliProvider.parseGeneration("Bun v1.2.3\nopencode v2.2.6\n", ""));
        assertEquals(CliGeneration.V1, OpenCodeCliProvider.parseGeneration("Node.js v20.11.0\n1.19.4\n", ""));
        assertEquals(CliGeneration.V2, OpenCodeCliProvider.parseGeneration("Warning 1.0.0\n", ""));
        assertEquals(CliGeneration.V1, OpenCodeCliProvider.parseGeneration("", "v1.19.4\n"));

        runner.queue(CliProcessResult.of(0, "1.19.4"));
        runner.queue(CliProcessResult.of(0, "nightly-build"));
        runner.queue(CliProcessResult.of(0, textEvent("OVERWRITTEN")));
        runner.queue(CliProcessResult.of(1, "broken"));
        runner.queue(CliProcessResult.of(0, "opencode v2.2.6"));
        runner.queue(CliProcessResult.of(0, textEvent("REPROBED")));
        OpenCodeCliProvider provider = new OpenCodeCliProvider(new StubAdapter("opencode"), runner);

        assertEquals(OpenCodeAuthStatus.READY, provider.getAuthStatus());
        assertEquals(OpenCodeAuthStatus.READY, provider.getAuthStatus());
        assertEquals("OVERWRITTEN", provider.execute("prompt"));
        assertEquals(List.of("opencode", "run", "--format", "json"), runner.commands.get(2));
        assertEquals(OpenCodeAuthStatus.UNKNOWN, provider.getAuthStatus());
        assertEquals("REPROBED", provider.execute("prompt"));
        assertEquals(List.of("opencode", "--version"), runner.commands.get(4));
    }

    @Test
    void buildRunCommandIsPureOverGenerationArgument() {
        Path workDir = Path.of("sandbox");
        assertEquals(List.of("opencode", "run", "--pure", "--format", "json", "--dir", "sandbox", "-m", "m/id"),
                OpenCodeCliProvider.buildRunCommand("opencode", workDir, "m/id", CliGeneration.V1));
        assertEquals(List.of("opencode", "run", "--format", "json", "-m", "m/id"),
                OpenCodeCliProvider.buildRunCommand("opencode", workDir, "m/id", CliGeneration.V2));
    }

    @Test
    void mapsExactV2ErrorsWithoutFuzzySubtypeMatching() {
        runner.queue(CliProcessResult.of(0, "opencode v2.2.6"));
        runner.queue(CliProcessResult.of(0, "known/model\n"));
        runner.queue(v2Error("provider.auth", "This API key has expired."));
        runner.queue(v2Error("provider.free-limit", "Free usage limit reached."));
        runner.queue(v2Error("provider.no-route", "No provider route."));
        runner.queue(v2Error("unknown", "Unexpected server error."));
        runner.queue(v2Error("custom.provider.auth", "Custom error"));
        runner.queue(v2Error("custom.no-route", "Model unavailable right now"));
        OpenCodeCliProvider provider = new OpenCodeCliProvider(new StubAdapter("opencode"), runner);
        provider.getAuthStatus();
        provider.listModels();

        assertEquals("OpenCode isn't signed in to that model's provider. Run 'opencode auth login' in the "
                + "AI CLI terminal tab, or pick an opencode/ free model.", failure(provider, ""));
        assertEquals("OpenCode's free tier refused this request. Pick another free model, or add a provider "
                + "with 'opencode auth login' in the AI CLI terminal tab.", failure(provider, ""));
        assertEquals("OpenCode doesn't know the model 'nope/missing'. Pick one from the list or check "
                + "'opencode models'.", failure(provider, "nope/missing"));
        assertEquals("OpenCode failed: Unexpected server error. "
                + "'nope/missing' isn't in the 'opencode models' list, so check the model ID.",
                failure(provider, "nope/missing"));
        assertEquals("OpenCode failed: Custom error", failure(provider, ""));
        assertEquals("OpenCode failed: Model unavailable right now", failure(provider, "known/model"));
    }

    private static String failure(OpenCodeCliProvider provider, String model) {
        return assertThrows(CliProviderException.class,
                () -> provider.execute("prompt", model)).getMessage();
    }

    private static CliProcessResult v2Error(String type, String message) {
        return new CliProcessResult(1, "{\"type\":\"error\",\"timestamp\":1,\"sessionID\":\"ses_1\","
                + "\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}", "", false, 1L);
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
            CliProcessResult result = results.poll();
            return result == null ? CliProcessResult.of(0, "") : result;
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
            CliProcessResult result = results.poll();
            return result == null ? CliProcessResult.of(0, "") : result;
        }

        @Override
        public CliProcessResult run(List<String> command, String stdin, Duration timeout,
                                    Consumer<String> stdoutLineConsumer) {
            return run(command, stdin, timeout);
        }
    }
}
