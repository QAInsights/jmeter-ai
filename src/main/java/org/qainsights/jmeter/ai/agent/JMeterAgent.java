package org.qainsights.jmeter.ai.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.apache.jmeter.gui.UndoHistory;
import org.qainsights.jmeter.ai.agent.claude.ClaudeChatModel;
import org.qainsights.jmeter.ai.agent.claude.ClaudeToolAdapter;
import org.qainsights.jmeter.ai.agent.jmeter.SwingToolConfirmationGate;
import org.qainsights.jmeter.ai.agent.loop.AgentLoop;
import org.qainsights.jmeter.ai.agent.schema.SchemaGrounding;
import org.qainsights.jmeter.ai.agent.tool.AgentToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolConfirmationGate;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.handlers.ApplyCorrelationHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.MoveElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.OpenPlanHandler;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.utils.AiConfig;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageParam;

/**
 * Façade that wires the tool registry, executor, schema-grounded system prompt
 * and a provider {@link ClaudeChatModel} into a runnable {@link AgentLoop}. This
 * is the single entry point the chat UI calls to run an agentic request.
 */
public final class JMeterAgent {

    public static final String ENABLED_KEY = "jmeter.ai.agent.enabled";
    public static final String MAX_TOKENS_KEY = "jmeter.ai.agent.max.tokens";
    public static final String MAX_ITERATIONS_KEY = "jmeter.ai.agent.max.iterations";
    public static final String CONFIRM_DESTRUCTIVE_KEY = "jmeter.ai.agent.confirm.destructive";

    /** Max prior user/assistant turn *pairs* seeded into a run, to bound token usage. */
    private static final int MAX_HISTORY_TURN_PAIRS = 10;

    /** Tool names that require confirmation via the {@link ToolConfirmationGate} when set. */
    private static final Set<String> DESTRUCTIVE_TOOLS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(DeleteElementHandler.DELETE_ELEMENT, MoveElementHandler.MOVE_ELEMENT,
                    OpenPlanHandler.OPEN_PLAN, ApplyCorrelationHandler.APPLY_CORRELATION)));

    /** Ensures the undo-history nudge (see {@link #maybeWarnAboutUndoHistory}) fires once per session. */
    private static final AtomicBoolean UNDO_NUDGE_SHOWN = new AtomicBoolean(false);

    private final ClaudeChatModel.MessageService service;
    private final String model;
    private final long maxTokens;
    private final int maxIterations;
    private final ToolConfirmationGate confirmationGate;

    public JMeterAgent(ClaudeChatModel.MessageService service, String model, long maxTokens, int maxIterations) {
        this(service, model, maxTokens, maxIterations, null);
    }

    /**
     * @param confirmationGate asked before running a destructive tool ({@code delete_element},
     *                          {@code move_element}, {@code open_plan}, {@code apply_correlation});
     *                          {@code null} runs destructive tools without confirmation
     */
    public JMeterAgent(ClaudeChatModel.MessageService service, String model, long maxTokens, int maxIterations,
                       ToolConfirmationGate confirmationGate) {
        this.service = service;
        this.model = model;
        this.maxTokens = maxTokens;
        this.maxIterations = maxIterations;
        this.confirmationGate = confirmationGate;
    }

    /** True if the agent mode is enabled via {@code jmeter.ai.agent.enabled}. */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(AiConfig.getProperty(ENABLED_KEY, "false"));
    }

    /**
     * Wires an agent against an existing {@link ClaudeService}'s client and model. Destructive
     * tools (delete/move/open_plan/apply_correlation) are gated behind a confirmation dialog
     * unless {@code jmeter.ai.agent.confirm.destructive} is set to {@code false}.
     */
    public static JMeterAgent forClaude(ClaudeService claude) {
        long maxTokens = parseLong(AiConfig.getProperty(MAX_TOKENS_KEY, "4096"), 4096L);
        int maxIterations = (int) parseLong(AiConfig.getProperty(MAX_ITERATIONS_KEY, "8"), 8L);
        AnthropicClient client = claude.getClient();
        ClaudeChatModel.MessageService service = params -> client.messages().create(params);
        boolean confirmDestructive = Boolean.parseBoolean(AiConfig.getProperty(CONFIRM_DESTRUCTIVE_KEY, "true"));
        ToolConfirmationGate gate = confirmDestructive ? new SwingToolConfirmationGate() : null;
        return new JMeterAgent(service, claude.getCurrentModel(), maxTokens, maxIterations, gate);
    }

    /**
     * Runs an agentic request against the live JMeter tree with no prior conversation
     * context.
     *
     * @param userMessage the user's request
     * @param progress    receives human-readable progress lines (may be null)
     * @return the loop outcome
     */
    public AgentLoop.AgentResult run(String userMessage, Consumer<String> progress) {
        return run(userMessage, Collections.emptyList(), progress);
    }

    /**
     * Runs an agentic request against the live JMeter tree, seeding the model with prior
     * plain-text conversation turns so follow-up requests ("now add a header") retain
     * context across separate chat messages.
     *
     * @param userMessage           the user's request
     * @param priorConversationTurns earlier turns in strict user/assistant/user/... order
     *                                (e.g. from the chat panel's conversation history),
     *                                not including {@code userMessage} itself; may be null
     * @param progress              receives human-readable progress lines (may be null)
     * @return the loop outcome
     */
    public AgentLoop.AgentResult run(String userMessage, List<String> priorConversationTurns, Consumer<String> progress) {
        maybeWarnAboutUndoHistory(progress);
        ToolRegistry registry = AgentToolRegistry.createDefault();
        ToolExecutor executor = new ToolExecutor(registry, DESTRUCTIVE_TOOLS, confirmationGate);
        String systemPrompt = AgentSystemPrompt.build(new SchemaGrounding());
        List<MessageParam> seedHistory = toSeedHistory(priorConversationTurns);
        ClaudeChatModel chat = new ClaudeChatModel(service, new ClaudeToolAdapter(),
                registry.getSpecs(), systemPrompt, model, maxTokens, seedHistory);
        return new AgentLoop(chat, executor, maxIterations).run(userMessage, progress);
    }

    /**
     * Converts flat alternating user/assistant strings into seed {@link MessageParam}s,
     * dropping a trailing unpaired turn (so the seed always ends on an assistant turn) and
     * capping to the most recent {@link #MAX_HISTORY_TURN_PAIRS} pairs.
     */
    private static List<MessageParam> toSeedHistory(List<String> priorConversationTurns) {
        if (priorConversationTurns == null || priorConversationTurns.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> turns = new ArrayList<>(priorConversationTurns);
        if (turns.size() % 2 != 0) {
            turns.remove(turns.size() - 1);
        }
        int maxEntries = MAX_HISTORY_TURN_PAIRS * 2;
        if (turns.size() > maxEntries) {
            turns = turns.subList(turns.size() - maxEntries, turns.size());
        }
        List<MessageParam> history = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            MessageParam.Role role = (i % 2 == 0) ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;
            history.add(MessageParam.builder().role(role).content(turns.get(i)).build());
        }
        return history;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Nudges the user, once per JMeter session, to enable JMeter's own Undo/Redo
     * history (disabled by default: {@code undo.history.size=0}) so agent-made
     * changes can be reverted with Ctrl+Z. No further wiring is needed once it's
     * enabled - the agent's mutations already fire the same {@code JMeterTreeModel}
     * events JMeter's own GUI actions do, and {@code UndoHistory} listens generically.
     */
    private static void maybeWarnAboutUndoHistory(Consumer<String> progress) {
        if (progress == null || UndoHistory.isEnabled()) {
            return;
        }
        if (UNDO_NUDGE_SHOWN.compareAndSet(false, true)) {
            progress.accept("[Note: JMeter's Undo/Redo is disabled by default. Add "
                    + "undo.history.size=50 (or a value you prefer) to user.properties and "
                    + "restart JMeter to be able to undo changes the agent makes.]");
        }
    }

    /** Test-only hook to reset the one-time undo nudge between test cases. */
    static void resetUndoNudgeForTests() {
        UNDO_NUDGE_SHOWN.set(false);
    }
}
