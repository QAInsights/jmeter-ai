package org.qainsights.jmeter.ai.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
     * Runs {@code command} with additional environment variables for the child
     * process. Runners that do not support environment overrides retain the
     * default process behavior.
     */
    default CliProcessResult run(List<String> command, String stdin, Duration timeout,
                                 Map<String, String> environment) {
        return run(command, stdin, timeout);
    }

    /**
     * Runs {@code command} inside {@code workingDirectory} with additional
     * environment variables for the child process. Runners that do not
     * implement working-directory isolation fail fast rather than silently
     * spawning in the parent process working directory.
     *
     * @throws UnsupportedOperationException when this runner does not support
     *                                       running inside an explicit working
     *                                       directory
     */
    default CliProcessResult run(List<String> command, String stdin, Duration timeout,
                                 Path workingDirectory, Map<String, String> environment) {
        throw new UnsupportedOperationException(
                "Working directory override is not supported by this CliProcessRunner.");
    }

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
