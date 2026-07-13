package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.correlation.CorrelationApplier;
import org.qainsights.jmeter.ai.agent.correlation.CorrelationCandidateStore;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.correlation.CorrelationCandidate;

/**
 * The {@code apply_correlation} agent tool. Approves and applies specific candidates from
 * the last {@code find_correlation_candidates} call: for each, adds a regex/JSON extractor
 * to the candidate's source sampler and rewrites every reusing sampler's matching value to
 * {@code ${variableName}} - the same mutation {@code CorrelationInjector} performs for the
 * native Correlation Studio dialog's own "Apply Selected" button.
 * <p>
 * Destructive - registered in {@code JMeterAgent.DESTRUCTIVE_TOOLS} and gated behind
 * confirmation, the same as {@code delete_element}/{@code move_element}/{@code open_plan}.
 */
public final class ApplyCorrelationHandler {

    public static final String APPLY_CORRELATION = "apply_correlation";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_NO_CANDIDATES = "no_candidates";
    public static final String ERR_NO_SELECTION = "no_selection";
    public static final String ERR_INVALID_CANDIDATE_ID = "invalid_candidate_id";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final CorrelationApplier applier;

    /** Production constructor wiring the live JMeter tree and correlation injector. */
    public ApplyCorrelationHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, CorrelationApplier.live());
    }

    public ApplyCorrelationHandler(Supplier<JMeterTreeNode> rootSupplier, CorrelationApplier applier) {
        this.rootSupplier = rootSupplier;
        this.applier = applier;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(APPLY_CORRELATION)
                .description("Applies specific correlation candidates from the last find_correlation_candidates "
                        + "call: adds an extractor to each candidate's source sampler and rewrites every reusing "
                        + "sampler's matching value to a ${variable} reference. Select candidates with "
                        + "candidate_ids (the ids returned by find_correlation_candidates), or set apply_all to "
                        + "apply every candidate found.")
                .addParameter(ToolParameter.builder("candidate_ids", ParamType.STRING_ARRAY)
                        .description("1-based candidate ids from the last find_correlation_candidates result, "
                                + "e.g. [\"1\", \"3\"]. Ignored if apply_all is true.")
                        .build())
                .addParameter(ToolParameter.builder("apply_all", ParamType.BOOLEAN)
                        .description("Apply every candidate from the last find_correlation_candidates result. "
                                + "Default false.")
                        .build())
                .addPrecondition("find_correlation_candidates must have been called first")
                .addPrecondition("either candidate_ids or apply_all=true must be given")
                .build();

        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return handle(arguments);
            }
        };
    }

    private ToolResult handle(Map<String, Object> args) {
        if (rootSupplier.get() == null) {
            return ToolResult.error(ERR_NO_TEST_PLAN, "No test plan is currently open.");
        }

        List<CorrelationCandidate> stored = CorrelationCandidateStore.get();
        if (stored.isEmpty()) {
            return ToolResult.error(ERR_NO_CANDIDATES,
                    "No correlation candidates found yet. Call find_correlation_candidates first.");
        }

        boolean applyAll = Boolean.TRUE.equals(args.get("apply_all"));
        List<String> ids = stringList(args.get("candidate_ids"));
        if (!applyAll && ids.isEmpty()) {
            return ToolResult.error(ERR_NO_SELECTION,
                    "Give candidate_ids (from find_correlation_candidates) or set apply_all=true.");
        }

        List<CorrelationCandidate> selected;
        if (applyAll) {
            selected = new ArrayList<>(stored);
        } else {
            Set<Integer> indices = new LinkedHashSet<>();
            for (String id : ids) {
                int index = parseIndex(id);
                if (index < 1 || index > stored.size()) {
                    return ToolResult.error(ERR_INVALID_CANDIDATE_ID,
                            "'" + id + "' is not a valid candidate id. Call find_correlation_candidates again "
                                    + "to get current ids (1-" + stored.size() + ").");
                }
                indices.add(index);
            }
            selected = new ArrayList<>();
            for (int index : indices) {
                selected.add(stored.get(index - 1));
            }
        }

        for (CorrelationCandidate c : selected) {
            c.setStatus(CorrelationCandidate.Status.APPROVED);
        }
        int applied = applier.apply(selected);

        return ToolResult.ok(format(applied, selected));
    }

    private static String format(int applied, List<CorrelationCandidate> selected) {
        StringBuilder sb = new StringBuilder("Applied ").append(applied).append(" correlation extractor(s).");
        for (CorrelationCandidate c : selected) {
            sb.append("\n- ").append(c.getParameterName()).append(" -> ${").append(c.getVariableName())
                    .append("} extracted from '").append(c.getSourceSamplerName()).append("'; replaced in: ")
                    .append(String.join(", ", c.getTargetSamplerNames()));
        }
        return sb.toString();
    }

    private static int parseIndex(String id) {
        if (id == null) {
            return -1;
        }
        try {
            return Integer.parseInt(id.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }
}
