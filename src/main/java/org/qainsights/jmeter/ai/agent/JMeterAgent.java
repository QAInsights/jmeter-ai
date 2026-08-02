package org.qainsights.jmeter.ai.agent;

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
import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ChatModel;
import org.qainsights.jmeter.ai.agent.openai.OpenAiChatModel;
import org.qainsights.jmeter.ai.agent.openai.OpenAiToolAdapter;
import org.qainsights.jmeter.ai.agent.schema.SchemaGrounding;
import org.qainsights.jmeter.ai.agent.tool.AgentToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolConfirmationGate;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.agent.tool.handlers.ApplyCorrelationHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.MoveElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.OpenPlanHandler;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.qainsights.jmeter.ai.utils.AiConfig;

import com.anthropic.client.AnthropicClient;
import com.openai.client.OpenAIClient;

/**
 * Façade that wires the tool registry, executor, schema-grounded system prompt
 * and a provider {@link ChatModel} (Claude or OpenAI, via
 * {@link AgentChatModelFactory}) into a runnable {@link AgentLoop}. This is the
 * single entry point the chat UI calls to run an agentic request.
 */
public final class JMeterAgent {

    public static final String ENABLED_KEY = "jmeter.ai.agent.enabled";
    public static final String MAX_TOKENS_KEY = "jmeter.ai.agent.max.tokens";
    public static final String MAX_ITERATIONS_KEY = "jmeter.ai.agent.max.iterations";
    public static final String CONFIRM_DESTRUCTIVE_KEY = "jmeter.ai.agent.confirm.destructive";
    public static final String THINKING_EFFORT_KEY = "jmeter.ai.agent.thinking.effort";

    /** Max prior user/assistant turn *pairs* seeded into a run, to bound token usage. */
    private static final int MAX_HISTORY_TURN_PAIRS = 10;

    /** Tool names that require confirmation via the {@link ToolConfirmationGate} when set. */
    private static final Set<String> DESTRUCTIVE_TOOLS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(DeleteElementHandler.DELETE_ELEMENT, MoveElementHandler.MOVE_ELEMENT,
                    OpenPlanHandler.OPEN_PLAN, ApplyCorrelationHandler.APPLY_CORRELATION)));

    /** Ensures the undo-history nudge (see {@link #maybeWarnAboutUndoHistory}) fires once per session. */
    private static final AtomicBoolean UNDO_NUDGE_SHOWN = new AtomicBoolean(false);

    private final AgentChatModelFactory chatModelFactory;
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
        this(claudeFactory(service, model, maxTokens), maxIterations, confirmationGate);
    }

    /**
     * Provider-neutral constructor: any {@link AgentChatModelFactory} (Claude, OpenAI, ...)
     * can drive the same tool registry, system prompt and loop.
     */
    public JMeterAgent(AgentChatModelFactory chatModelFactory, int maxIterations,
                       ToolConfirmationGate confirmationGate) {
        if (chatModelFactory == null) {
            throw new IllegalArgumentException("chatModelFactory must not be null");
        }
        this.chatModelFactory = chatModelFactory;
        this.maxIterations = maxIterations;
        this.confirmationGate = confirmationGate;
    }

    /** Builds a factory that wires the Anthropic {@link ClaudeChatModel} for each run. */
    public static AgentChatModelFactory claudeFactory(ClaudeChatModel.MessageService service, String model,
                                                      long maxTokens) {
        return claudeFactory(service, model, maxTokens, null);
    }

    /**
     * Builds a factory that wires the Anthropic {@link ClaudeChatModel} for each run,
     * applying the user's reasoning settings (extended thinking on capable models).
     * The effort comes from {@code jmeter.ai.agent.thinking.effort} when set,
     * otherwise from the toolbar selection.
     */
    public static AgentChatModelFactory claudeFactory(ClaudeChatModel.MessageService service, String model,
                                                      long maxTokens, ReasoningSettings reasoningSettings) {
        Long thinkingBudget = thinkingBudgetFor(reasoningSettings, model);
        String effort = thinkingBudget == null ? null : effectiveAgentEffort(reasoningSettings);
        return (specs, systemPrompt, priorTurns) -> new ClaudeChatModel(service, new ClaudeToolAdapter(),
                specs, systemPrompt, model, maxTokens, ClaudeChatModel.toSeedHistory(priorTurns),
                thinkingBudget, effort);
    }

    /**
     * The effort level for an agent run: the {@code jmeter.ai.agent.thinking.effort}
     * property when set (lets loops run cheaper than the chat), otherwise the
     * toolbar's current selection.
     */
    static String effectiveAgentEffort(ReasoningSettings settings) {
        String override = AiConfig.getProperty(THINKING_EFFORT_KEY, "").trim();
        if (!override.isEmpty()) {
            return override.toLowerCase(java.util.Locale.ROOT);
        }
        return settings != null ? settings.getEffort() : "medium";
    }

    /**
     * The thinking budget for an agent run, or null when thinking does not
     * apply. Adaptive-thinking models (fable family) get the marker value 1:
     * {@link ClaudeChatModel} substitutes the adaptive config and ignores the
     * number.
     */
    private static Long thinkingBudgetFor(ReasoningSettings settings, String model) {
        if (!org.qainsights.jmeter.ai.service.reasoning.AnthropicThinking.applies(settings, model)) {
            return null;
        }
        if (org.qainsights.jmeter.ai.service.reasoning.AnthropicThinking.isAdaptiveThinkingModel(model)) {
            return 1L;
        }
        return org.qainsights.jmeter.ai.service.reasoning.ReasoningCapabilities
                .anthropicBudgetTokens(effectiveAgentEffort(settings));
    }

    /** Builds a factory that wires the OpenAI {@link OpenAiChatModel} for each run. */
    public static AgentChatModelFactory openAiFactory(OpenAiChatModel.CompletionService service, String model,
                                                      long maxTokens) {
        return openAiFactory(service, model, maxTokens, null);
    }

    /**
     * Builds a factory that wires the OpenAI {@link OpenAiChatModel} for each run,
     * applying the user's reasoning settings (subject to the tool-calling policy).
     */
    public static AgentChatModelFactory openAiFactory(OpenAiChatModel.CompletionService service, String model,
                                                      long maxTokens, ReasoningSettings reasoningSettings) {
        return (specs, systemPrompt, priorTurns) -> new OpenAiChatModel(service, new OpenAiToolAdapter(),
                specs, systemPrompt, model, maxTokens, OpenAiChatModel.toSeedHistory(priorTurns), reasoningSettings);
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
        return new JMeterAgent(claudeFactory(service, claude.getCurrentModel(), maxTokens,
                claude.getReasoningSettings()), maxIterations, gate);
    }

    /**
     * Wires an agent against an existing {@link OpenAiService}'s client and model, using the
     * same tool registry, system prompt, limits and destructive-tool confirmation as
     * {@link #forClaude(ClaudeService)}.
     */
    public static JMeterAgent forOpenAi(OpenAiService openAi) {
        long maxTokens = parseLong(AiConfig.getProperty(MAX_TOKENS_KEY, "4096"), 4096L);
        int maxIterations = (int) parseLong(AiConfig.getProperty(MAX_ITERATIONS_KEY, "8"), 8L);
        OpenAIClient client = openAi.getClient();
        OpenAiChatModel.CompletionService service = params -> client.chat().completions().create(params);
        return new JMeterAgent(openAiFactory(service, openAi.getCurrentModel(), maxTokens,
                openAi.getReasoningSettings()), maxIterations, destructiveGate());
    }

    /**
     * Wires an agent for whichever provider backs {@code service}, or returns {@code null}
     * if that provider has no tool-calling adapter yet (the caller then falls back to the
     * plain, non-agentic chat path).
     */
    public static JMeterAgent forService(AiService service) {
        if (service instanceof ClaudeService) {
            return forClaude((ClaudeService) service);
        }
        if (service instanceof OpenAiService) {
            return forOpenAi((OpenAiService) service);
        }
        return null;
    }

    /**
     * The provider chat-model factory backing {@code service}, or {@code null} if that
     * provider has no tool-calling adapter.
     * <p>
     * Exposed for callers that drive their own tool registry rather than the default
     * JMeter one - Record Mode advertises browser tools instead - so provider detection
     * and the token/model settings live in exactly one place.
     */
    public static AgentChatModelFactory chatModelFactoryFor(AiService service) {
        long maxTokens = parseLong(AiConfig.getProperty(MAX_TOKENS_KEY, "4096"), 4096L);
        if (service instanceof ClaudeService) {
            ClaudeService claude = (ClaudeService) service;
            AnthropicClient client = claude.getClient();
            ClaudeChatModel.MessageService messages = params -> client.messages().create(params);
            return claudeFactory(messages, claude.getCurrentModel(), maxTokens, claude.getReasoningSettings());
        }
        if (service instanceof OpenAiService) {
            OpenAiService openAi = (OpenAiService) service;
            OpenAIClient client = openAi.getClient();
            OpenAiChatModel.CompletionService completions =
                    params -> client.chat().completions().create(params);
            return openAiFactory(completions, openAi.getCurrentModel(), maxTokens, openAi.getReasoningSettings());
        }
        return null;
    }

    /** The confirmation gate for destructive tools, or {@code null} when disabled by config. */
    private static ToolConfirmationGate destructiveGate() {
        boolean confirmDestructive = Boolean.parseBoolean(AiConfig.getProperty(CONFIRM_DESTRUCTIVE_KEY, "true"));
        return confirmDestructive ? new SwingToolConfirmationGate() : null;
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
        return run(userMessage, priorConversationTurns, progress, null);
    }

    /**
     * Same as {@link #run(String, List, Consumer)}, additionally notifying
     * {@code onToolCallStarted} with each tool call's raw {@link AssistantTurn.ToolCall}
     * just before it executes - e.g. to drive a UI highlight of whatever element a tool
     * call targets (see {@code TreeActivityGlowController}).
     *
     * @param onToolCallStarted notified with each tool call about to run; may be null
     */
    public AgentLoop.AgentResult run(String userMessage, List<String> priorConversationTurns, Consumer<String> progress,
                                      Consumer<AssistantTurn.ToolCall> onToolCallStarted) {
        return run(userMessage, priorConversationTurns, progress, onToolCallStarted, null);
    }

    /**
     * Same as {@link #run(String, List, Consumer, Consumer)}, additionally forwarding
     * each turn's thinking text (extended thinking) to {@code reasoning}, so the UI
     * can render it in a collapsed thoughts card per run.
     *
     * @param reasoning receives each turn's thinking text (may be null)
     */
    public AgentLoop.AgentResult run(String userMessage, List<String> priorConversationTurns, Consumer<String> progress,
                                      Consumer<AssistantTurn.ToolCall> onToolCallStarted, Consumer<String> reasoning) {
        maybeWarnAboutUndoHistory(progress);
        ToolRegistry registry = AgentToolRegistry.createDefault();
        ToolExecutor executor = new ToolExecutor(registry, DESTRUCTIVE_TOOLS, confirmationGate);
        String systemPrompt = AgentSystemPrompt.build(new SchemaGrounding());
        List<String> seedTurns = ConversationSeed.normalize(priorConversationTurns, MAX_HISTORY_TURN_PAIRS);
        List<ToolSpec> specs = registry.getSpecs();
        ChatModel chat = chatModelFactory.create(specs, systemPrompt, seedTurns);
        return new AgentLoop(chat, executor, maxIterations)
                .run(userMessage, progress, onToolCallStarted, reasoning);
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
