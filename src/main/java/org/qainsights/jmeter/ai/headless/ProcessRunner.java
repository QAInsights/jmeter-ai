package org.qainsights.jmeter.ai.headless;

import java.io.File;
import java.util.List;

/**
 * Seam for executing an external CLI process. Abstracted so the headless runner
 * can be unit-tested with a fake instead of launching a real binary.
 */
public interface ProcessRunner {

    /** Outcome of a process execution. */
    final class Result {
        public final int exitCode;
        public final String output;
        public final boolean timedOut;

        public Result(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.timedOut = timedOut;
        }
    }

    /**
     * Run {@code command} in {@code workingDir}, capturing combined stdout+stderr.
     *
     * @param command        full argv (argv[0] is the binary)
     * @param workingDir     working directory (may be null = inherit)
     * @param timeoutSeconds wall-clock limit; the process is killed if exceeded
     * @param env            extra environment variables to set (may be null)
     */
    Result run(List<String> command, File workingDir, long timeoutSeconds,
               java.util.Map<String, String> env) throws Exception;
}
