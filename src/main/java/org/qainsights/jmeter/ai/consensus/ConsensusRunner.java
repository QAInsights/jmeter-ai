package org.qainsights.jmeter.ai.consensus;

import org.qainsights.jmeter.ai.claudecode.BaseCliAdapter;
import org.qainsights.jmeter.ai.headless.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the same prompt across multiple AI CLIs and collects their answers, for
 * high-stakes analysis where one model's word shouldn't be trusted alone
 * (e.g. "why did p99 spike?"). The collected {@link Outcome}s feed a
 * {@link ConsensusReport} that surfaces agreement and divergence.
 */
public final class ConsensusRunner {

    private static final Logger log = LoggerFactory.getLogger(ConsensusRunner.class);

    /** One CLI's result in a consensus run. */
    public static final class Outcome {
        public final String cliName;
        public final boolean available;
        public final int exitCode;
        public final boolean timedOut;
        public final String output;

        public Outcome(String cliName, boolean available, int exitCode, boolean timedOut, String output) {
            this.cliName = cliName;
            this.available = available;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.output = output == null ? "" : output;
        }

        public boolean succeeded() {
            return available && !timedOut && exitCode == 0;
        }
    }

    private final ProcessRunner runner;

    public ConsensusRunner(ProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Run {@code prompt} against each CLI that is available and headless-capable.
     * Unavailable CLIs are still represented (as a skipped {@link Outcome}) so the
     * report is honest about coverage.
     */
    public List<Outcome> run(String prompt, String workingDir, List<BaseCliAdapter> clis,
                             long timeoutSeconds) {
        List<Outcome> outcomes = new ArrayList<>();
        for (BaseCliAdapter cli : clis) {
            if (!cli.detect() || !cli.supportsHeadless()) {
                outcomes.add(new Outcome(cli.getName(), false, -1, false,
                        "(skipped: not available or no headless mode)"));
                continue;
            }
            try {
                List<String> command = cli.buildHeadlessCommand(prompt, workingDir);
                java.io.File wd = workingDir == null ? null : new java.io.File(workingDir);
                ProcessRunner.Result r = runner.run(command, wd, timeoutSeconds, null);
                outcomes.add(new Outcome(cli.getName(), true, r.exitCode, r.timedOut, r.output));
            } catch (Exception e) {
                log.warn("Consensus run failed for {}: {}", cli.getName(), e.getMessage());
                outcomes.add(new Outcome(cli.getName(), true, 1, false, "Error: " + e.getMessage()));
            }
        }
        return outcomes;
    }
}
