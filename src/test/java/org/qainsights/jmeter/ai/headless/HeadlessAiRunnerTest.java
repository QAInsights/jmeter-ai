package org.qainsights.jmeter.ai.headless;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qainsights.jmeter.ai.claudecode.BaseCliAdapter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeadlessAiRunnerTest {

    @BeforeAll
    static void initProps() throws IOException {
        if (JMeterUtils.getJMeterProperties() == null) {
            File tmp = File.createTempFile("jmeter-test", ".properties");
            tmp.deleteOnExit();
            JMeterUtils.loadJMeterProperties(tmp.getAbsolutePath());
        }
        // Keep audit writes out of the home directory during tests.
        File audit = File.createTempFile("audit", ".log");
        audit.deleteOnExit();
        JMeterUtils.setProperty("jmeter.ai.security.audit.file", audit.getAbsolutePath());
    }

    @Test
    void successReturnsZeroAndWritesReport(@TempDir Path dir) throws Exception {
        HeadlessOptions o = options(dir, false);
        int code = new HeadlessAiRunner().run(o, fixedRunner(0, "AI says hello", false),
                adapter(true, true));

        assertEquals(HeadlessAiRunner.EXIT_OK, code);
        String report = read(o.output);
        assertTrue(report.contains("SUCCESS"), report);
        assertTrue(report.contains("AI says hello"), report);
    }

    @Test
    void failureWithoutFailOnErrorStillReturnsZero(@TempDir Path dir) throws Exception {
        HeadlessOptions o = options(dir, false);
        int code = new HeadlessAiRunner().run(o, fixedRunner(2, "boom", false), adapter(true, true));
        assertEquals(HeadlessAiRunner.EXIT_OK, code);
        assertTrue(read(o.output).contains("FAILED"));
    }

    @Test
    void failureWithFailOnErrorPropagatesExitCode(@TempDir Path dir) throws Exception {
        HeadlessOptions o = options(dir, true);
        int code = new HeadlessAiRunner().run(o, fixedRunner(2, "boom", false), adapter(true, true));
        assertEquals(2, code);
    }

    @Test
    void timeoutWithFailOnErrorReturnsTimeoutCode(@TempDir Path dir) throws Exception {
        HeadlessOptions o = options(dir, true);
        int code = new HeadlessAiRunner().run(o, fixedRunner(124, "", true), adapter(true, true));
        assertEquals(HeadlessAiRunner.EXIT_TIMEOUT, code);
    }

    @Test
    void cliNotFoundReturnsNotFoundCode(@TempDir Path dir) throws Exception {
        HeadlessOptions o = options(dir, false);
        int code = new HeadlessAiRunner().run(o, fixedRunner(0, "", false), adapter(false, true));
        assertEquals(HeadlessAiRunner.EXIT_CLI_NOT_FOUND, code);
    }

    @Test
    void unsupportedHeadlessReturnsUnsupportedCode(@TempDir Path dir) throws Exception {
        HeadlessOptions o = options(dir, false);
        int code = new HeadlessAiRunner().run(o, fixedRunner(0, "", false), adapter(true, false));
        assertEquals(HeadlessAiRunner.EXIT_HEADLESS_UNSUPPORTED, code);
    }

    @Test
    void missingPromptThrowsUsage(@TempDir Path dir) {
        HeadlessOptions o = options(dir, false);
        o.prompt = null;
        assertThrows(HeadlessUsageException.class,
                () -> new HeadlessAiRunner().run(o, fixedRunner(0, "", false), adapter(true, true)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static HeadlessOptions options(Path dir, boolean failOnError) {
        HeadlessOptions o = new HeadlessOptions();
        o.prompt = "analyze the plan";
        o.workingDir = dir.resolve("work").toString();
        o.output = dir.resolve("report.md").toString();
        o.failOnError = failOnError;
        return o;
    }

    private static ProcessRunner fixedRunner(int exit, String output, boolean timedOut) {
        return (command, workingDir, timeoutSeconds, env) ->
                new ProcessRunner.Result(exit, output, timedOut);
    }

    private static BaseCliAdapter adapter(boolean detect, boolean headless) {
        return new BaseCliAdapter() {
            @Override
            public String getName() {
                return "FakeKiro";
            }

            @Override
            public boolean detect() {
                detectedPath = "/usr/local/bin/fake-kiro";
                return detect;
            }

            @Override
            public boolean supportsHeadless() {
                return headless;
            }

            @Override
            public List<String> buildHeadlessCommand(String prompt, String workingDirectory) {
                return Arrays.asList("/usr/local/bin/fake-kiro", "chat", "--no-interactive", prompt);
            }
        };
    }
}
