package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.correlation.CorrelationCandidateFinder;
import org.qainsights.jmeter.ai.agent.correlation.CorrelationCandidateStore;
import org.qainsights.jmeter.ai.agent.correlation.CorrelationExecutionException;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.correlation.CorrelationCandidate;

/**
 * The {@code find_correlation_candidates} agent tool. Replays the current test plan as a
 * single-thread, single-loop probe (the same engine JMeter AI's own "Correlation Studio" ->
 * "Run &amp; Correlate" button uses) and detects dynamic values - session ids, CSRF tokens,
 * auth codes, etc. - whose response value from one sampler is reused by a later sampler's
 * request: candidates for extract-and-substitute correlation. Forces exactly 1 thread/1 loop
 * (unlike {@code get_test_results}'s as-configured run) so cross-referencing "which later
 * request reused this value" is unambiguous, rather than interleaving multiple virtual users.
 * <p>
 * This tool never mutates the test plan; it stores its findings (by 1-based id, in
 * {@link CorrelationCandidateStore}) for a later {@code apply_correlation} call.
 */
public final class FindCorrelationCandidatesHandler {

    public static final String FIND_CORRELATION_CANDIDATES = "find_correlation_candidates";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_ALREADY_RUNNING = "already_running";
    public static final String ERR_EXECUTION_FAILED = "execution_failed";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final TestRunController controller;
    private final CorrelationCandidateFinder finder;

    /** Production constructor wiring the live JMeter tree, run controller and correlation engine. */
    public FindCorrelationCandidatesHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, TestRunController.live(), CorrelationCandidateFinder.live());
    }

    public FindCorrelationCandidatesHandler(Supplier<JMeterTreeNode> rootSupplier, TestRunController controller,
                                             CorrelationCandidateFinder finder) {
        this.rootSupplier = rootSupplier;
        this.controller = controller;
        this.finder = finder;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(FIND_CORRELATION_CANDIDATES)
                .description("Replays the current test plan once (forced to 1 thread/1 loop, regardless of its "
                        + "own configured thread/loop counts) and detects dynamic values - session ids, CSRF "
                        + "tokens, auth codes, etc. - whose value from one sampler's response is reused by a "
                        + "later sampler's request. Returns each finding with a 1-based candidate id, the "
                        + "variable name it would be extracted into, its source sampler, and which later "
                        + "sampler(s) reuse it. Does not modify the test plan - call apply_correlation "
                        + "afterwards with the ids you want to keep to actually add the extractor and rewrite "
                        + "the reusing sampler(s).")
                .addPrecondition("A test plan must be open (see get_tree_state)")
                .addPrecondition("no test may currently be running (see stop_test) - this tool runs its own probe")
                .build();

        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return handle();
            }
        };
    }

    private ToolResult handle() {
        if (rootSupplier.get() == null) {
            return ToolResult.error(ERR_NO_TEST_PLAN, "No test plan is currently open.");
        }
        if (controller.isRunning()) {
            return ToolResult.error(ERR_ALREADY_RUNNING,
                    "A test is already running. Use stop_test to stop it before finding correlation candidates.");
        }

        List<CorrelationCandidate> found;
        try {
            found = finder.find();
        } catch (CorrelationExecutionException e) {
            return ToolResult.error(ERR_EXECUTION_FAILED, "Could not run the correlation probe: " + e.getMessage());
        }
        CorrelationCandidateStore.set(found);

        return ToolResult.ok(format(found));
    }

    private static String format(List<CorrelationCandidate> found) {
        if (found.isEmpty()) {
            return "No correlation candidates found - no dynamic value from one sampler's response appears to "
                    + "be reused by a later sampler.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(found.size()).append(" correlation candidate(s) found:");
        for (int i = 0; i < found.size(); i++) {
            CorrelationCandidate c = found.get(i);
            sb.append("\n").append(i + 1).append(". ").append(c.getParameterName())
                    .append(" -> ${").append(c.getVariableName()).append("} from '")
                    .append(c.getSourceSamplerName()).append("' (").append(c.getSourceLocation())
                    .append("); reused by: ").append(String.join(", ", c.getTargetSamplerNames()));
        }
        sb.append("\nCall apply_correlation with candidate_ids (or apply_all=true) to apply.");
        return sb.toString();
    }
}
