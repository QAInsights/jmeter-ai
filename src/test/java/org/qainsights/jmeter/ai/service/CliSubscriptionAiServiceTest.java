package org.qainsights.jmeter.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.cli.CliAuthState;
import org.qainsights.jmeter.ai.cli.CliProviderException;
import org.qainsights.jmeter.ai.cli.SubscriptionCliProvider;

class CliSubscriptionAiServiceTest {

    @Test
    void requestModelDoesNotMutateTheProviderSelection() {
        RecordingProvider provider = new RecordingProvider();
        provider.setModel("selected-model");
        TestService service = new TestService(provider);

        assertEquals("ok", service.generateResponse(List.of("hello"), "request-model"));

        assertEquals("request-model", provider.requestModel.get());
        assertEquals("selected-model", provider.getModel());
    }

    @Test
    void streamingSnapshotsTheModelBeforeStartingTheWorker() throws Exception {
        BlockingProvider provider = new BlockingProvider();
        provider.setModel("initial-model");
        TestService service = new TestService(provider);
        CountDownLatch completed = new CountDownLatch(1);

        service.generateStreamResponse(List.of("hello"), null, ignored -> { }, completed::countDown,
                ignored -> { });
        assertTrue(provider.started.await(2, TimeUnit.SECONDS));
        provider.setModel("new-selection");
        provider.release.countDown();

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals("initial-model", provider.requestModel.get());
    }

    @Test
    void cancellingAStreamInterruptsTheCliWorker() throws Exception {
        BlockingProvider provider = new BlockingProvider();
        TestService service = new TestService(provider);
        Runnable cancel = service.generateStreamResponse(List.of("hello"), "request-model",
                ignored -> { }, () -> { }, ignored -> { });
        assertTrue(provider.started.await(2, TimeUnit.SECONDS));

        cancel.run();

        assertTrue(provider.interrupted.await(2, TimeUnit.SECONDS));
        provider.release.countDown();
    }

    @Test
    void streamingDeliversTheAnswerAsOneChunkThenCompletes() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        TestService service = new TestService(provider);
        List<String> tokens = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();

        service.generateStreamResponse(List.of("hello"), null, tokens::add, completed::countDown, error::set);

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("ok"), tokens);
        assertNull(error.get());
    }

    @Test
    void aCliFailureIsRoutedToOnErrorWithoutCompleting() throws Exception {
        CliProviderException failure = new CliProviderException("Codex is not signed in.");
        RecordingProvider provider = new RecordingProvider() {
            @Override
            public String execute(String prompt, String model) {
                throw failure;
            }
        };
        TestService service = new TestService(provider);
        AtomicInteger tokens = new AtomicInteger();
        AtomicBoolean completed = new AtomicBoolean();
        CountDownLatch errored = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();

        service.generateStreamResponse(List.of("hello"), null, ignored -> tokens.incrementAndGet(),
                () -> completed.set(true), e -> {
                    error.set(e);
                    errored.countDown();
                });

        assertTrue(errored.await(2, TimeUnit.SECONDS));
        assertSame(failure, error.get());
        assertEquals(0, tokens.get());
        assertFalse(completed.get());
    }

    @Test
    void anUnexpectedRuntimeFailureIsAlsoRoutedToOnError() throws Exception {
        IllegalStateException failure = new IllegalStateException("boom");
        RecordingProvider provider = new RecordingProvider() {
            @Override
            public String execute(String prompt, String model) {
                throw failure;
            }
        };
        CountDownLatch errored = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();

        new TestService(provider).generateStreamResponse(List.of("hello"), null, ignored -> { }, () -> { },
                e -> {
                    error.set(e);
                    errored.countDown();
                });

        assertTrue(errored.await(2, TimeUnit.SECONDS));
        assertSame(failure, error.get());
    }

    @Test
    void aCancelledStreamSuppressesEveryCallback() throws Exception {
        BlockingProvider provider = new BlockingProvider();
        TestService service = new TestService(provider);
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(1);
        Runnable cancel = service.generateStreamResponse(List.of("hello"), null,
                ignored -> callbacks.incrementAndGet(), callbacks::incrementAndGet,
                ignored -> callbacks.incrementAndGet());
        assertTrue(provider.started.await(2, TimeUnit.SECONDS));

        cancel.run();
        assertTrue(provider.interrupted.await(2, TimeUnit.SECONDS));
        provider.finished.thenRun(finished::countDown);

        assertTrue(finished.await(2, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertEquals(0, callbacks.get());
    }

    @Test
    void aCancelledStreamSuppressesALateAnswer() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        RecordingProvider provider = new RecordingProvider() {
            @Override
            public String execute(String prompt, String model) {
                started.countDown();
                try {
                    // swallow the interrupt, as a CLI that already produced output might
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "late answer";
            }
        };
        AtomicInteger callbacks = new AtomicInteger();
        Runnable cancel = new TestService(provider) {
            @Override
            public String generateResponse(List<String> conversation, String model) {
                try {
                    return super.generateResponse(conversation, model);
                } finally {
                    finished.countDown();
                }
            }
        }.generateStreamResponse(List.of("hello"), null, ignored -> callbacks.incrementAndGet(),
                callbacks::incrementAndGet, ignored -> callbacks.incrementAndGet());
        assertTrue(started.await(2, TimeUnit.SECONDS));

        cancel.run();
        release.countDown();

        assertTrue(finished.await(2, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertEquals(0, callbacks.get());
    }

    @Test
    void anEmptyHistoryYieldsTheGreetingPrompt() {
        assertEquals("SYS\n\nUser: Hello, how can you help me with JMeter?",
                CliSubscriptionAiService.buildPrompt("SYS", List.of(), 10));
        assertEquals("SYS\n\nUser: Hello, how can you help me with JMeter?",
                CliSubscriptionAiService.buildPrompt("SYS", null, 10));
    }

    @Test
    void aSingleMessageIsLabelledAsTheUser() {
        assertEquals("SYS\n\nUser: q1\n\nAnswer the last user message. Reply with the answer text only.",
                CliSubscriptionAiService.buildPrompt("SYS", List.of("q1"), 10));
    }

    @Test
    void anEvenHistoryStartsWithTheAssistantSoTheLastEntryIsTheUser() {
        assertEquals("SYS\n\nAssistant: a0\n\nUser: q1\n\n"
                        + "Answer the last user message. Reply with the answer text only.",
                CliSubscriptionAiService.buildPrompt("SYS", List.of("a0", "q1"), 10));
    }

    @Test
    void anOddHistoryAlternatesStartingWithTheUser() {
        assertEquals("SYS\n\nUser: q0\n\nAssistant: a0\n\nUser: q1\n\n"
                        + "Answer the last user message. Reply with the answer text only.",
                CliSubscriptionAiService.buildPrompt("SYS", List.of("q0", "a0", "q1"), 10));
    }

    @Test
    void historyIsCappedToTheNewestEntriesKeepingTheLastAsUser() {
        List<String> conversation = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            conversation.add(i % 2 == 0 ? "q" + i : "a" + i);
        }
        // q0 a1 q2 a3 q4 a5 q6 a7 q8 a9 q10 a11 q12 -> the newest 10 are a3..q12
        conversation.add("q12");
        String prompt = CliSubscriptionAiService.buildPrompt("SYS", conversation, 10);

        assertFalse(prompt.contains("q0\n"), prompt);
        assertFalse(prompt.contains("a1\n"), prompt);
        assertFalse(prompt.contains("q2\n"), prompt);
        assertTrue(prompt.contains("Assistant: a3\n\n"), prompt);
        assertTrue(prompt.contains("User: q4\n\n"), prompt);
        assertTrue(prompt.contains("Assistant: a11\n\nUser: q12\n\nAnswer the last user message."), prompt);
    }

    @Test
    void aNonPositiveCapStillKeepsTheCurrentMessage() {
        assertEquals("SYS\n\nUser: q2\n\nAnswer the last user message. Reply with the answer text only.",
                CliSubscriptionAiService.buildPrompt("SYS", List.of("q0", "a1", "q2"), 0));
        assertEquals("SYS\n\nUser: q2\n\nAnswer the last user message. Reply with the answer text only.",
                CliSubscriptionAiService.buildPrompt("SYS", List.of("q0", "a1", "q2"), -5));
    }

    @Test
    void generateResponseSendsTheTranscriptToTheProvider() {
        RecordingProvider provider = new RecordingProvider();
        new TestService(provider).generateResponse(List.of("q0", "a0", "q1"));

        String prompt = provider.prompt.get();
        assertTrue(prompt.startsWith("CUSTOM SYSTEM\n\n"), prompt);
        assertTrue(prompt.contains("User: q0\n\nAssistant: a0\n\nUser: q1\n\n"), prompt);
    }

    private static class TestService extends CliSubscriptionAiService {

        TestService(SubscriptionCliProvider provider) {
            super(provider);
        }

        @Override
        public String getName() {
            return "Test CLI";
        }

        @Override
        protected String systemPrompt() {
            return "CUSTOM SYSTEM";
        }
    }

    private static class RecordingProvider implements SubscriptionCliProvider {

        protected final AtomicReference<String> requestModel = new AtomicReference<>();
        protected final AtomicReference<String> prompt = new AtomicReference<>();
        private volatile String model = "";

        @Override
        public String execute(String prompt) {
            return execute(prompt, model);
        }

        @Override
        public String execute(String prompt, String model) {
            this.prompt.set(prompt);
            requestModel.set(model);
            return "ok";
        }

        @Override
        public String displayName() {
            return "Test CLI";
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean isInstalled() {
            return true;
        }

        @Override
        public CliAuthState getAuthStatus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CliAuthState login() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CliAuthState logout() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String installHint() {
            return "install it";
        }

        @Override
        public String signInActionLabel() {
            return "Sign in";
        }

        @Override
        public String modelPrefix() {
            return "test:";
        }

        @Override
        public void refresh() {
        }

        @Override
        public List<String> listModels() {
            return List.of("default");
        }

        @Override
        public String getModel() {
            return model;
        }

        @Override
        public void setModel(String model) {
            this.model = model;
        }
    }

    private static final class BlockingProvider extends RecordingProvider {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CompletableFuture<Void> finished = new CompletableFuture<>();

        @Override
        public String execute(String prompt, String model) {
            requestModel.set(model);
            started.countDown();
            try {
                release.await();
                return "ok";
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new CliProviderException("cancelled", e);
            } finally {
                finished.complete(null);
            }
        }
    }
}
