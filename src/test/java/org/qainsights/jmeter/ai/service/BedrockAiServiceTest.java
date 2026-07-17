package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.AgreementAvailability;
import software.amazon.awssdk.services.bedrock.model.FoundationModelLifecycle;
import software.amazon.awssdk.services.bedrock.model.FoundationModelSummary;
import software.amazon.awssdk.services.bedrock.model.GetFoundationModelAvailabilityRequest;
import software.amazon.awssdk.services.bedrock.model.GetFoundationModelAvailabilityResponse;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileModel;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileSummary;
import software.amazon.awssdk.services.bedrock.model.ListFoundationModelsRequest;
import software.amazon.awssdk.services.bedrock.model.ListFoundationModelsResponse;
import software.amazon.awssdk.services.bedrock.model.ListInferenceProfilesRequest;
import software.amazon.awssdk.services.bedrock.model.ListInferenceProfilesResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BedrockAiService}.
 * Tests cover service identity, model management, response generation with
 * null client (error paths), streaming with null client, conversation history
 * management, and error message filtering.
 */
class BedrockAiServiceTest {

    private BedrockAiService bedrockService;

    @BeforeEach
    void setUp() {
        bedrockService = new BedrockAiService(
                null, null, null,
                "anthropic.claude-3-5-sonnet-20241022-v2:0",
                0.5f, 10, 4096L,
                "You are a test assistant.");
    }

    // ==================== Service Identity ====================

    @Test
    void testGetName_returnsBedrock() {
        assertEquals("AWS Bedrock", bedrockService.getName(),
                "getName() should return 'AWS Bedrock'");
    }

    // ==================== Model Management ====================

    @Test
    void testGetCurrentModel_returnsDefault() {
        assertEquals("anthropic.claude-3-5-sonnet-20241022-v2:0",
                bedrockService.getCurrentModel(),
                "Default model should match the constructor value");
    }

    @Test
    void testSetModel_updatesCurrentModel() {
        bedrockService.setModel("anthropic.claude-3-haiku-20240307-v1:0");
        assertEquals("anthropic.claude-3-haiku-20240307-v1:0",
                bedrockService.getCurrentModel(),
                "Model should be updated after setModel()");
    }

    // ==================== Response Generation (Null Client) ====================

    @Test
    void testGenerateResponse_withNullClient_returnsError() {
        List<String> conversation = List.of("Hello");
        String response = bedrockService.generateResponse(conversation);
        assertTrue(response.startsWith("Error:"),
                "Should return error when client is null");
    }

    @Test
    void testGenerateResponse_withModel_withNullClient_returnsError() {
        List<String> conversation = List.of("Hello");
        String response = bedrockService.generateResponse(conversation,
                "anthropic.claude-3-5-sonnet-20241022-v2:0");
        assertTrue(response.startsWith("Error:"),
                "Should return error when client is null");
    }

    @Test
    void testGenerateResponse_emptyConversation_withNullClient_returnsError() {
        String response = bedrockService.generateResponse(Collections.emptyList());
        assertTrue(response.startsWith("Error:"),
                "Should return error even with empty conversation when client is null");
    }

    @Test
    void testGenerateResponse_nullConversation_withNullClient() {
        String response = bedrockService.generateResponse((List<String>) null);
        assertTrue(response.startsWith("Error:"),
                "Should handle null conversation gracefully");
    }

    // ==================== Streaming (Null Client) ====================

    @Test
    void testGenerateStreamResponse_withNullClient_returnsNoOpRunnable() {
        Runnable cancelHandle = bedrockService.generateStreamResponse(
                List.of("Hello"), "anthropic.claude-3-5-sonnet-20241022-v2:0",
                token -> {}, () -> {}, error -> {});
        assertNotNull(cancelHandle, "Cancel handle should not be null");
        cancelHandle.run(); // Should not throw
    }

    @Test
    void testGenerateStreamResponse_emptyConversation_withNullClient() {
        Runnable cancelHandle = bedrockService.generateStreamResponse(
                Collections.emptyList(), "anthropic.claude-3-5-sonnet-20241022-v2:0",
                token -> {}, () -> {}, error -> {});
        assertNotNull(cancelHandle, "Cancel handle should not be null");
    }

    // ==================== Conversation History Management ====================

    @Test
    void testGenerateResponse_largeConversation_isLimited() {
        List<String> conversation = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            conversation.add("message " + i);
        }
        String response = bedrockService.generateResponse(conversation);
        assertTrue(response.startsWith("Error:"),
                "Should still return error (null client) but not throw on large history");
    }

    @Test
    void testGenerateResponse_conversationWithErrors_filtered() {
        List<String> conversation = new ArrayList<>();
        conversation.add("Hello");
        conversation.add("Error: something went wrong");
        conversation.add("Try again");
        String response = bedrockService.generateResponse(conversation);
        assertTrue(response.startsWith("Error:"),
                "Should still return error (null client)");
    }

    // ==================== Model Listing (Null Client) ====================

    @Test
    void testListModels_withNullClient_returnsDefaultModel() {
        List<String> models = bedrockService.listModels();
        assertNotNull(models, "Model list should not be null");
        assertEquals(1, models.size(),
                "Model list should contain the default model when client is null");
        assertEquals("anthropic.claude-3-5-sonnet-20241022-v2:0", models.get(0),
                "Default model should be returned when client is null");
    }

    @Test
    void testListModels_includesLegacyAnthropicModelForConfiguredProvider() {
        BedrockClient bedrockClient = mock(BedrockClient.class);
        FoundationModelSummary anthropicModel = FoundationModelSummary.builder()
                .modelId("anthropic.claude-3-haiku-20240307-v1:0")
                .providerName("Anthropic")
                .modelLifecycle(FoundationModelLifecycle.builder().status("LEGACY").build())
                .build();
        FoundationModelSummary mistralModel = FoundationModelSummary.builder()
                .modelId("mistral.mistral-7b-instruct-v0:2")
                .providerName("Mistral")
                .build();
        when(bedrockClient.listFoundationModels(
                (Consumer<ListFoundationModelsRequest.Builder>) any())).thenReturn(
                ListFoundationModelsResponse.builder()
                        .modelSummaries(anthropicModel, mistralModel)
                        .build());
        InferenceProfileSummary anthropicProfile = InferenceProfileSummary.builder()
                .inferenceProfileId("us.anthropic.claude-sonnet-4-6")
                .inferenceProfileName("US Anthropic Claude Sonnet 4.6")
                .status("ACTIVE")
                .models(InferenceProfileModel.builder()
                        .modelArn("arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-sonnet-4-6")
                        .build())
                .build();
        when(bedrockClient.listInferenceProfiles(
                (Consumer<ListInferenceProfilesRequest.Builder>) any())).thenReturn(
                ListInferenceProfilesResponse.builder()
                        .inferenceProfileSummaries(anthropicProfile)
                        .build());
        when(bedrockClient.getFoundationModelAvailability(
                (Consumer<GetFoundationModelAvailabilityRequest.Builder>) any())).thenReturn(
                GetFoundationModelAvailabilityResponse.builder()
                        .agreementAvailability(AgreementAvailability.builder().status("AVAILABLE").build())
                        .authorizationStatus("AUTHORIZED")
                        .entitlementAvailability("AVAILABLE")
                        .regionAvailability("AVAILABLE")
                        .build());

        BedrockAiService service = new BedrockAiService(
                null, null, bedrockClient, "meta.llama3-8b-instruct-v1:0",
                0.5f, 10, 4096L, "Test prompt", List.of("Anthropic", "Mistral"));

        assertEquals(List.of(anthropicModel.modelId(), mistralModel.modelId(),
                anthropicProfile.inferenceProfileId()), service.listModels());
    }

    // ==================== Cancel Handle ====================

    @Test
    void testCancelHandle_isIdempotent() {
        Runnable cancelHandle = bedrockService.generateStreamResponse(
                List.of("Hello"), "anthropic.claude-3-5-sonnet-20241022-v2:0",
                token -> {}, () -> {}, error -> {});
        cancelHandle.run();
        cancelHandle.run();
    }
}
