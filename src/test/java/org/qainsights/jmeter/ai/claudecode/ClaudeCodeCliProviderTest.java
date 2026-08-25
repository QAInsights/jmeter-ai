package org.qainsights.jmeter.ai.claudecode;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.cli.CliProcessResult;
import org.qainsights.jmeter.ai.cli.CliProcessRunner;
import org.qainsights.jmeter.ai.cli.CliProviderException;
import org.qainsights.jmeter.ai.utils.AiConfig;

/** Behaviour of {@link ClaudeCodeCliProvider} with the CLI faked out. */
class ClaudeCodeCliProviderTest {

    private static final String SIGNED_IN =
            "{\"loggedIn\":true,\"authMethod\":\"claudeai\",\"apiProvider\":\"firstParty\"}";
    private static final String SIGNED_OUT =
            "{\"loggedIn\":false,\"authMethod\":\"none\",\"apiProvider\":\"firstParty\"}";

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
    void reportsNotInstalledWhenTheBinaryIsMissing() {
        ClaudeCodeCliProvider provider = provider(null);
        assertFalse(provider.isInstalled());
        assertEquals(ClaudeCodeAuthStatus.CLI_NOT_INSTALLED, provider.getAuthStatus());
    }

    @Test
    void concurrentDetectionWaitsForTheSharedResult() throws Exception {
        BlockingAdapter adapter = new BlockingAdapter("claude");
        ClaudeCodeCliProvider provider = new ClaudeCodeCliProvider(adapter, runner);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(provider::executable);
            assertTrue(adapter.started.await(2, TimeUnit.SECONDS));
            Future<String> second = executor.submit(provider::executable);
            try {
                assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            } finally {
                adapter.release.countDown();
            }
            assertEquals("claude", first.get(2, TimeUnit.SECONDS));
            assertEquals("claude", second.get(2, TimeUnit.SECONDS));
        } finally {
            adapter.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void refreshRedetectsANewlyInstalledBinary() {
        FakeAdapter adapter = new FakeAdapter(null);
        ClaudeCodeCliProvider provider = new ClaudeCodeCliProvider(adapter, runner);
        assertFalse(provider.isInstalled());

        adapter.setBinary("claude");
        provider.refresh();

        assertTrue(provider.isInstalled());
    }

    @Test
    void readsTheStatusFromTheCli() {
        runner.queue(CliProcessResult.of(0, SIGNED_IN));
        assertEquals(ClaudeCodeAuthStatus.SUBSCRIPTION, provider("claude").getAuthStatus());
        assertEquals(List.of("claude", "auth", "status", "--json"), runner.commands.get(0));
    }

    @Test
    void loginUsesTheSubscriptionFlowAndReVerifies() {
        runner.queue(CliProcessResult.of(0, ""));
        runner.queue(CliProcessResult.of(0, SIGNED_IN));
        assertEquals(ClaudeCodeAuthStatus.SUBSCRIPTION, provider("claude").login());
        assertEquals(List.of("claude", "auth", "login", "--claudeai"), runner.commands.get(0));
        assertEquals(List.of("claude", "auth", "status", "--json"), runner.commands.get(1));
    }

    @Test
    void logoutIsVerifiedWithAFreshStatusRead() {
        runner.queue(CliProcessResult.of(0, ""));
        runner.queue(CliProcessResult.of(0, SIGNED_OUT));
        assertEquals(ClaudeCodeAuthStatus.NOT_LOGGED_IN, provider("claude").logout());
        assertEquals(List.of("claude", "auth", "logout"), runner.commands.get(0));
    }

    @Test
    void executeRunsANonInteractivePromptOnStdin() {
        runner.queue(CliProcessResult.of(0, "Add a Constant Timer.\n"));
        assertEquals("Add a Constant Timer.", provider("claude").execute("How do I pace requests?"));
        List<String> command = runner.commands.get(0);
        assertEquals(List.of("claude", "-p", "--output-format", "text"), command);
        assertEquals("How do I pace requests?", runner.stdins.get(0));
    }

    @Test
    void requestModelDoesNotMutateSharedSelection() {
        ClaudeCodeCliProvider provider = provider("claude");
        provider.setModel("selected-model");
        runner.queue(CliProcessResult.of(0, "ok"));

        provider.execute("hi", "request-model");

        List<String> command = runner.commands.get(0);
        assertEquals("request-model", command.get(command.indexOf("--model") + 1));
        assertEquals("selected-model", provider.getModel());
    }

    @Test
    void aTimedOutRunIsReportedWithoutAStackTrace() {
        runner.queue(CliProcessResult.timeout(120_000L));
        CliProviderException failure = assertThrows(CliProviderException.class,
                () -> provider("claude").execute("hi"));
        assertTrue(failure.getMessage().contains("did not respond"), failure.getMessage());
    }

    @Test
    void aFailedRunIsReportedWithTheCliDetail() {
        runner.queue(new CliProcessResult(1, "", "Invalid API key", false, 12L));
        CliProviderException failure = assertThrows(CliProviderException.class,
                () -> provider("claude").execute("hi"));
        assertFalse(failure.getMessage().isBlank());
    }

    @Test
    void anEmptyPromptNeverReachesTheCli() {
        assertThrows(CliProviderException.class, () -> provider("claude").execute(""));
        assertTrue(runner.commands.isEmpty());
    }

    private ClaudeCodeCliProvider provider(String binary) {
        return new ClaudeCodeCliProvider(new FakeAdapter(binary), runner);
    }

    private static final class FakeAdapter extends ClaudeCodeCliAdapter {

        private String binary;

        FakeAdapter(String binary) {
            this.binary = binary;
        }

        void setBinary(String binary) {
            this.binary = binary;
        }

        @Override
        public boolean detect() {
            detectedPath = binary;
            return binary != null;
        }
    }

    private static final class BlockingAdapter extends ClaudeCodeCliAdapter {

        private final String binary;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingAdapter(String binary) {
            this.binary = binary;
        }

        @Override
        public boolean detect() {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            detectedPath = binary;
            return true;
        }
    }

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
