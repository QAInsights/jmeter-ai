package org.qainsights.jmeter.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class BedrockConverseClient {

    private static final Logger log = LoggerFactory.getLogger(BedrockConverseClient.class);
    private static final String DEFAULT_USER_MESSAGE = "Hello, how can you help me with JMeter?";

    private final BedrockRuntimeClient runtimeClient;
    private final BedrockRuntimeAsyncClient asyncClient;

    BedrockConverseClient(BedrockRuntimeClient runtimeClient,
                          BedrockRuntimeAsyncClient asyncClient) {
        this.runtimeClient = runtimeClient;
        this.asyncClient = asyncClient;
    }

    String generateResponse(List<String> conversation, String modelId,
                            String systemPrompt, float temperature, long maxTokens) {
        ConverseResponse response = runtimeClient.converse(buildRequest(
                conversation, modelId, systemPrompt, temperature, maxTokens));
        String text = extractText(response);
        if ("MAX_TOKENS".equals(response.stopReasonAsString()) && !text.isEmpty()) {
            return text + "\n\n[Response truncated due to max_tokens limit.]";
        }
        return text.isEmpty() ? "No content available" : text;
    }

    Runnable generateStreamResponse(List<String> conversation, String modelId,
                                    String systemPrompt, float temperature, long maxTokens,
                                    Consumer<String> tokenConsumer, Runnable onComplete,
                                    Consumer<Exception> onError) {
        ConverseStreamRequest request = buildStreamRequest(
                conversation, modelId, systemPrompt, temperature, maxTokens);
        ConverseStreamResponseHandler handler = ConverseStreamResponseHandler.builder()
                .subscriber(ConverseStreamResponseHandler.Visitor.builder()
                        .onContentBlockDelta(event -> publishText(event, tokenConsumer))
                        .build())
                .onError(error -> publishError(error, onError))
                .onComplete(() -> SwingUtilities.invokeLater(onComplete))
                .build();
        CompletableFuture<Void> future = asyncClient.converseStream(request, handler);
        return () -> {
            log.info("Cancelling Bedrock Converse stream");
            if (!future.isDone()) {
                future.cancel(true);
            }
        };
    }

    private static ConverseRequest buildRequest(List<String> conversation, String modelId,
                                                String systemPrompt, float temperature,
                                                long maxTokens) {
        ConverseRequest.Builder builder = ConverseRequest.builder()
                .modelId(modelId)
                .messages(buildMessages(conversation))
                .inferenceConfig(buildInferenceConfiguration(temperature, maxTokens));
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            builder.system(SystemContentBlock.builder().text(systemPrompt).build());
        }
        return builder.build();
    }

    private static ConverseStreamRequest buildStreamRequest(List<String> conversation,
                                                            String modelId,
                                                            String systemPrompt,
                                                            float temperature,
                                                            long maxTokens) {
        ConverseStreamRequest.Builder builder = ConverseStreamRequest.builder()
                .modelId(modelId)
                .messages(buildMessages(conversation))
                .inferenceConfig(buildInferenceConfiguration(temperature, maxTokens));
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            builder.system(SystemContentBlock.builder().text(systemPrompt).build());
        }
        return builder.build();
    }

    private static InferenceConfiguration buildInferenceConfiguration(float temperature,
                                                                     long maxTokens) {
        return InferenceConfiguration.builder()
                .temperature(temperature)
                .maxTokens((int) Math.min(Math.max(maxTokens, 1), Integer.MAX_VALUE))
                .build();
    }

    private static List<Message> buildMessages(List<String> conversation) {
        List<String> messages = nonEmptyMessages(conversation);
        if (messages.isEmpty()) {
            messages = List.of(DEFAULT_USER_MESSAGE);
        }
        List<Message> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            result.add(Message.builder()
                    .role(i % 2 == 0 ? ConversationRole.USER : ConversationRole.ASSISTANT)
                    .content(ContentBlock.fromText(messages.get(i)))
                    .build());
        }
        return result;
    }

    private static String extractText(ConverseResponse response) {
        if (response == null || response.output() == null
                || response.output().message() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : response.output().message().content()) {
            if (block.text() != null && !block.text().isEmpty()) {
                text.append(block.text());
            }
        }
        return text.toString();
    }

    private static void publishText(ContentBlockDeltaEvent event, Consumer<String> consumer) {
        if (event.delta() != null && event.delta().text() != null
                && !event.delta().text().isEmpty()) {
            SwingUtilities.invokeLater(() -> consumer.accept(event.delta().text()));
        }
    }

    private static void publishError(Throwable error, Consumer<Exception> consumer) {
        Exception exception = error instanceof Exception
                ? (Exception) error : new RuntimeException(error);
        log.error("Error in Bedrock Converse stream", exception);
        SwingUtilities.invokeLater(() -> consumer.accept(exception));
    }

    private static List<String> nonEmptyMessages(List<String> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return List.of();
        }
        return conversation.stream()
                .filter(message -> message != null && !message.isEmpty())
                .toList();
    }
}
