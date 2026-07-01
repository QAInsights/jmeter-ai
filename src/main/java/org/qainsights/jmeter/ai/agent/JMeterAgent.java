package org.qainsights.jmeter.ai.agent;

import java.util.function.Consumer;

import org.qainsights.jmeter.ai.agent.claude.ClaudeChatModel;
import org.qainsights.jmeter.ai.agent.claude.ClaudeToolAdapter;
import org.qainsights.jmeter.ai.agent.loop.AgentLoop;
import org.qainsights.jmeter.ai.agent.schema.SchemaGrounding;
import org.qainsights.jmeter.ai.agent.tool.AgentToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.utils.AiConfig;

import com.anthropic.client.AnthropicClient;

/**
 * Façade that wires the tool registry, executor, schema-grounded system prompt
 * and a provider {@link ClaudeChatModel} into a runnable {@link AgentLoop}. This
 * is the single entry point the chat UI calls to run an agentic request.
 */
public final class JMeterAgent {

    public static final String ENABLED_KEY = "jmeter.ai.agent.enabled";
    public static final String MAX_TOKENS_KEY = "jmeter.ai.agent.max.tokens";
    public static final String MAX_ITERATIONS_KEY = "jmeter.ai.agent.max.iterations";

    private final ClaudeChatModel.MessageService service;
    private final String model;
    private final long maxTokens;
    private final int maxIterations;

    public JMeterAgent(ClaudeChatModel.MessageService service, String model, long maxTokens, int maxIterations) {
        this.service = service;
        this.model = model;
        this.maxTokens = maxTokens;
        this.maxIterations = maxIterations;
    }

    /** True if the agent mode is enabled via {@code jmeter.ai.agent.enabled}. */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(AiConfig.getProperty(ENABLED_KEY, "false"));
    }

    /** Wires an agent against an existing {@link ClaudeService}'s client and model. */
    public static JMeterAgent forClaude(ClaudeService claude) {
        long maxTokens = parseLong(AiConfig.getProperty(MAX_TOKENS_KEY, "4096"), 4096L);
        int maxIterations = (int) parseLong(AiConfig.getProperty(MAX_ITERATIONS_KEY, "8"), 8L);
        AnthropicClient client = claude.getClient();
        ClaudeChatModel.MessageService service = params -> client.messages().create(params);
        return new JMeterAgent(service, claude.getCurrentModel(), maxTokens, maxIterations);
    }

    /**
     * Runs an agentic request against the live JMeter tree.
     *
     * @param userMessage the user's request
     * @param progress    receives human-readable progress lines (may be null)
     * @return the loop outcome
     */
    public AgentLoop.AgentResult run(String userMessage, Consumer<String> progress) {
        ToolRegistry registry = AgentToolRegistry.createDefault();
        ToolExecutor executor = new ToolExecutor(registry);
        String systemPrompt = AgentSystemPrompt.build(new SchemaGrounding());
        ClaudeChatModel chat = new ClaudeChatModel(service, new ClaudeToolAdapter(),
                registry.getSpecs(), systemPrompt, model, maxTokens);
        return new AgentLoop(chat, executor, maxIterations).run(userMessage, progress);
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
