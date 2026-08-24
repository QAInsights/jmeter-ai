package org.qainsights.jmeter.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.qainsights.jmeter.ai.cli.CliProviderException;
import org.qainsights.jmeter.ai.cli.SubscriptionCliProvider;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared {@link AiService} for the CLI-backed providers (Codex, Claude Code).
 * The transport is a one-shot non-interactive process per request, so the
 * conversation is replayed as a flat transcript on every call; the CLI keeps no
 * server-side session for us.
 * <p>
 * The CLIs stream their own progress to the terminal, not to us, so "streaming"
 * here means: run the prompt on a background thread and emit the finished
 * answer in one chunk. Nothing blocks the Swing EDT.
 */
public abstract class CliSubscriptionAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(CliSubscriptionAiService.class);

    /** Property for how many prior conversation entries to replay. */
    public static final String MAX_HISTORY_KEY = "jmeter.ai.cli.max.history.size";

    private final SubscriptionCliProvider provider;

    protected CliSubscriptionAiService(SubscriptionCliProvider provider) {
        this.provider = provider;
    }

    /** The underlying CLI provider (also used by the agent wiring and the UI). */
    public SubscriptionCliProvider getProvider() {
        return provider;
    }

    /** The model id currently sent to the CLI ({@code ""} means the CLI's own default). */
    public String getCurrentModel() {
        return provider.getModel();
    }

    public void setModel(String model) {
        provider.setModel(model);
    }

    @Override
    public String generateResponse(List<String> conversation) {
        return generateResponse(conversation, null);
    }

    @Override
    public String generateResponse(List<String> conversation, String model) {
        if (model != null && !model.isEmpty()) {
            provider.setModel(model);
        }
        return provider.execute(buildPrompt(systemPrompt(), conversation, maxHistorySize()));
    }

    @Override
    public Runnable generateStreamResponse(List<String> conversation, String model,
                                           Consumer<String> tokenConsumer, Runnable onComplete,
                                           Consumer<Exception> onError) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            try {
                String answer = generateResponse(conversation, model);
                if (cancelled.get()) {
                    return;
                }
                tokenConsumer.accept(answer);
                onComplete.run();
            } catch (CliProviderException e) {
                if (!cancelled.get()) {
                    log.warn("{} request failed: {}", getName(), e.getMessage());
                    onError.accept(e);
                }
            } catch (RuntimeException e) {
                if (!cancelled.get()) {
                    log.error("{} request failed unexpectedly", getName(), e);
                    onError.accept(e);
                }
            }
        }, getName() + "-cli-request");
        worker.setDaemon(true);
        worker.start();
        // The CLI answer arrives as a single chunk, so cancelling only suppresses
        // the callbacks; the child process is still bounded by its own timeout.
        return () -> cancelled.set(true);
    }

    /**
     * Flattens the alternating user/assistant history into a single transcript
     * prompt, prefixed with the JMeter system prompt. Only the last
     * {@code maxHistorySize} entries are replayed, to bound the prompt size.
     */
    static String buildPrompt(String systemPrompt, List<String> conversation, int maxHistorySize) {
        List<String> history = limit(conversation, maxHistorySize);
        StringBuilder prompt = new StringBuilder(systemPrompt).append("\n\n");
        if (history.isEmpty()) {
            prompt.append("User: Hello, how can you help me with JMeter?");
            return prompt.toString();
        }
        // The history alternates user/assistant starting with the user; the last
        // entry is the message being answered now.
        int offset = (history.size() - 1) % 2 == 0 ? 0 : 1;
        for (int i = 0; i < history.size(); i++) {
            boolean user = (i + offset) % 2 == 0;
            prompt.append(user ? "User: " : "Assistant: ").append(history.get(i)).append("\n\n");
        }
        prompt.append("Answer the last user message. Reply with the answer text only.");
        return prompt.toString();
    }

    private static List<String> limit(List<String> conversation, int maxHistorySize) {
        if (conversation == null || conversation.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, conversation.size() - Math.max(1, maxHistorySize));
        return new ArrayList<>(conversation.subList(from, conversation.size()));
    }

    private static int maxHistorySize() {
        try {
            return Integer.parseInt(AiConfig.getProperty(MAX_HISTORY_KEY, "10").trim());
        } catch (RuntimeException e) {
            return 10;
        }
    }

    /** The system prompt prepended to every transcript. */
    protected String systemPrompt() {
        return Constants.DEFAULT_JMETER_SYSTEM_PROMPT;
    }
}
