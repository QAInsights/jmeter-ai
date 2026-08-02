package org.qainsights.jmeter.ai.service;

import java.util.List;
import java.util.function.Consumer;

public interface AiService {
    String generateResponse(List<String> conversation);
    String generateResponse(List<String> conversation, String model);
    String getName();

    /**
     * Generates a streaming response from the AI.
     *
     * @param conversation The conversation history
     * @param model        The specific model to use for this request
     * @param tokenConsumer Callback for each token chunk
     * @param onComplete    Callback for stream completion
     * @param onError       Callback for stream error
     * @return A cancel handle as a Runnable
     */
    default Runnable generateStreamResponse(List<String> conversation, String model, Consumer<String> tokenConsumer, Runnable onComplete, Consumer<Exception> onError) {
        throw new UnsupportedOperationException("Streaming not implemented for " + getName());
    }

    /**
     * Streaming overload with a separate channel for reasoning (thinking) tokens.
     * The default implementation ignores reasoning and delegates to the 5-arg
     * method so providers without reasoning support keep working unchanged.
     *
     * @param conversation      The conversation history
     * @param model             The specific model to use for this request
     * @param tokenConsumer     Callback for each answer token chunk
     * @param reasoningConsumer Callback for each reasoning token chunk
     * @param onComplete        Callback for stream completion
     * @param onError           Callback for stream error
     * @return A cancel handle as a Runnable
     */
    default Runnable generateStreamResponse(List<String> conversation, String model, Consumer<String> tokenConsumer,
            Consumer<String> reasoningConsumer, Runnable onComplete, Consumer<Exception> onError) {
        return generateStreamResponse(conversation, model, tokenConsumer, onComplete, onError);
    }

    /**
     * Injects the user's reasoning (thinking/effort) choices. Services that
     * support reasoning override this; the default is a no-op so the remaining
     * providers don't change.
     */
    default void setReasoningSettings(org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings settings) {
        // no reasoning support by default
    }

    /**
     * Returns and clears the reasoning text captured from the last non-streaming
     * response (e.g. a Claude thinking block or a DeepSeek reasoning_content
     * field), or null when there was none. Used by the UI to render the
     * collapsible thinking card on the non-streaming path.
     */
    default String consumeLastReasoning() {
        return null;
    }
}
