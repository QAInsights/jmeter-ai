package org.qainsights.jmeter.ai.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end behaviour of {@link DefaultCliProcessRunner} against real child
 * processes. The children are single-file Java programs run by the JVM that is
 * running the tests, so the assertions hold on Windows, macOS and Linux without
 * a shell.
 */
class DefaultCliProcessRunnerTest {

    private final DefaultCliProcessRunner runner = new DefaultCliProcessRunner();

    @Test
    void capturesStdoutStderrAndExitCode(@TempDir Path dir) throws IOException {
        Path program = program(dir, "Both", """
                public class Both {
                    public static void main(String[] args) {
                        System.out.println("hello \\u00e9");
                        System.err.println("a warning");
                        System.exit(3);
                    }
                }
                """);
        CliProcessResult result = runReliably(javaCommand(program), null, Duration.ofSeconds(60));
        assertFalse(result.isTimedOut(), describe(result));
        assertFalse(result.isSuccess(), describe(result));
        assertEquals(3, result.getExitCode());
        assertTrue(result.getStdout().contains("hello é"), result.getStdout());
        assertTrue(result.getStderr().contains("a warning"), result.getStderr());
    }

    @Test
    void sendsThePromptOnStdinAsUtf8(@TempDir Path dir) throws IOException {
        Path program = program(dir, "Echo", """
                import java.io.InputStreamReader;
                import java.io.BufferedReader;
                import java.nio.charset.StandardCharsets;

                public class Echo {
                    public static void main(String[] args) throws Exception {
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(System.in, StandardCharsets.UTF_8));
                        String line;
                        while ((line = in.readLine()) != null) {
                            System.out.println("got:" + line);
                        }
                    }
                }
                """);
        CliProcessResult result = runReliably(javaCommand(program), "caf\u00e9 latte", Duration.ofSeconds(60));
        assertTrue(result.isSuccess(), describe(result));
        assertEquals("got:café latte", result.getStdout().trim());
    }

    @Test
    void forwardsEnvironmentVariablesToTheChild(@TempDir Path dir) throws IOException {
        Path program = program(dir, "Environment", """
                public class Environment {
                    public static void main(String[] args) {
                        System.out.print(System.getenv("X"));
                    }
                }
                """);

        CliProcessResult result = runReliably(
                javaCommand(program), null, Duration.ofSeconds(60), Map.of("X", "from-runner"));

        assertTrue(result.isSuccess(), describe(result));
        assertEquals("from-runner", result.getStdout().trim());
    }

    @Test
    void killsAProcessThatOutlivesTheTimeout(@TempDir Path dir) throws IOException {
        Path program = program(dir, "Sleeper", """
                public class Sleeper {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(120_000L);
                    }
                }
                """);
        CliProcessResult result = runReliably(javaCommand(program), null, Duration.ofMillis(1_500));
        assertTrue(result.isTimedOut(), describe(result));
        assertFalse(result.isSuccess(), describe(result));
    }

    @Test
    void timeoutIncludesABlockedStdinWrite(@TempDir Path dir) throws IOException {
        Path program = program(dir, "NonReader", """
                public class NonReader {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(30_000L);
                    }
                }
                """);
        String prompt = "x".repeat(4 * 1024 * 1024);

        CliProcessResult result = assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> runReliably(javaCommand(program), prompt, Duration.ofMillis(500)));

        assertTrue(result.isTimedOut(), describe(result));
        assertFalse(result.isSuccess(), describe(result));
    }

    @Test
    void pipeHoldingDescendantCannotDelayOrRaceOutputSnapshot(@TempDir Path dir) throws IOException {
        Path descendant = program(dir, "PipeHolder", """
                public class PipeHolder {
                    public static void main(String[] args) throws Exception {
                        for (int i = 0; i < 10_000; i++) {
                            System.out.println("descendant-out-" + i);
                            System.err.println("descendant-err-" + i);
                            Thread.sleep(1L);
                        }
                    }
                }
                """);
        Path program = program(dir, "Spawner", """
                public class Spawner {
                    public static void main(String[] args) throws Exception {
                        new ProcessBuilder(args[0], "-Xms16m", "-Xmx64m", args[1]).inheritIO().start();
                        System.out.println("parent-out");
                        System.err.println("parent-err");
                    }
                }
                """);

        CliProcessResult result = assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> runReliably(javaCommand(program, javaLauncher(), descendant.toString()),
                        null, Duration.ofSeconds(60)));

        assertTrue(result.isSuccess(), describe(result));
        assertTrue(result.getStdout().contains("parent-out"), result.getStdout());
        assertTrue(result.getStderr().contains("parent-err"), result.getStderr());
    }

    @Test
    void aMissingExecutableIsReportedAsAProviderFailure() {
        CliProviderException failure = assertThrows(CliProviderException.class,
                () -> runner.run(List.of("definitely-not-a-real-cli-xyz"), null, Duration.ofSeconds(5)));
        assertTrue(failure.getMessage().contains("PATH"), failure.getMessage());
    }

    @Test
    void anEmptyCommandIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(List.of(), null, Duration.ofSeconds(5)));
    }

    /**
     * Runs the command, retrying when the child exhibits a launch flake: a
     * non-zero exit in under a second. A freshly written {@code .java} file can
     * briefly be unavailable to the spawned launcher (e.g. a real-time AV scan
     * still holding it), which kills the child almost instantly; the assertion
     * that follows would then see a spurious failure with no diagnostics.
     */
    private CliProcessResult runReliably(List<String> command, String stdin, Duration timeout) {
        return runReliably(command, stdin, timeout, Map.of());
    }

    private CliProcessResult runReliably(List<String> command, String stdin, Duration timeout,
                                       Map<String, String> environment) {
        CliProcessResult result = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            result = environment.isEmpty()
                    ? runner.run(command, stdin, timeout)
                    : runner.run(command, stdin, timeout, environment);
            if (result.isTimedOut() || result.getExitCode() == 0
                    || result.getDurationMillis() >= 1_000) {
                return result;
            }
        }
        return result;
    }

    private static String describe(CliProcessResult result) {
        return "exitCode=" + result.getExitCode() + " timedOut=" + result.isTimedOut()
                + " durationMs=" + result.getDurationMillis()
                + " stdout=" + result.getStdout() + " stderr=" + result.getStderr();
    }

    private static Path program(Path dir, String name, String source) throws IOException {
        Path file = dir.resolve(name + ".java");
        Files.write(file, source.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** Runs a single-file Java program with the JVM executing this test. */
    private static List<String> javaCommand(Path program, String... args) {
        List<String> command = new ArrayList<>();
        command.add(javaLauncher());
        // Cap the child heap - default ergonomics commit ~500 MB per spawn,
        // which can exhaust the machine's pagefile while the suite is loaded.
        command.add("-Xms16m");
        command.add("-Xmx64m");
        command.add(program.toString());
        command.addAll(List.of(args));
        return command;
    }

    private static String javaLauncher() {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }
}
