package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import org.qainsights.jmeter.ai.service.usage.UsageStats;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void shouldRecordUsageStatsFromConverseResponse() {
        BedrockRuntimeClient runtimeClient = mock(BedrockRuntimeClient.class);
        Message responseMessage = Message.builder()
                .role("assistant")
                .content(ContentBlock.fromText("Converse response"))
                .build();
        when(runtimeClient.converse(any(ConverseRequest.class))).thenReturn(
                ConverseResponse.builder()
                        .output(ConverseOutput.fromMessage(responseMessage))
                        .stopReason("END_TURN")
                        .usage(TokenUsage.builder().inputTokens(12).outputTokens(7).build())
                        .build());
        BedrockConverseClient client = new BedrockConverseClient(runtimeClient, null);
        UsageStats stats = new UsageStats();
        client.setUsageStats(stats);

        client.generateResponse(List.of("Hello"), "minimax.minimax-m2.5",
                "You are helpful.", 0.5f, 1024);

        assertEquals(1, stats.snapshot().calls());
        assertEquals(12, stats.snapshot().totalInput());
        assertEquals(7, stats.snapshot().totalOutput());
        assertEquals(12, stats.lastInputTokens());
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

    private static final class CapturedStream {
        final ConverseStreamRequest request;
        final ConverseStreamResponseHandler handler;
        final StreamCallbacks callbacks;

        CapturedStream(ConverseStreamRequest request, ConverseStreamResponseHandler handler,
                       StreamCallbacks callbacks) {
            this.request = request;
            this.handler = handler;
            this.callbacks = callbacks;
        }
    }

    private static CapturedStream startStream(BedrockConverseClient client, BedrockRuntimeAsyncClient asyncClient,
                                              List<String> conversation) {
        StreamCallbacks callbacks = new StreamCallbacks();
        client.generateStreamResponse(conversation, "anthropic.claude-sonnet-4-6", "You are helpful.",
                0.5f, 1024, callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError,
                0, null, false);
        ArgumentCaptor<ConverseStreamRequest> request = ArgumentCaptor.forClass(ConverseStreamRequest.class);
        ArgumentCaptor<ConverseStreamResponseHandler> handler =
                ArgumentCaptor.forClass(ConverseStreamResponseHandler.class);
        verify(asyncClient).converseStream(request.capture(), handler.capture());
        return new CapturedStream(request.getValue(), handler.getValue(), callbacks);
    }

    private static BedrockRuntimeAsyncClient pendingAsyncClient() {
        BedrockRuntimeAsyncClient asyncClient = mock(BedrockRuntimeAsyncClient.class);
        when(asyncClient.converseStream(any(ConverseStreamRequest.class),
                any(ConverseStreamResponseHandler.class)))
                .thenReturn(new CompletableFuture<>());
        return asyncClient;
    }

    /** Replays events through the handler the same way the SDK does: response, event stream, then complete. */
    private static void replay(ConverseStreamResponseHandler handler, ConverseStreamOutput... events) {
        handler.responseReceived(ConverseStreamResponse.builder().build());
        handler.onEventStream(SdkPublisher.adapt(new ListPublisher<>(List.of(events))));
        handler.complete();
    }

    private static ConverseStreamOutput textDelta(String text) {
        return ConverseStreamOutput.contentBlockDeltaBuilder()
                .contentBlockIndex(0)
                .delta(ContentBlockDelta.fromText(text))
                .build();
    }

    private static ConverseStreamOutput reasoningDelta(String text) {
        return ConverseStreamOutput.contentBlockDeltaBuilder()
                .contentBlockIndex(0)
                .delta(ContentBlockDelta.fromReasoningContent(ReasoningContentBlockDelta.fromText(text)))
                .build();
    }

    @Test
    void streamDeliversTextAndReasoningSkipsEmptyDeltasRecordsUsageAndCompletes() throws Exception {
        BedrockRuntimeAsyncClient asyncClient = pendingAsyncClient();
        BedrockConverseClient client = new BedrockConverseClient(null, asyncClient);
        UsageStats stats = new UsageStats();
        client.setUsageStats(stats);

        CapturedStream stream = startStream(client, asyncClient,
                List.of("Add a sampler", "", "Added HTTP sampler", "Now add a timer"));
        replay(stream.handler,
                ConverseStreamOutput.messageStartBuilder().role(ConversationRole.ASSISTANT).build(),
                reasoningDelta("Consider pacing."),
                reasoningDelta(""),
                textDelta(""),
                textDelta("Add a "),
                textDelta("Constant Timer."),
                ConverseStreamOutput.messageStopBuilder().stopReason("end_turn").build(),
                ConverseStreamOutput.metadataBuilder()
                        .usage(TokenUsage.builder().inputTokens(31).outputTokens(9).totalTokens(40).build())
                        .build());
        stream.callbacks.awaitTerminal();

        assertEquals(List.of("Consider pacing."), stream.callbacks.reasoning);
        assertEquals(List.of("Add a ", "Constant Timer."), stream.callbacks.tokens);
        assertEquals(1, stream.callbacks.completions.get());
        assertTrue(stream.callbacks.errors.isEmpty());
        assertEquals(1, stats.snapshot().calls());
        assertEquals(31, stats.snapshot().totalInput());
        assertEquals(9, stats.snapshot().totalOutput());

        ConverseStreamRequest request = stream.request;
        assertEquals("anthropic.claude-sonnet-4-6", request.modelId());
        assertEquals("You are helpful.", request.system().get(0).text());
        assertEquals(List.of(ConversationRole.USER, ConversationRole.ASSISTANT, ConversationRole.USER),
                request.messages().stream().map(Message::role).collect(Collectors.toList()));
        assertEquals(List.of("Add a sampler", "Added HTTP sampler", "Now add a timer"),
                request.messages().stream().map(m -> m.content().get(0).text()).collect(Collectors.toList()));
        assertEquals(0.5f, request.inferenceConfig().temperature(), 1e-6);
        assertEquals(1024, request.inferenceConfig().maxTokens());
    }

    @Test
    void streamWithoutMetadataCompletesWithoutRecordingUsage() throws Exception {
        BedrockRuntimeAsyncClient asyncClient = pendingAsyncClient();
        BedrockConverseClient client = new BedrockConverseClient(null, asyncClient);
        UsageStats stats = new UsageStats();
        client.setUsageStats(stats);

        CapturedStream stream = startStream(client, asyncClient, List.of("Hello"));
        replay(stream.handler, textDelta("Hi"));
        stream.callbacks.awaitTerminal();

        assertEquals(List.of("Hi"), stream.callbacks.tokens);
        assertEquals(1, stream.callbacks.completions.get());
        assertEquals(0, stats.snapshot().calls());
    }

    @Test
    void streamExceptionIsPassedThroughToOnError() throws Exception {
        BedrockRuntimeAsyncClient asyncClient = pendingAsyncClient();
        BedrockConverseClient client = new BedrockConverseClient(null, asyncClient);
        CapturedStream stream = startStream(client, asyncClient, List.of("Hello"));
        IllegalStateException failure = new IllegalStateException("throttled");

        stream.handler.exceptionOccurred(failure);
        stream.callbacks.awaitTerminal();

        assertEquals(List.of(failure), stream.callbacks.errors);
        assertEquals(0, stream.callbacks.completions.get());
    }

    @Test
    void streamThrowableIsWrappedInRuntimeException() throws Exception {
        BedrockRuntimeAsyncClient asyncClient = pendingAsyncClient();
        BedrockConverseClient client = new BedrockConverseClient(null, asyncClient);
        CapturedStream stream = startStream(client, asyncClient, List.of("Hello"));
        AssertionError failure = new AssertionError("stream broke");

        stream.handler.exceptionOccurred(failure);
        stream.callbacks.awaitTerminal();

        assertEquals(1, stream.callbacks.errors.size());
        Exception error = stream.callbacks.errors.get(0);
        assertInstanceOf(RuntimeException.class, error);
        assertSame(failure, error.getCause());
        assertEquals(0, stream.callbacks.completions.get());
    }

    /** Minimal reactive-streams publisher honouring back-pressure, for replaying canned events. */
    private static final class ListPublisher<T> implements Publisher<T> {
        private final List<T> items;

        ListPublisher(List<T> items) {
            this.items = items;
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber) {
            subscriber.onSubscribe(new Subscription() {
                private int next;
                private long demand;
                private boolean emitting;
                private boolean done;

                @Override
                public void request(long n) {
                    demand += n;
                    if (emitting) {
                        return;
                    }
                    emitting = true;
                    while (demand > 0 && next < items.size()) {
                        demand--;
                        subscriber.onNext(items.get(next++));
                    }
                    if (next == items.size() && !done) {
                        done = true;
                        subscriber.onComplete();
                    }
                    emitting = false;
                }

                @Override
                public void cancel() {
                    next = items.size();
                    done = true;
                }
            });
        }
    }
}
