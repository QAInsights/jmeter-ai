package org.qainsights.jmeter.ai.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * Mid-run growth of a focused Agent Mode tool set. When the model calls
 * {@link ExpandToolsTool}, this asks Jev to re-route the request enriched with the
 * model's stated need and registers the missing tools from the full registry into
 * the live registry the {@code ToolExecutor} already holds. The set only ever
 * grows; on uncertainty or failure it expands to the complete registry so the
 * model is never left with fewer tools than a non-routed run would have.
 */
public final class AgentToolExpander {

    private static final int BRIEF_MAX_CHARS = 160;

    /**
     * Immutable wiring for one run's expansion: the re-routing seam, the
     * sanitized routing message, the full and live registries, the expansion
     * {@code Notice} sink, and the per-run expansion cap.
     */
    public record Config(AgentRequestRouter router, String routingMessage,
                         ToolRegistry fullRegistry, ToolRegistry liveRegistry,
                         Consumer<AgentRequestRouter.Notice> noticeSink,
                         int maxExpansions) {
    }

    private final AgentRequestRouter router;
    private final String routingMessage;
    private final ToolRegistry fullRegistry;
    private final ToolRegistry liveRegistry;
    private final Consumer<AgentRequestRouter.Notice> noticeSink;
    private final int maxExpansions;
    private Consumer<List<ToolSpec>> specSink = specs -> { };
    private int expansions;

    /**
     * @param config the run's expansion wiring; {@code maxExpansions} clamps at 0
     */
    public AgentToolExpander(Config config) {
        this.router = config.router();
        this.routingMessage = config.routingMessage() == null ? "" : config.routingMessage();
        this.fullRegistry = config.fullRegistry();
        this.liveRegistry = config.liveRegistry();
        this.noticeSink = config.noticeSink();
        this.maxExpansions = Math.max(0, config.maxExpansions());
    }

    /**
     * Supplies the live registry's specs after every expansion so the run's
     * {@code ChatModel} can re-advertise the grown tool set on its next request.
     */
    public void bindSpecSink(Consumer<List<ToolSpec>> specSink) {
        this.specSink = specSink == null ? specs -> { } : specSink;
    }

    /** The {@code expand_tools} tool bound to this expander. */
    public Tool tool() {
        return new ExpandToolsTool(this::expand);
    }

    /**
     * Registers {@code expand_tools} into a focused live registry when routing
     * produced a focused decision and the expansion flag is on, then returns the
     * expander so the caller can bind {@link #bindSpecSink} once the run's
     * {@code ChatModel} exists. Returns null for full-registry runs, disabled
     * flags, or a missing router.
     */
    public static AgentToolExpander registerIfEnabled(AgentRequestRouter router, String routingMessage,
                                                    ToolRegistry fullRegistry, ToolRegistry liveRegistry,
                                                    AgentRequestRouter.Decision decision,
                                                    Consumer<AgentRequestRouter.Notice> noticeSink) {
        if (router == null || fullRegistry == null || liveRegistry == null
                || liveRegistry == fullRegistry
                || decision == null || !decision.isFocused()
                || !AgentRoutingConfig.expansionEnabled()) {
            return null;
        }
        int max = AgentRoutingConfig.expansionMax();
        if (max < 1) {
            return null;
        }
        AgentToolExpander expander = new AgentToolExpander(new Config(router, routingMessage,
                fullRegistry, liveRegistry, noticeSink, max));
        expander.registerOwnTool();
        return expander;
    }

    private void registerOwnTool() {
        liveRegistry.register(tool());
    }

    ToolResult expand(String reason) {
        if (missingTools().isEmpty()) {
            return ToolResult.ok("All Agent Mode tools are already available. Continue with them.");
        }
        if (expansions >= maxExpansions) {
            return ToolResult.ok("The tool set cannot be expanded further in this run. "
                    + "Continue with the tools you have.");
        }
        expansions++;
        AgentRequestRouter.Decision decision = reroute(reason);
        List<Tool> added = registerWanted(decision);
        if (added.isEmpty()) {
            return ToolResult.ok("Your current tools already cover this request. Continue with them.");
        }
        specSink.accept(liveRegistry.getSpecs());
        publishNotice(decision, added);
        return ToolResult.ok(describe(added));
    }

    private AgentRequestRouter.Decision reroute(String reason) {
        String need = reason == null || reason.isBlank() ? "an unspecified capability" : reason;
        try {
            AgentRequestRouter.Decision decision = router.route(routingMessage
                    + "\n\nThe agent could not complete the task with its current tools and additionally "
                    + "needs: " + need);
            return decision == null ? AgentRequestRouter.Decision.unavailable() : decision;
        } catch (RuntimeException e) {
            return AgentRequestRouter.Decision.unavailable();
        }
    }

    private List<Tool> registerWanted(AgentRequestRouter.Decision decision) {
        Set<String> wanted = decision != null && decision.isFocused()
                ? AgentToolPacks.toolNames(decision.route())
                : allNames();
        List<Tool> added = new ArrayList<>();
        for (Tool tool : missingTools()) {
            if (wanted.contains(tool.getSpec().getName())) {
                liveRegistry.register(tool);
                added.add(tool);
            }
        }
        return added;
    }

    /** Standard tools absent from the live registry, in full-registry order. */
    private List<Tool> missingTools() {
        List<Tool> missing = new ArrayList<>();
        for (Tool tool : fullRegistry.getAll()) {
            if (!liveRegistry.isRegistered(tool.getSpec().getName())) {
                missing.add(tool);
            }
        }
        return missing;
    }

    private Set<String> allNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Tool tool : fullRegistry.getAll()) {
            names.add(tool.getSpec().getName());
        }
        return names;
    }

    private void publishNotice(AgentRequestRouter.Decision decision, List<Tool> added) {
        if (noticeSink == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (ToolSpec spec : liveRegistry.getSpecs()) {
            if (!ExpandToolsTool.EXPAND_TOOLS.equals(spec.getName())) {
                names.add(spec.getName());
            }
        }
        List<String> addedNames = new ArrayList<>();
        for (Tool tool : added) {
            addedNames.add(tool.getSpec().getName());
        }
        noticeSink.accept(AgentRequestRouter.Notice.expansion(decision, names,
                fullRegistry.size(), addedNames));
    }

    private static String describe(List<Tool> added) {
        StringBuilder text = new StringBuilder("Additional tools are now available. "
                + "You may call them on your next step:\n");
        for (Tool tool : added) {
            ToolSpec spec = tool.getSpec();
            text.append("- ").append(spec.getName()).append(": ").append(brief(spec.getDescription()));
            List<String> required = new ArrayList<>();
            for (ToolParameter p : spec.getRequiredParameters()) {
                required.add(p.getName());
            }
            if (!required.isEmpty()) {
                text.append(" Required parameters: ").append(String.join(", ", required)).append(".");
            }
            text.append("\n");
        }
        return text.toString().trim();
    }

    private static String brief(String description) {
        String value = description == null ? "" : description.trim();
        int dot = value.indexOf('.');
        if (dot > 0 && dot < BRIEF_MAX_CHARS) {
            return value.substring(0, dot + 1);
        }
        return value.length() <= BRIEF_MAX_CHARS ? value : value.substring(0, BRIEF_MAX_CHARS) + "…";
    }
}
