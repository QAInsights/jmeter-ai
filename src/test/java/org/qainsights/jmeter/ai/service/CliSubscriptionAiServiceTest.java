package org.qainsights.jmeter.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    private static final class TestService extends CliSubscriptionAiService {

        TestService(SubscriptionCliProvider provider) {
            super(provider);
        }

        @Override
        public String getName() {
            return "Test CLI";
        }
    }

    private static class RecordingProvider implements SubscriptionCliProvider {

        protected final AtomicReference<String> requestModel = new AtomicReference<>();
        private volatile String model = "";

        @Override
        public String execute(String prompt) {
            return execute(prompt, model);
        }

        @Override
        public String execute(String prompt, String model) {
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
            }
        }
    }
}
