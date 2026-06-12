package org.qainsights.jmeter.ai.headless;

import org.apache.jmeter.util.JMeterUtils;
import org.qainsights.jmeter.ai.claudecode.BaseCliAdapter;
import org.qainsights.jmeter.ai.claudecode.KiroCliAdapter;
import org.qainsights.jmeter.ai.security.AuditLogger;
import org.qainsights.jmeter.ai.security.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Runs a single AI CLI prompt without the JMeter GUI, for use in CI pipelines
 * and {@code jmeter -n} automation. Generates, optimizes, lints, or analyses a
 * test plan (or its results) from natural language and writes a structured
 * report artifact the build can archive and a PR can review.
 *
 * <p>Reuses the same governance as the GUI terminal: context is redacted via
 * {@link SecretRedactor} before it is shared, the launch is recorded by
 * {@link AuditLogger}, and the CLI's tool-trust policy applies.
 *
 * <p>Example:
 * <pre>
 *   java -cp jmeter-agent.jar org.qainsights.jmeter.ai.headless.HeadlessAiRunner \
 *     --jmx test.jmx --prompt "Lint this plan and list risky elements" \
 *     --output report.md --fail-on-error
 * </pre>
 */
public final class HeadlessAiRunner {

    private static final Logger log = LoggerFactory.getLogger(HeadlessAiRunner.class);

    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_CLI_NOT_FOUND = 3;
    public static final int EXIT_HEADLESS_UNSUPPORTED = 4;
    public static final int EXIT_TIMEOUT = 124;

    public static void main(String[] args) {
        int code;
        try {
            HeadlessOptions options = HeadlessOptions.parse(args);
            if (options.help) {
                System.out.println(HeadlessOptions.usage());
                System.exit(EXIT_OK);
            }
            ensureJMeterProperties();
            code = new HeadlessAiRunner().run(options, new DefaultProcessRunner(), null);
        } catch (HeadlessUsageException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.println(HeadlessOptions.usage());
            code = EXIT_USAGE;
        } catch (Exception e) {
            System.err.println("Headless run failed: " + e.getMessage());
            code = 1;
        }
        System.exit(code);
    }

    /**
     * Execute the run. Returns the process exit code to propagate. Does not call
     * {@link System#exit}, so it is unit-testable with a fake {@link ProcessRunner}
     * and adapter.
     *
     * @param adapter the CLI adapter to use, or {@code null} to resolve from
     *                {@code options.cli}
     */
    public int run(HeadlessOptions options, ProcessRunner runner, BaseCliAdapter adapter)
            throws Exception {

        String prompt = resolvePrompt(options);
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new HeadlessUsageException("A prompt is required (--prompt or --prompt-file)");
        }

        BaseCliAdapter cli = adapter != null ? adapter : resolveAdapter(options.cli);
        if (!cli.detect()) {
            System.err.println(cli.getName() + " was not found. Install it or set its path property.");
            return EXIT_CLI_NOT_FOUND;
        }
        if (!cli.supportsHeadless()) {
            System.err.println(cli.getName() + " does not support headless mode.");
            return EXIT_HEADLESS_UNSUPPORTED;
        }

        File workingDir = resolveWorkingDir(options);
        String sharedContext = prepareContext(options, workingDir);

        // Wire MCP servers (e.g. the JMeter MCP server) so the agent can run tests
        // and parse JTLs through tools rather than free text.
        org.qainsights.jmeter.ai.mcp.McpConfigWriter.writeFor(cli, workingDir);

        List<String> command = cli.buildHeadlessCommand(prompt, workingDir.getAbsolutePath());

        AuditLogger.recordLaunch(cli.getName(), cli.getBinaryPath(), command,
                workingDir.getAbsolutePath(), sharedContext);

        log.info("Running {} headless ({}s timeout): {}", cli.getName(), options.timeoutSeconds, command);
        ProcessRunner.Result result = runner.run(command, workingDir, options.timeoutSeconds, null);

        HeadlessReport report = new HeadlessReport(cli.getName(), cli.getBinaryPath(), prompt,
                options.jmx, command, result.exitCode, result.timedOut, result.output);
        writeReport(options, report);

        System.out.println("AI headless run " + report.status() + " — report: " + options.output);

        if (result.timedOut) {
            return options.failOnError ? EXIT_TIMEOUT : EXIT_OK;
        }
        if (result.exitCode != 0) {
            return options.failOnError ? result.exitCode : EXIT_OK;
        }
        return EXIT_OK;
    }

    private BaseCliAdapter resolveAdapter(String name) {
        String n = name == null ? "" : name.trim().toLowerCase();
        switch (n) {
            case "":
            case "kiro":
            case "kiro-cli":
            case "aws kiro":
                return new KiroCliAdapter();
            default:
                throw new HeadlessUsageException(
                        "Unknown or non-headless CLI: '" + name + "'. Supported: kiro");
        }
    }

    private String resolvePrompt(HeadlessOptions options) throws IOException {
        if (options.prompt != null && !options.prompt.isEmpty()) {
            return options.prompt;
        }
        if (options.promptFile != null && !options.promptFile.isEmpty()) {
            return new String(Files.readAllBytes(Paths.get(options.promptFile)), StandardCharsets.UTF_8);
        }
        return null;
    }

    private File resolveWorkingDir(HeadlessOptions options) throws IOException {
        if (options.workingDir != null && !options.workingDir.isEmpty()) {
            File dir = new File(options.workingDir);
            Files.createDirectories(dir.toPath());
            return dir;
        }
        return Files.createTempDirectory("jmeter-ai-headless-").toFile();
    }

    /**
     * If a {@code .jmx} was supplied, read it, redact secrets, and write the
     * context files Kiro reads ({@code KIRO.md} / {@code AGENTS.md} / {@code CLAUDE.md}).
     *
     * @return the redacted context that was shared (for the audit hash), or "".
     */
    private String prepareContext(HeadlessOptions options, File workingDir) throws IOException {
        if (options.jmx == null || options.jmx.isEmpty()) {
            return "";
        }
        Path jmxPath = Paths.get(options.jmx);
        if (!Files.isRegularFile(jmxPath)) {
            log.warn("--jmx not found, continuing without test-plan context: {}", options.jmx);
            return "";
        }
        String raw = new String(Files.readAllBytes(jmxPath), StandardCharsets.UTF_8);
        String context = "# JMeter Test Plan Context\n\n"
                + "Source: " + jmxPath.toAbsolutePath() + "\n\n"
                + "```xml\n" + raw + "\n```\n";
        String redacted = SecretRedactor.redact(context);
        byte[] bytes = redacted.getBytes(StandardCharsets.UTF_8);
        Files.write(new File(workingDir, "KIRO.md").toPath(), bytes);
        Files.write(new File(workingDir, "AGENTS.md").toPath(), bytes);
        Files.write(new File(workingDir, "CLAUDE.md").toPath(), bytes);
        return redacted;
    }

    private void writeReport(HeadlessOptions options, HeadlessReport report) throws IOException {
        Path out = Paths.get(options.output);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.write(out, report.render(options.format).getBytes(StandardCharsets.UTF_8));
    }

    /** Make AiConfig usable outside JMeter by ensuring a properties object exists. */
    private static void ensureJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() != null) {
            return;
        }
        try {
            String home = System.getProperty("jmeter.home",
                    System.getenv().getOrDefault("JMETER_HOME", ""));
            File props = home.isEmpty() ? null : new File(home, "bin/jmeter.properties");
            if (props != null && props.isFile()) {
                JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
            } else {
                File tmp = File.createTempFile("jmeter-ai", ".properties");
                tmp.deleteOnExit();
                JMeterUtils.loadJMeterProperties(tmp.getAbsolutePath());
            }
        } catch (IOException e) {
            log.warn("Could not initialise JMeter properties: {}", e.getMessage());
        }
    }
}
