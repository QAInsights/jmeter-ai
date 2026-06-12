package org.qainsights.jmeter.ai.headless;

/**
 * Parsed command-line options for {@link HeadlessAiRunner}.
 *
 * <pre>
 *   --cli &lt;name&gt;         CLI to use (default: kiro)
 *   --prompt &lt;text&gt;      the request to send (or use --prompt-file)
 *   --prompt-file &lt;path&gt; read the prompt from a file
 *   --jmx &lt;path&gt;         test plan whose (redacted) content is shared as context
 *   --working-dir &lt;path&gt; directory to run the CLI in (default: a temp dir)
 *   --output &lt;path&gt;      report file (default: ./jmeter-ai-report.md)
 *   --format md|json     report format (default: md)
 *   --timeout &lt;seconds&gt;  wall-clock limit (default: 300)
 *   --fail-on-error      exit non-zero if the CLI run fails
 *   --help               print usage
 * </pre>
 */
public final class HeadlessOptions {

    public String cli = "kiro";
    public String prompt;
    public String promptFile;
    public String jmx;
    public String generateFrom;
    public String generateOut = "generated-plan.jmx";
    public String workingDir;
    public String output = "jmeter-ai-report.md";
    public String format = "md";
    public long timeoutSeconds = 300;
    public boolean failOnError;
    public boolean consensus;
    public String clis = "kiro,claude";
    public boolean help;

    public static String usage() {
        return "Usage: HeadlessAiRunner --prompt \"<text>\" [options]\n"
                + "  --cli <name>          CLI to use (default: kiro)\n"
                + "  --prompt <text>       request to send (or --prompt-file)\n"
                + "  --prompt-file <path>  read the prompt from a file\n"
                + "  --jmx <path>          test plan shared as (redacted) context\n"
                + "  --generate-from <path> HAR or OpenAPI/Swagger (JSON) to generate a .jmx from\n"
                + "  --generate-out <path>  where to write the generated plan (default: generated-plan.jmx)\n"
                + "  --working-dir <path>  directory to run the CLI in (default: temp)\n"
                + "  --output <path>       report file (default: jmeter-ai-report.md)\n"
                + "  --format md|json      report format (default: md)\n"
                + "  --timeout <seconds>   wall-clock limit (default: 300)\n"
                + "  --fail-on-error       exit non-zero if the CLI run fails\n"
                + "  --consensus           run the prompt across multiple CLIs and diff answers\n"
                + "  --clis <a,b>          CLIs for --consensus (default: kiro,claude)\n"
                + "  --help                print this help";
    }

    /**
     * Parse argv into options.
     *
     * @throws HeadlessUsageException on an unknown flag or a missing value
     */
    public static HeadlessOptions parse(String[] args) {
        HeadlessOptions o = new HeadlessOptions();
        if (args == null) {
            return o;
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help":
                case "-h":
                    o.help = true;
                    break;
                case "--fail-on-error":
                    o.failOnError = true;
                    break;
                case "--consensus":
                    o.consensus = true;
                    break;
                case "--clis":
                    o.clis = value(args, ++i, a);
                    break;
                case "--cli":
                    o.cli = value(args, ++i, a);
                    break;
                case "--prompt":
                    o.prompt = value(args, ++i, a);
                    break;
                case "--prompt-file":
                    o.promptFile = value(args, ++i, a);
                    break;
                case "--jmx":
                    o.jmx = value(args, ++i, a);
                    break;
                case "--generate-from":
                    o.generateFrom = value(args, ++i, a);
                    break;
                case "--generate-out":
                    o.generateOut = value(args, ++i, a);
                    break;
                case "--working-dir":
                    o.workingDir = value(args, ++i, a);
                    break;
                case "--output":
                    o.output = value(args, ++i, a);
                    break;
                case "--format":
                    o.format = value(args, ++i, a).toLowerCase();
                    if (!o.format.equals("md") && !o.format.equals("json")) {
                        throw new HeadlessUsageException("--format must be 'md' or 'json'");
                    }
                    break;
                case "--timeout":
                    String t = value(args, ++i, a);
                    try {
                        o.timeoutSeconds = Long.parseLong(t.trim());
                    } catch (NumberFormatException e) {
                        throw new HeadlessUsageException("--timeout must be a number of seconds: " + t);
                    }
                    if (o.timeoutSeconds <= 0) {
                        throw new HeadlessUsageException("--timeout must be positive");
                    }
                    break;
                default:
                    throw new HeadlessUsageException("Unknown argument: " + a);
            }
        }
        return o;
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new HeadlessUsageException("Missing value for " + flag);
        }
        return args[i];
    }
}
