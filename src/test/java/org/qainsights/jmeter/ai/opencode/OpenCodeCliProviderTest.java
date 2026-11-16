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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.claudecode.OpenCodeCliAdapter;
import org.qainsights.jmeter.ai.cli.CliProcessResult;
import org.qainsights.jmeter.ai.cli.CliProcessRunner;
import org.qainsights.jmeter.ai.cli.CliProviderException;
import org.qainsights.jmeter.ai.utils.AiConfig;

class OpenCodeCliProviderTest {

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
    void exposesProviderConfigurationAndDoesNotOfferInteractiveSignIn() {
        OpenCodeCliProvider provider = provider("opencode");

        assertEquals("OpenCode", provider.displayName());
        assertEquals("opencode:", provider.modelPrefix());
        assertEquals("Add providers in OpenCode", provider.signInActionLabel());
        assertEquals("Add providers with 'opencode auth login' in the AI CLI terminal tab.",
                provider.signInHint());
        assertTrue(provider.installHint().contains("npm install -g opencode-ai"));
        assertTrue(provider.installHint().contains("free models work without signing in"));
        assertFalse(provider.managesSignIn());
        assertFalse(provider.isEnabled());
    }

    @Test
    void fixedExecutableOverrideTakesPrecedenceOverTheProperty() {
        config.when(() -> AiConfig.getProperty(OpenCodeCliProvider.EXECUTABLE_KEY, ""))
                .thenReturn("/configured/opencode");

        assertEquals("/fixed/opencode",
                OpenCodeCliProvider.forExecutable(" /fixed/opencode ").executable());
    }

    @Test
    void blankExecutableOverrideUsesPathDetectionInsteadOfTheConfiguredProperty() {
        config.when(() -> AiConfig.getProperty(OpenCodeCliProvider.EXECUTABLE_KEY, ""))
                .thenReturn("/configured/opencode");
        FakeAdapter adapter = new FakeAdapter("/path/opencode");

        OpenCodeCliProvider provider = OpenCodeCliProvider.forExecutable("", adapter, runner);

        assertEquals("/path/opencode", provider.executable());
        assertEquals(1, adapter.detections);
    }

    @Test
    void noArgumentConstructorStillUsesTheConfiguredExecutableProperty() {
        config.when(() -> AiConfig.getProperty(OpenCodeCliProvider.EXECUTABLE_KEY, ""))
                .thenReturn("/configured/opencode");

        assertEquals("/configured/opencode", new OpenCodeCliProvider().executable());
    }

    @Test
    void featuredModelIdsUseDefaultThenConfiguredOrderWhenEnabled() {
        config.when(() -> AiConfig.getProperty(OpenCodeCliProvider.ENABLED_KEY, "false"))
                .thenReturn("true");
        config.when(() -> AiConfig.getProperty(OpenCodeCliProvider.MODELS_KEY, ""))
                .thenReturn("opencode/nemotron-3.5-lightning-free,opencode/big-pickle");

        assertEquals(List.of("opencode:default",
                        "opencode:opencode/nemotron-3.5-lightning-free",
                        "opencode:opencode/big-pickle"),
                provider("opencode").featuredModelIds());
    }

    @Test
    void featuredModelIdsAreEmptyWhenDisabled() {
        config.when(() -> AiConfig.getProperty(OpenCodeCliProvider.MODELS_KEY, ""))
                .thenReturn("opencode/nemotron-3.5-lightning-free,opencode/big-pickle");

        assertEquals(List.of(), provider("opencode").featuredModelIds());
    }

    @Test
    void checksOnlyTheCliVersionForAuthStatus() {
        runner.queue(CliProcessResult.of(0, "1.19.4"));

        assertEquals(OpenCodeAuthStatus.READY, provider("opencode").getAuthStatus());
        assertEquals(List.of("opencode", "--version"), runner.commands.get(0));
        assertEquals(Duration.ofSeconds(30), runner.timeouts.get(0));
    }

    @Test
    void nonzeroTimeoutAndExceptionsProduceUnknownStatus() {
        runner.queue(CliProcessResult.of(1, "version failure"));
        assertEquals(OpenCodeAuthStatus.UNKNOWN, provider("opencode").getAuthStatus());

        runner.queue(CliProcessResult.timeout(30_000L));
        assertEquals(OpenCodeAuthStatus.UNKNOWN, provider("opencode").getAuthStatus());

        runner.failNext(new IllegalStateException("runner failure"));
        assertEquals(OpenCodeAuthStatus.UNKNOWN, provider("opencode").getAuthStatus());
    }

    @Test
    void missingCliDoesNotRunAProcessAndLoginLogoutAreNoOps() {
        OpenCodeCliProvider provider = provider(null);

        assertFalse(provider.isInstalled());
        assertEquals(OpenCodeAuthStatus.CLI_NOT_INSTALLED, provider.getAuthStatus());
        assertEquals(OpenCodeAuthStatus.CLI_NOT_INSTALLED, provider.login());
        assertEquals(OpenCodeAuthStatus.CLI_NOT_INSTALLED, provider.logout());
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    void loginAndLogoutOnlyRecheckTheCliVersion() {
        runner.queue(CliProcessResult.of(0, "1.19.4"));
        runner.queue(CliProcessResult.of(0, "1.19.4"));
        OpenCodeCliProvider provider = provider("opencode");

        assertEquals(OpenCodeAuthStatus.READY, provider.login());
        assertEquals(OpenCodeAuthStatus.READY, provider.logout());
        assertEquals(List.of(
                List.of("opencode", "--version"),
                List.of("opencode", "--version")), runner.commands);
    }

    @Test
    void runsWithJsonFormatInAnExistingEmptyTemporaryDirectory() throws IOException {
        runner.queue(CliProcessResult.of(0, textEvent("PONG")));
        OpenCodeCliProvider provider = provider("opencode");

        assertEquals("PONG", provider.execute("Reply with PONG.", "opencode/big-pickle"));

        List<String> command = runner.commands.get(0);
        assertEquals(List.of("opencode", "run", "--format", "json", "-m", "opencode/big-pickle"), command);
        assertFalse(command.contains("--pure"));
        assertFalse(command.contains("--dir"));
        Path directory = runner.workingDirectories.get(0);
        assertTrue(runner.tempDirectoryExistedWhenRun);
        assertTrue(runner.tempDirectoryEmptyWhenRun);
        assertTrue(directory.getFileName().toString().startsWith("feather-wand-opencode"));
        assertFalse(command.contains("Reply with PONG."));
        assertEquals("Reply with PONG.", runner.stdins.get(0));
        assertEquals(Map.of("OPENCODE_CONFIG_CONTENT", OpenCodeCliProvider.SAFE_CONFIG),
                runner.environments.get(0));
        assertFalse(Files.exists(directory), "temporary working directory is deleted after the run");
    }

    @Test
    void omitsTheModelFlagForTheDefaultAndNormalizesTheSelectedModel() {
        runner.queue(CliProcessResult.of(0, textEvent("ok")));
        OpenCodeCliProvider provider = provider("opencode");
        provider.setModel(OpenCodeCliProvider.DEFAULT_MODEL);

        assertEquals("", provider.getModel());
        assertEquals("ok", provider.execute("hello"));
        assertFalse(runner.commands.get(0).contains("-m"));

        runner.queue(CliProcessResult.of(0, textEvent("ok")));
        assertEquals("ok", provider.execute("hello", "default"));
        assertFalse(runner.commands.get(1).contains("-m"));
    }

    @Test
    void keepsOnlyTextFromTheFinalStepAndSkipsNonJsonOutput() {
        String output = """
                startup noise
                {"type":"step_start"}
                {"type":"text","part":{"text":"discarded"}}
                {"type":"step_finish"}
                {"type":"step_start"}
                {"type":"text","part":{"text":" final"}}
                {"type":"text","part":{"text":" answer "}}
                {"type":"step_finish"}
                trailing noise
                """;
        runner.queue(CliProcessResult.of(0, output));

        assertEquals("final answer", provider("opencode").execute("prompt"));
    }

    @Test
    void mapsFreeTierErrors() {
        runner.queue(errorResult("FreeTierError", "Free tier request was refused"));
        assertEquals("OpenCode's free tier refused this request. Pick another free model, or add a provider "
                + "with 'opencode auth login' in the AI CLI terminal tab.", executeFailure(""));
    }

    @Test
    void mapsAuthenticationErrors() {
        runner.queue(errorResult("ProviderAuthError", "HTTP 401: Unauthorized API key; not authenticated"));
        assertEquals("OpenCode isn't signed in to that model's provider. Run 'opencode auth login' in the "
                + "AI CLI terminal tab, or pick an opencode/ free model.", executeFailure(""));
    }

    @Test
    void mapsModelNotFoundErrorsUsingTheRequestedModel() {
        runner.queue(errorResult("ProviderModelNotFoundError", "provider rejected model"));
        assertEquals("OpenCode doesn't know the model 'example/model'. Pick one from the list or check "
                + "'opencode models'.", executeFailure("example/model"));
    }

    @Test
    void mapsModelNotFoundMessages() {
        runner.queue(errorResult("ProviderError", "The model not found in this provider"));
        String message = executeFailure("example/model");
        assertTrue(message.contains("doesn't know the model 'example/model'"), message);
    }

    @Test
    void preservesUnknownErrorMessageAndHintsWhenModelIsAbsentFromDiscoveredModels() {
        runner.queue(CliProcessResult.of(0, "known/model\n"));
        runner.queue(errorResult("UnknownError", "Unexpected server error. Check server logs for details."));
        OpenCodeCliProvider provider = provider("opencode");
        provider.listModels();

        assertEquals("OpenCode failed: Unexpected server error. Check server logs for details. "
                + "'nope/missing-model' isn't in the 'opencode models' list, so check the model ID.",
                executeFailure(provider, "nope/missing-model"));
    }

    @Test
    void keepsUnknownErrorGenericWhenModelIsDiscovered() {
        runner.queue(CliProcessResult.of(0, "known/model\n"));
        runner.queue(errorResult("UnknownError", "Unexpected server error. Check server logs for details."));
        OpenCodeCliProvider provider = provider("opencode");
        provider.listModels();

        assertEquals("OpenCode failed: Unexpected server error. Check server logs for details.",
                executeFailure(provider, "known/model"));
    }

    @Test
    void keepsUnknownErrorGenericWhenModelsHaveNotBeenDiscovered() {
        runner.queue(errorResult("UnknownError", "Unexpected server error. Check server logs for details."));
        assertEquals("OpenCode failed: Unexpected server error. Check server logs for details.",
                executeFailure("nope/missing-model"));
    }

    @Test
    void mapsOtherJsonErrorsToTheFirstThreeMessageLines() {
        runner.queue(errorResult("ProviderError", "line one\nline two\nline three\nline four"));
        assertEquals("OpenCode failed: line one line two line three", executeFailure(""));
    }

    @Test
    void fallsBackToExitCodeAndStderrWhenNoJsonErrorIsAvailable() {
        runner.queue(new CliProcessResult(7, "not-json", "line one\nline two\nline three\nline four", false, 1L));
        assertEquals("OpenCode failed: exited with code 7. Details: line one line two line three",
                executeFailure(""));
    }

    @Test
    void reportsTimeoutAndEmptyResponses() {
        runner.queue(CliProcessResult.timeout(180_000L));
        assertEquals("OpenCode did not respond within 180 seconds and was stopped. Increase "
                + OpenCodeCliProvider.TIMEOUT_KEY + " if your prompts need longer.", executeFailure(""));

        runner.queue(CliProcessResult.of(0, "{\"type\":\"step_start\"}\n"));
        assertEquals("OpenCode returned an empty response. Try rephrasing your request.", executeFailure(""));
    }

    @Test
    void rejectsAnEmptyPromptWithoutStartingTheCli() {
        CliProviderException failure = assertThrows(CliProviderException.class,
                () -> provider("opencode").execute(" \t "));

        assertTrue(failure.getMessage().contains("prompt is empty"), failure.getMessage());
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    void mergesConfiguredAndDiscoveredModelsInOrder() {
        config.when(() -> AiConfig.getProperty(OpenCodeCliProvider.MODELS_KEY, ""))
                .thenReturn("custom/first, opencode/custom, custom/first");
        runner.queue(CliProcessResult.of(0, """
                opencode/free-model
                invalid model
                custom/second
                custom/second
                provider/model with spaces
                """));

        assertEquals(List.of("default", "custom/first", "opencode/custom",
                "opencode/free-model", "custom/second"), provider("opencode").listModels());
        assertEquals(List.of("opencode", "models"), runner.commands.get(0));
        assertEquals(Duration.ofSeconds(60), runner.timeouts.get(0));
    }

    @Test
    void failedDiscoveryIsNotCached() {
        runner.queue(CliProcessResult.of(1, "unavailable"));
        runner.queue(CliProcessResult.of(0, "provider/model"));
        OpenCodeCliProvider provider = provider("opencode");

        assertEquals(List.of("default"), provider.listModels());
        assertEquals(List.of("default", "provider/model"), provider.listModels());
        assertEquals(2, runner.commands.size());
    }

    @Test
    void modelListingOnTheEdtDoesNotDiscoverTheExecutableOrRunAProcess() throws Exception {
        FakeAdapter adapter = new FakeAdapter("opencode");
        OpenCodeCliProvider provider = new OpenCodeCliProvider(adapter, runner);
        AtomicReference<List<String>> models = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> models.set(provider.listModels()));

        assertEquals(List.of("default"), models.get());
        assertEquals(0, adapter.detections);
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    void refreshClearsCachesAndRediscoversModelsSynchronously() {
        runner.queue(CliProcessResult.of(0, "provider/first"));
        runner.queue(CliProcessResult.of(0, "provider/second"));
        FakeAdapter adapter = new FakeAdapter("opencode");
        OpenCodeCliProvider provider = new OpenCodeCliProvider(adapter, runner);

        assertEquals(List.of("default", "provider/first"), provider.listModels());
        provider.refresh();

        assertEquals(List.of("default", "provider/second"), provider.listModels());
        assertEquals(2, runner.commands.size());
        assertEquals(2, adapter.detections);
    }

    @Test
    void refreshDoesNotStartModelDiscoveryWhenTheCliIsMissing() {
        OpenCodeCliProvider provider = provider(null);

        provider.refresh();

        assertTrue(runner.commands.isEmpty());
    }

    private OpenCodeCliProvider provider(String binary) {
        return new OpenCodeCliProvider(new FakeAdapter(binary), runner);
    }

    private String executeFailure(String model) {
        return executeFailure(provider("opencode"), model);
    }

    private String executeFailure(OpenCodeCliProvider provider, String model) {
        return assertThrows(CliProviderException.class, () -> {
            if (model.isEmpty()) {
                provider.execute("prompt");
            } else {
                provider.execute("prompt", model);
            }
        }).getMessage();
    }

    private static CliProcessResult errorResult(String name, String message) {
        String json = "{\"type\":\"error\",\"error\":{\"name\":\"" + name
                + "\",\"data\":{\"message\":\"" + message.replace("\\", "\\\\")
                        .replace("\"", "\\\"").replace("\n", "\\n") + "\"}}}";
        return new CliProcessResult(1, json, "", false, 1L);
    }

    private static String textEvent(String text) {
        return "{\"type\":\"step_start\"}\n"
                + "{\"type\":\"text\",\"part\":{\"text\":\"" + text + "\"}}\n";
    }

    private static final class FakeAdapter extends OpenCodeCliAdapter {

        private final String binary;
        private int detections;

        FakeAdapter(String binary) {
            this.binary = binary;
        }

        @Override
        public boolean detect() {
            detections++;
            detectedPath = binary;
            return binary != null;
        }
    }

    private static final class RecordingRunner implements CliProcessRunner {

        private final Deque<CliProcessResult> results = new ArrayDeque<>();
        private final List<List<String>> commands = new ArrayList<>();
        private final List<String> stdins = new ArrayList<>();
        private final List<Duration> timeouts = new ArrayList<>();
        private final List<Path> workingDirectories = new ArrayList<>();
        private final List<Map<String, String>> environments = new ArrayList<>();
        private RuntimeException nextFailure;
        private boolean tempDirectoryExistedWhenRun;
        private boolean tempDirectoryEmptyWhenRun;

        void queue(CliProcessResult result) {
            results.add(result);
        }

        void failNext(RuntimeException failure) {
            nextFailure = failure;
        }

        @Override
        public CliProcessResult run(List<String> command, String stdin, Duration timeout) {
            return record(command, stdin, timeout, null);
        }

        @Override
        public CliProcessResult run(List<String> command, String stdin, Duration timeout,
                                   Map<String, String> environment) {
            environments.add(Map.copyOf(environment));
            return record(command, stdin, timeout, null);
        }

        @Override
        public CliProcessResult run(List<String> command, String stdin, Duration timeout,
                                    Path workingDirectory, Map<String, String> environment) {
            workingDirectories.add(workingDirectory);
            environments.add(Map.copyOf(environment));
            return record(command, stdin, timeout, workingDirectory);
        }

        @Override
        public CliProcessResult run(List<String> command, String stdin, Duration timeout,
                                   Consumer<String> stdoutLineConsumer) {
            return run(command, stdin, timeout);
        }

        private CliProcessResult record(
                List<String> command, String stdin, Duration timeout, Path workingDirectory) {
            commands.add(List.copyOf(command));
            stdins.add(stdin);
            timeouts.add(timeout);
            Path directory = workingDirectory;
            if (directory == null) {
                int directoryFlag = command.indexOf("--dir");
                if (directoryFlag >= 0) {
                    directory = Path.of(command.get(directoryFlag + 1));
                }
            }
            if (directory != null) {
                tempDirectoryExistedWhenRun = Files.isDirectory(directory);
                try (var children = Files.list(directory)) {
                    tempDirectoryEmptyWhenRun = children.findAny().isEmpty();
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
            if (nextFailure != null) {
                RuntimeException failure = nextFailure;
                nextFailure = null;
                throw failure;
            }
            CliProcessResult result = results.poll();
            return result == null ? CliProcessResult.of(0, "") : result;
        }
    }
}
