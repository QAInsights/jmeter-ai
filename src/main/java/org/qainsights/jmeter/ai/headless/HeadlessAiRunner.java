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
            org.qainsights.jmeter.ai.config.ManagedConfigLoader.applyOnce();
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

        // Optional generation step: HAR / OpenAPI -> .jmx. If no prompt follows,
        // this is a pure generation run; otherwise the generated plan becomes the
        // context the AI then refines.
        boolean hasPrompt = (options.prompt != null && !options.prompt.isEmpty())
                || (options.promptFile != null && !options.promptFile.isEmpty());
        if (options.generateFrom != null && !options.generateFrom.isEmpty()) {
            String jmx = org.qainsights.jmeter.ai.generate.TestPlanGenerator
                    .generateFromFile(options.generateFrom, null);
            Path out = Paths.get(options.generateOut);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.write(out, jmx.getBytes(StandardCharsets.UTF_8));
            System.out.println("Generated test plan: " + options.generateOut);
            if (!hasPrompt) {
                return EXIT_OK;
            }
            if (options.jmx == null || options.jmx.isEmpty()) {
                options.jmx = options.generateOut; // refine the plan we just made
            }
        }

        // Correlation autopilot: detect dynamic values in a HAR and write a report.
        // A standalone analysis step (no CLI needed); if a prompt also follows, the
        // generated report can inform the AI's next action.
        if (options.correlateFrom != null && !options.correlateFrom.isEmpty()) {
            String har = new String(Files.readAllBytes(Paths.get(options.correlateFrom)),
                    StandardCharsets.UTF_8);
            java.util.List<org.qainsights.jmeter.ai.correlation.CorrelationCandidate> candidates;
            try {
                candidates = org.qainsights.jmeter.ai.correlation.HarCorrelationAnalyzer.analyze(har);
            } catch (Exception e) {
                throw new HeadlessUsageException("Could not analyze HAR: " + e.getMessage());
            }
            String report = new org.qainsights.jmeter.ai.correlation.CorrelationReport(candidates)
                    .render(options.format);
            Path out = Paths.get(options.output);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.write(out, report.getBytes(StandardCharsets.UTF_8));
            System.out.println("Correlation autopilot: found " + candidates.size()
                    + " dynamic value(s) — report: " + options.output);
            boolean hasPromptForCorr = (options.prompt != null && !options.prompt.isEmpty())
                    || (options.promptFile != null && !options.promptFile.isEmpty());
            if (!hasPromptForCorr) {
                return EXIT_OK;
            }
        }

        String prompt = resolvePrompt(options);
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new HeadlessUsageException("A prompt is required (--prompt or --prompt-file)");
        }

        if (options.consensus) {
            return runConsensus(options, runner, prompt, resolveClis(options.clis));
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
            case "claude":
            case "claude code":
            case "claudecode":
                return new org.qainsights.jmeter.ai.claudecode.ClaudeCodeCliAdapter();
            default:
                throw new HeadlessUsageException(
                        "Unknown or non-headless CLI: '" + name + "'. Supported: kiro, claude");
        }
    }

    private List<BaseCliAdapter> resolveClis(String csv) {
        List<BaseCliAdapter> clis = new java.util.ArrayList<>();
        for (String part : (csv == null ? "" : csv).split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                clis.add(resolveAdapter(p));
            }
        }
        if (clis.isEmpty()) {
            throw new HeadlessUsageException("--consensus needs at least one CLI in --clis");
        }
        return clis;
    }

    /** Run the prompt across multiple CLIs and write a consensus report. */
    int runConsensus(HeadlessOptions options, ProcessRunner runner, String prompt,
                     List<BaseCliAdapter> clis) throws IOException {
        File workingDir = resolveWorkingDir(options);
        String sharedContext = prepareContext(options, workingDir);

        for (BaseCliAdapter cli : clis) {
            org.qainsights.jmeter.ai.mcp.McpConfigWriter.writeFor(cli, workingDir);
        }

        org.qainsights.jmeter.ai.consensus.ConsensusRunner consensus =
                new org.qainsights.jmeter.ai.consensus.ConsensusRunner(runner);
        List<org.qainsights.jmeter.ai.consensus.ConsensusRunner.Outcome> outcomes =
                consensus.run(prompt, workingDir.getAbsolutePath(), clis, options.timeoutSeconds);

        // Audit each launched CLI.
        for (org.qainsights.jmeter.ai.consensus.ConsensusRunner.Outcome o : outcomes) {
            if (o.available) {
                AuditLogger.recordLaunch(o.cliName + " (consensus)", "", null,
                        workingDir.getAbsolutePath(), sharedContext);
            }
        }

        org.qainsights.jmeter.ai.consensus.ConsensusReport report =
                new org.qainsights.jmeter.ai.consensus.ConsensusReport(prompt, outcomes);
        Path out = Paths.get(options.output);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.write(out, report.render(options.format).getBytes(StandardCharsets.UTF_8));

        long ok = outcomes.stream().filter(o -> o.succeeded()).count();
        System.out.println("Consensus: " + ok + "/" + outcomes.size()
                + " CLIs succeeded, agreement " + String.format("%.2f", report.agreementScore())
                + " — report: " + options.output);

        if (ok == 0) {
            return options.failOnError ? 1 : EXIT_OK;
        }
        return EXIT_OK;
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
