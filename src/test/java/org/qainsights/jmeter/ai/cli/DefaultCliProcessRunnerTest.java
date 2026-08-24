package org.qainsights.jmeter.ai.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

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
        CliProcessResult result = runner.run(javaCommand(program), null, Duration.ofSeconds(60));
        assertFalse(result.isTimedOut());
        assertFalse(result.isSuccess());
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
        CliProcessResult result = runner.run(javaCommand(program), "caf\u00e9 latte", Duration.ofSeconds(60));
        assertTrue(result.isSuccess());
        assertEquals("got:café latte", result.getStdout().trim());
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
        CliProcessResult result = runner.run(javaCommand(program), null, Duration.ofMillis(1_500));
        assertTrue(result.isTimedOut());
        assertFalse(result.isSuccess());
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

    private static Path program(Path dir, String name, String source) throws IOException {
        Path file = dir.resolve(name + ".java");
        Files.write(file, source.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** Runs a single-file Java program with the JVM executing this test. */
    private static List<String> javaCommand(Path program) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("win");
        Path launcher = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        return List.of(launcher.toString(), program.toString());
    }
}
