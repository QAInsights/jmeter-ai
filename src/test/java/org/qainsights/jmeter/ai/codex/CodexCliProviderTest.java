package org.qainsights.jmeter.ai.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.claudecode.OpenAiCodexCliAdapter;
import org.qainsights.jmeter.ai.cli.CliProcessResult;
import org.qainsights.jmeter.ai.cli.CliProcessRunner;
import org.qainsights.jmeter.ai.cli.CliProviderException;
import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Behaviour of {@link CodexCliProvider} with the CLI faked out: detection,
 * status parsing, the mandatory status re-read after login/logout, and the
 * user-facing errors for failed or timed-out runs.
 */
class CodexCliProviderTest {

    private MockedStatic<AiConfig> config;
    private RecordingRunner runner;

    @BeforeEach
    void setUp() {
        config = mockStatic(AiConfig.class);
        // every property falls back to its default, as on a fresh install
        config.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        runner = new RecordingRunner();
    }

    @AfterEach
    void tearDown() {
        config.close();
    }

    @Test
    void reportsInstalledWhenTheAdapterFindsTheBinary() {
        assertTrue(provider("/usr/local/bin/codex").isInstalled());
    }

    @Test
    void reportsNotInstalledWhenTheBinaryIsMissing() {
        CodexCliProvider provider = provider(null);
        assertFalse(provider.isInstalled());
        assertEquals(CodexAuthStatus.CODEX_NOT_INSTALLED, provider.getAuthStatus());
    }

    @Test
    void readsTheStatusFromTheCli() {
        runner.queue(CliProcessResult.of(0, "Logged in using ChatGPT"));
        assertEquals(CodexAuthStatus.CHATGPT, provider("codex").getAuthStatus());
        assertEquals(List.of("codex", "login", "status"), runner.commands.get(0));
    }

    @Test
    void statusTimeoutDoesNotClaimAuthentication() {
        runner.queue(CliProcessResult.timeout(30_000L));
        assertEquals(CodexAuthStatus.UNKNOWN, provider("codex").getAuthStatus());
    }

    @Test
    void loginIsVerifiedWithAFreshStatusRead() {
        runner.queue(CliProcessResult.of(0, "")); // codex login
        runner.queue(CliProcessResult.of(0, "Logged in using ChatGPT"));
        assertEquals(CodexAuthStatus.CHATGPT, provider("codex").login());
        assertEquals(List.of("codex", "login"), runner.commands.get(0));
        assertEquals(List.of("codex", "login", "status"), runner.commands.get(1));
    }

    @Test
    void aSuccessfulLoginExitCodeAloneIsNotTrusted() {
        runner.queue(CliProcessResult.of(0, "")); // codex login "succeeded"
        runner.queue(CliProcessResult.of(1, "Not logged in"));
        assertEquals(CodexAuthStatus.NOT_LOGGED_IN, provider("codex").login());
    }

    @Test
    void logoutIsVerifiedWithAFreshStatusRead() {
        runner.queue(CliProcessResult.of(0, ""));
        runner.queue(CliProcessResult.of(1, "Not logged in"));
        assertEquals(CodexAuthStatus.NOT_LOGGED_IN, provider("codex").logout());
        assertEquals(List.of("codex", "logout"), runner.commands.get(0));
    }

    @Test
    void executeReturnsTheCliAnswer() {
        runner.queue(CliProcessResult.of(0, "  Use a Constant Timer.  "));
        assertEquals("Use a Constant Timer.", provider("codex").execute("How do I pace requests?"));
        List<String> command = runner.commands.get(0);
        assertEquals("codex", command.get(0));
        assertEquals("exec", command.get(1));
        assertTrue(command.contains("--sandbox"));
        assertEquals("-", command.get(command.size() - 1));
        // the prompt travels on stdin, never on the command line
        assertEquals("How do I pace requests?", runner.stdins.get(0));
        assertFalse(command.contains("How do I pace requests?"));
    }

    @Test
    void executePassesTheSelectedModel() {
        CodexCliProvider provider = provider("codex");
        provider.setModel("gpt-5-codex");
        runner.queue(CliProcessResult.of(0, "ok"));
        provider.execute("hi");
        List<String> command = runner.commands.get(0);
        assertEquals("gpt-5-codex", command.get(command.indexOf("--model") + 1));
    }

    @Test
    void theDefaultSelectorEntryLeavesTheModelToTheCli() {
        CodexCliProvider provider = provider("codex");
        provider.setModel(CodexCliProvider.DEFAULT_MODEL);
        runner.queue(CliProcessResult.of(0, "ok"));
        provider.execute("hi");
        assertFalse(runner.commands.get(0).contains("--model"));
    }

    @Test
    void anUnauthenticatedRunExplainsHowToSignIn() {
        runner.queue(new CliProcessResult(1, "", "Not logged in", false, 10L));
        CliProviderException failure = assertThrows(CliProviderException.class,
                () -> provider("codex").execute("hi"));
        assertTrue(failure.getMessage().contains("not signed in"), failure.getMessage());
    }

    @Test
    void anOutdatedCliIsReportedAsSuch() {
        runner.queue(new CliProcessResult(2, "", "error: unexpected argument '--output-last-message'", false, 5L));
        CliProviderException failure = assertThrows(CliProviderException.class,
                () -> provider("codex").execute("hi"));
        assertTrue(failure.getMessage().contains("does not support"), failure.getMessage());
    }

    @Test
    void aTimedOutRunIsReportedWithoutAStackTrace() {
        runner.queue(CliProcessResult.timeout(120_000L));
        CliProviderException failure = assertThrows(CliProviderException.class,
                () -> provider("codex").execute("hi"));
        assertTrue(failure.getMessage().contains("did not respond"), failure.getMessage());
        assertTrue(failure.getMessage().contains(CodexCliProvider.TIMEOUT_KEY), failure.getMessage());
    }

    @Test
    void anEmptyAnswerIsReportedInsteadOfReturningNothing() {
        runner.queue(CliProcessResult.of(0, "   "));
        assertThrows(CliProviderException.class, () -> provider("codex").execute("hi"));
    }

    @Test
    void anEmptyPromptNeverReachesTheCli() {
        assertThrows(CliProviderException.class, () -> provider("codex").execute("  "));
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    void modelListStartsWithTheCliDefault() {
        assertEquals(List.of(CodexCliProvider.DEFAULT_MODEL), provider("codex").listModels());
    }

    private CodexCliProvider provider(String binary) {
        return new CodexCliProvider(new FakeAdapter(binary), runner);
    }

    /** Adapter stand-in: reports the binary the test wants (or none at all). */
    private static final class FakeAdapter extends OpenAiCodexCliAdapter {

        private final String binary;

        FakeAdapter(String binary) {
            this.binary = binary;
        }

        @Override
        public boolean detect() {
            detectedPath = binary;
            return binary != null;
        }
    }

    /** Records the commands it is asked to run and replays queued results. */
    private static final class RecordingRunner implements CliProcessRunner {

        private final Deque<CliProcessResult> results = new ArrayDeque<>();
        private final List<List<String>> commands = new ArrayList<>();
        private final List<String> stdins = new ArrayList<>();

        void queue(CliProcessResult result) {
            results.add(result);
        }

        @Override
        public CliProcessResult run(List<String> command, String stdin, Duration timeout) {
            commands.add(List.copyOf(command));
            stdins.add(stdin);
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
