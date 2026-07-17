package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BedrockConverseClientTest {

    @Test
    void shouldBuildStandardConverseRequestAndExtractText() {
        BedrockRuntimeClient runtimeClient = mock(BedrockRuntimeClient.class);
        Message responseMessage = Message.builder()
                .role("assistant")
                .content(ContentBlock.fromText("Converse response"))
                .build();
        AtomicReference<ConverseRequest> request = new AtomicReference<>();
        when(runtimeClient.converse(any(ConverseRequest.class))).thenAnswer(invocation -> {
            request.set(invocation.getArgument(0));
            return ConverseResponse.builder()
                    .output(ConverseOutput.fromMessage(responseMessage))
                    .stopReason("END_TURN")
                    .build();
        });
        BedrockConverseClient client = new BedrockConverseClient(runtimeClient, null);

        String response = client.generateResponse(
                List.of("Hello", "Hi"), "minimax.minimax-m2.5",
                "You are helpful.", 0.5f, 1024);

        verify(runtimeClient).converse(any(ConverseRequest.class));

        assertEquals("Converse response", response);
        assertNotNull(request.get());
        assertEquals("minimax.minimax-m2.5", request.get().modelId());
        assertEquals(2, request.get().messages().size());
        assertEquals("You are helpful.", request.get().system().get(0).text());
        assertEquals(1024, request.get().inferenceConfig().maxTokens());
    }

    @Test
    void shouldCreateConverseStreamRequestAndCancelHandle() {
        BedrockRuntimeAsyncClient asyncClient = mock(BedrockRuntimeAsyncClient.class);
        when(asyncClient.converseStream(any(ConverseStreamRequest.class),
                any(ConverseStreamResponseHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        BedrockConverseClient client = new BedrockConverseClient(null, asyncClient);

        Runnable cancel = client.generateStreamResponse(
                List.of("Hello"), "anthropic.claude-sonnet-4-6",
                "You are helpful.", 0.5f, 1024,
                token -> {}, () -> {}, error -> {});

        assertNotNull(cancel);
        cancel.run();
        verify(asyncClient).converseStream(any(ConverseStreamRequest.class),
                any(ConverseStreamResponseHandler.class));
    }
}
