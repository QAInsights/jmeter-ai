package org.qainsights.jmeter.ai.consensus;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.claudecode.BaseCliAdapter;
import org.qainsights.jmeter.ai.headless.ProcessRunner;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsensusRunnerTest {

    @Test
    void runsEachAvailableCliAndCollectsOutcomes() {
        ProcessRunner runner = (command, wd, timeout, env) ->
                new ProcessRunner.Result(0, "answer from " + command.get(0), false);

        List<ConsensusRunner.Outcome> outcomes = new ConsensusRunner(runner).run(
                "why slow?", "/tmp", Arrays.asList(fake("Kiro", true), fake("Claude Code", true)), 60);

        assertEquals(2, outcomes.size());
        assertTrue(outcomes.get(0).succeeded());
        assertTrue(outcomes.get(1).succeeded());
        assertTrue(outcomes.get(0).output.contains("answer from"));
    }

    @Test
    void unavailableCliIsRecordedAsSkipped() {
        ProcessRunner runner = (command, wd, timeout, env) ->
                new ProcessRunner.Result(0, "ok", false);

        List<ConsensusRunner.Outcome> outcomes = new ConsensusRunner(runner).run(
                "q", "/tmp", Arrays.asList(fake("Kiro", true), fake("Missing", false)), 60);

        assertTrue(outcomes.get(0).succeeded());
        assertFalse(outcomes.get(1).available);
        assertFalse(outcomes.get(1).succeeded());
    }

    private static BaseCliAdapter fake(String name, boolean available) {
        return new BaseCliAdapter() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean detect() {
                detectedPath = "/usr/local/bin/" + name.toLowerCase().replace(' ', '-');
                return available;
            }

            @Override
            public boolean supportsHeadless() {
                return true;
            }

            @Override
            public List<String> buildHeadlessCommand(String prompt, String workingDirectory) {
                return Arrays.asList("/bin/" + name.toLowerCase().replace(' ', '-'), "-p", prompt);
            }
        };
    }
}
