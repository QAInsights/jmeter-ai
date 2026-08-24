package org.qainsights.jmeter.ai.cli;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Seam over process execution so provider logic (status parsing, prompt
 * building) is unit-testable without spawning real processes.
 */
public interface CliProcessRunner {

    /**
     * Runs {@code command} with no shell, writing {@code stdin} to the process
     * (when non-null) and killing it after {@code timeout}.
     *
     * @throws CliProviderException when the executable cannot be started or the
     *                              calling thread is interrupted
     */
    CliProcessResult run(List<String> command, String stdin, Duration timeout);

    /**
     * Same as {@link #run(List, String, Duration)} but forwarding each stdout
     * line to {@code stdoutLineConsumer} as it arrives (used to surface the
     * login URL while {@code codex login} is still running).
     */
    default CliProcessResult run(List<String> command, String stdin, Duration timeout,
                                 Consumer<String> stdoutLineConsumer) {
        return run(command, stdin, timeout);
    }
}
