package org.qainsights.jmeter.ai.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ProcessBuilder}-backed runner: no shell, UTF-8 in and out, stdout and
 * stderr drained on separate daemon threads (so neither pipe can deadlock the
 * caller), a hard timeout that force-kills the child, and interrupt status
 * preserved. Callers must stay off the Swing EDT - every provider in this
 * package is driven from a {@code SwingWorker} or a background thread.
 */
public final class DefaultCliProcessRunner implements CliProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultCliProcessRunner.class);

    /** Grace period for the drain threads to finish after the process exits. */
    private static final long DRAIN_JOIN_MILLIS = 2_000L;

    @Override
    public CliProcessResult run(List<String> command, String stdin, Duration timeout) {
        return run(command, stdin, timeout, null);
    }

    @Override
    public CliProcessResult run(List<String> command, String stdin, Duration timeout,
                                Consumer<String> stdoutLineConsumer) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        long startNanos = System.nanoTime();
        Process process = start(command);

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outDrain = drain(process.getInputStream(), stdout, stdoutLineConsumer, "cli-stdout");
        Thread errDrain = drain(process.getErrorStream(), stderr, null, "cli-stderr");
        Thread stdinWriter = writeStdin(process, stdin);
        List<Thread> ioThreads = List.of(stdinWriter, outDrain, errDrain);

        boolean exited;
        try {
            exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            destroy(process);
            interrupt(ioThreads);
            Thread.currentThread().interrupt();
            throw new CliProviderException("The " + command.get(0) + " request was cancelled.", e);
        }

        if (!exited) {
            destroy(process);
            interrupt(ioThreads);
            join(ioThreads);
            long elapsed = elapsedMillis(startNanos);
            log.warn("CLI execution timed out after {} ms: {}", elapsed, command.get(0));
            return new CliProcessResult(-1, snapshot(stdout), snapshot(stderr), true, elapsed);
        }

        join(ioThreads);
        int exitCode = process.exitValue();
        long elapsed = elapsedMillis(startNanos);
        log.info("CLI execution completed: executable={} exitCode={} durationMs={}",
                command.get(0), exitCode, elapsed);
        return new CliProcessResult(exitCode, snapshot(stdout), snapshot(stderr), false, elapsed);
    }

    private static Process start(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.redirectErrorStream(false);
        try {
            log.info("CLI execution started: {}", command.get(0));
            return builder.start();
        } catch (IOException e) {
            throw new CliProviderException("Could not start '" + command.get(0)
                    + "'. Check that it is installed and on your PATH.", e);
        }
    }

    /** Writes the prompt to the child's stdin; a closed pipe means the child already exited. */
    private static Thread writeStdin(Process process, String stdin) {
        Thread thread = new Thread(() -> {
            try (OutputStream out = process.getOutputStream()) {
                if (stdin != null) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException e) {
                log.debug("Could not write to the CLI stdin (process may have exited): {}", e.getMessage());
            }
        }, "cli-stdin");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static Thread drain(InputStream stream, StringBuilder sink, Consumer<String> lineConsumer, String name) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                    }
                    if (lineConsumer != null) {
                        lineConsumer.accept(line);
                    }
                }
            } catch (IOException e) {
                log.debug("Stopped reading CLI output: {}", e.getMessage());
            } catch (UncheckedIOException e) {
                log.debug("Stopped reading CLI output: {}", e.getMessage());
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void destroy(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (ProcessHandle descendant : descendants) {
            descendant.destroyForcibly();
        }
        process.destroyForcibly();
    }

    private static void interrupt(List<Thread> threads) {
        for (Thread thread : threads) {
            thread.interrupt();
        }
    }

    private static void join(List<Thread> threads) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_JOIN_MILLIS);
        for (Thread thread : threads) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
            int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
            try {
                thread.join(millis, nanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Thread thread : threads) {
            if (thread.isAlive()) {
                log.warn("CLI I/O thread did not stop within {} ms: {}", DRAIN_JOIN_MILLIS, thread.getName());
            }
        }
    }

    private static String snapshot(StringBuilder sink) {
        synchronized (sink) {
            return sink.toString();
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
