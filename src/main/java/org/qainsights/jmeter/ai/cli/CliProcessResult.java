package org.qainsights.jmeter.ai.cli;

/**
 * Immutable outcome of one CLI invocation: exit code, captured stdout/stderr,
 * whether the process was killed on timeout and how long it ran. Nothing here
 * is provider-specific, so both the Codex and the Claude Code providers share it.
 */
public final class CliProcessResult {

    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final boolean timedOut;
    private final long durationMillis;

    public CliProcessResult(int exitCode, String stdout, String stderr, boolean timedOut, long durationMillis) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.timedOut = timedOut;
        this.durationMillis = durationMillis;
    }

    /** Convenience factory for tests and fakes: a completed run with no stderr. */
    public static CliProcessResult of(int exitCode, String stdout) {
        return new CliProcessResult(exitCode, stdout, "", false, 0L);
    }

    /** Convenience factory for a run killed by the timeout. */
    public static CliProcessResult timeout(long durationMillis) {
        return new CliProcessResult(-1, "", "", true, durationMillis);
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public boolean isSuccess() {
        return !timedOut && exitCode == 0;
    }
}
