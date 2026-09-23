package org.qainsights.jmeter.ai.service;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Records the token / reasoning / completion / error callbacks of a streaming
 * call. {@link #awaitTerminal()} blocks until onComplete or onError fired and
 * then drains the EDT so every earlier {@code invokeLater} callback is visible.
 */
final class StreamCallbacks {

    final List<String> tokens = new CopyOnWriteArrayList<>();
    final List<String> reasoning = new CopyOnWriteArrayList<>();
    final List<Exception> errors = new CopyOnWriteArrayList<>();
    final AtomicInteger completions = new AtomicInteger();
    private final CountDownLatch terminal = new CountDownLatch(1);

    final Consumer<String> onToken = tokens::add;
    final Consumer<String> onReasoning = reasoning::add;
    final Runnable onComplete = () -> {
        completions.incrementAndGet();
        terminal.countDown();
    };
    final Consumer<Exception> onError = error -> {
        errors.add(error);
        terminal.countDown();
    };

    StreamCallbacks awaitTerminal() throws Exception {
        if (!terminal.await(20, TimeUnit.SECONDS)) {
            throw new AssertionError("Stream did not complete or fail within 20s; tokens=" + tokens
                    + ", errors=" + errors);
        }
        flushEdt();
        return this;
    }

    String text() {
        return String.join("", tokens);
    }

    static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }
}
