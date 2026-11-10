package org.qainsights.jmeter.ai.param;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.SwingWorker;
import javax.swing.Timer;

final class ParameterSampleRequest {
    private final SwingWorker<List<List<String>>, Void> worker;
    private final Timer timer;
    private boolean stopped;

    ParameterSampleRequest(Function<String, String> provider, String prompt, int count, int width,
            int timeoutMillis, Consumer<List<List<String>>> success, Consumer<String> failure) {
        timer = new Timer(timeoutMillis, event -> {
            cancel();
            failure.accept("Sample generation timed out. Try fewer rows or another provider.");
        });
        timer.setRepeats(false);
        worker = new SwingWorker<>() {
            @Override
            protected List<List<String>> doInBackground() {
                return ParameterSampleRows.parse(provider.apply(prompt), count, width);
            }

            @Override
            protected void done() {
                timer.stop();
                if (stopped || isCancelled()) {
                    return;
                }
                try {
                    success.accept(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure.accept("Sample generation was interrupted.");
                } catch (CancellationException e) {
                    // Cancellation deliberately leaves the plan and file untouched.
                } catch (ExecutionException e) {
                    failure.accept(e.getCause() instanceof IllegalArgumentException
                            ? e.getCause().getMessage()
                            : "Could not generate sample rows. Check the provider settings and try again.");
                }
            }
        };
    }

    void start() {
        timer.start();
        worker.execute();
    }

    void cancel() {
        stopped = true;
        timer.stop();
        worker.cancel(true);
    }
}
