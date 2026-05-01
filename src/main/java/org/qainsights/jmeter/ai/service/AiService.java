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
}
