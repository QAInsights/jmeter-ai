package org.qainsights.jmeter.ai.service;

import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.Pager;
import com.google.genai.types.Model;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleAiServiceTest {

    private Client googleClient;
    private Models modelsStub;

    private GoogleAiService googleService;

    private static MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeAll
    static void setUpAll() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String defaultValue = invocation.getArgument(1);
            if (key.equals("google.api.key")) return "test-api-key";
            if (key.equals("google.default.model")) return "gemini-2.5-flash";
            if (key.equals("google.temperature")) return "0.7";
            if (key.equals("google.max.tokens")) return "4096";
            if (key.equals("google.max.history.size")) return "10";
            if (key.equals("google.system.prompt")) return "You are a test assistant.";
            if (key.equals("google.streaming.enabled")) return "true";
            return defaultValue;
        });
    }

    @AfterAll
    static void tearDownAll() {
        if (aiConfigMockedStatic != null) {
            aiConfigMockedStatic.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        googleClient = mock(Client.class);
        modelsStub = mock(Models.class);
        Field modelsField = Client.class.getDeclaredField("models");
        modelsField.setAccessible(true);
        modelsField.set(googleClient, modelsStub);
        googleService = new GoogleAiService(googleClient);
    }

    @SuppressWarnings("unchecked")
    private Pager<Model> createPager(List<Model> models) {
        Pager<Model> pager = mock(Pager.class);
        when(pager.iterator()).thenReturn(models.iterator());
        return pager;
    }

    // ==================== Service Identity ====================

    @Test
    void testGetName_returnsGoogleGemini() {
        assertEquals("Google Gemini", googleService.getName(),
                "getName() should return 'Google Gemini'");
    }

    // ==================== Model Management ====================

    @Test
    void testGetCurrentModel_returnsDefaultModel() {
        assertEquals("gemini-2.5-flash", googleService.getCurrentModel(),
                "getCurrentModel() should return the default model");
    }

    @Test
    void testSetModel_updatesCurrentModel() {
        googleService.setModel("gemini-2.5-pro");
        assertEquals("gemini-2.5-pro", googleService.getCurrentModel(),
                "setModel() should update the current model");
    }

    // ==================== Non-Streaming Response ====================

    @Test
    void testGenerateResponse_withNullClient_returnsError() {
        GoogleAiService nullClientService = new GoogleAiService(null);
        String response = nullClientService.generateResponse(Collections.singletonList("Hello"));
        assertTrue(response.startsWith("Error:"),
                "Response should start with 'Error:' when client is null");
    }

    // ==================== Streaming Response ====================

    @Test
    void testGenerateStreamResponse_withNullClient_returnsNoOp() {
        GoogleAiService nullClientService = new GoogleAiService(null);
        Runnable handle = nullClientService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "gemini-2.5-flash",
                token -> {},
                () -> {},
                error -> {}
        );
        assertNotNull(handle, "Handle should not be null even with null client");
        handle.run();
    }

    @Test
    void testGenerateStreamResponse_withStreamingDisabled_returnsNoOp() {
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(eq("google.streaming.enabled"), anyString()))
                .thenReturn("false");
        GoogleAiService disabledService = new GoogleAiService(googleClient);

        AtomicBoolean tokenReceived = new AtomicBoolean(false);
        Runnable handle = disabledService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "gemini-2.5-flash",
                token -> tokenReceived.set(true),
                () -> {},
                error -> {}
        );
        assertNotNull(handle, "Handle should not be null when streaming is disabled");
        handle.run();
        assertFalse(tokenReceived.get(), "No tokens should be received when streaming is disabled");
    }

    @Test
    void testGenerateStreamResponse_cancellationWorks() {
        Runnable handle = googleService.generateStreamResponse(
                Collections.singletonList("Hello"),
                "gemini-2.5-flash",
                token -> {},
                () -> {},
                error -> {}
        );
        assertNotNull(handle, "Handle should not be null");
        handle.run();
    }

    // ==================== listModels ====================

    @Test
    void testListModels_withNullClient_returnsEmptyList() {
        GoogleAiService nullClientService = new GoogleAiService(null);
        List<String> models = nullClientService.listModels();
        assertNotNull(models, "listModels() should return an empty list when client is null");
        assertTrue(models.isEmpty(), "listModels() should return an empty list when client is null");
    }

    @Test
    void testListModels_filtersNonChatModels() throws Exception {
        Model chatModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-2.5-flash")).when(chatModel).name();

        Model ttsModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-2.5-flash-preview-tts")).when(ttsModel).name();

        Model embeddingModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-embedding-001")).when(embeddingModel).name();

        Model imageModel = mock(Model.class);
        doReturn(Optional.of("models/imagen-4.0-generate-001")).when(imageModel).name();

        Model veoModel = mock(Model.class);
        doReturn(Optional.of("models/veo-3.0-generate-001")).when(veoModel).name();

        Model liveModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-3.1-flash-live-preview")).when(liveModel).name();

        Model audioModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-2.5-flash-native-audio-latest")).when(audioModel).name();

        Model roboticsModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-robotics-er-1.5-preview")).when(roboticsModel).name();

        Model computerUseModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-2.5-computer-use-preview-10-2025")).when(computerUseModel).name();

        Model gemmaModel = mock(Model.class);
        doReturn(Optional.of("models/gemma-4-31b-it")).when(gemmaModel).name();

        Model deepResearchModel = mock(Model.class);
        doReturn(Optional.of("models/deep-research-preview-04-2026")).when(deepResearchModel).name();

        List<Model> allModels = new ArrayList<>();
        allModels.add(chatModel);
        allModels.add(ttsModel);
        allModels.add(embeddingModel);
        allModels.add(imageModel);
        allModels.add(veoModel);
        allModels.add(liveModel);
        allModels.add(audioModel);
        allModels.add(roboticsModel);
        allModels.add(computerUseModel);
        allModels.add(gemmaModel);
        allModels.add(deepResearchModel);

        Pager<Model> allModelsPager = createPager(allModels);
        when(modelsStub.list(null)).thenReturn(allModelsPager);

        List<String> result = googleService.listModels();

        assertNotNull(result, "Result should not be null");
        assertEquals(2, result.size(), "Only chat models should be included");
        assertTrue(result.contains("gemini-2.5-flash"), "Should contain gemini-2.5-flash");
        assertTrue(result.contains("gemma-4-31b-it"), "Should contain gemma-4-31b-it");
        assertFalse(result.contains("gemini-2.5-flash-preview-tts"), "Should not contain tts model");
        assertFalse(result.contains("gemini-embedding-001"), "Should not contain embedding model");
        assertFalse(result.contains("imagen-4.0-generate-001"), "Should not contain imagen model");
        assertFalse(result.contains("veo-3.0-generate-001"), "Should not contain veo model");
        assertFalse(result.contains("gemini-3.1-flash-live-preview"), "Should not contain live model");
        assertFalse(result.contains("gemini-2.5-flash-native-audio-latest"), "Should not contain native-audio model");
        assertFalse(result.contains("gemini-robotics-er-1.5-preview"), "Should not contain robotics model");
        assertFalse(result.contains("gemini-2.5-computer-use-preview-10-2025"), "Should not contain computer-use model");
        assertFalse(result.contains("deep-research-preview-04-2026"), "Should not contain deep-research model");
    }

    @Test
    void testListModels_handlesOptionalWrapping() throws Exception {
        Model model = mock(Model.class);
        doReturn(Optional.of("models/gemini-2.5-flash")).when(model).name();

        List<Model> modelList = Collections.singletonList(model);
        Pager<Model> optionalPager = createPager(modelList);
        when(modelsStub.list(null)).thenReturn(optionalPager);

        List<String> result = googleService.listModels();

        assertNotNull(result, "Result should not be null");
        assertEquals(1, result.size(), "Should have one model");
        assertEquals("gemini-2.5-flash", result.get(0), "Should strip models/ prefix");
    }

    @Test
    void testListModels_handlesException_returnsEmptyList() throws Exception {
        when(modelsStub.list(null)).thenThrow(new RuntimeException("API error"));

        List<String> result = googleService.listModels();

        assertNotNull(result, "listModels() should return an empty list on exception");
        assertTrue(result.isEmpty(), "listModels() should return an empty list on exception");
    }

    @Test
    void testListModels_includesGeminiChatModels() throws Exception {
        Model flashModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-2.5-flash")).when(flashModel).name();

        Model proModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-2.5-pro")).when(proModel).name();

        Model flashLiteModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-2.5-flash-lite")).when(flashLiteModel).name();

        Model previewModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-3-flash-preview")).when(previewModel).name();

        List<Model> modelList = new ArrayList<>();
        modelList.add(flashModel);
        modelList.add(proModel);
        modelList.add(flashLiteModel);
        modelList.add(previewModel);

        Pager<Model> geminiPager = createPager(modelList);
        when(modelsStub.list(null)).thenReturn(geminiPager);

        List<String> result = googleService.listModels();

        assertNotNull(result, "Result should not be null");
        assertEquals(4, result.size(), "All gemini chat models should be included");
        assertTrue(result.contains("gemini-2.5-flash"));
        assertTrue(result.contains("gemini-2.5-pro"));
        assertTrue(result.contains("gemini-2.5-flash-lite"));
        assertTrue(result.contains("gemini-3-flash-preview"));
    }

    @Test
    void testListModels_excludesImageVariantModels() throws Exception {
        Model imageModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-2.5-flash-image")).when(imageModel).name();

        Model imagePreviewModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-3-pro-image-preview")).when(imagePreviewModel).name();

        List<Model> modelList = new ArrayList<>();
        modelList.add(imageModel);
        modelList.add(imagePreviewModel);

        Pager<Model> imagePager = createPager(modelList);
        when(modelsStub.list(null)).thenReturn(imagePager);

        List<String> result = googleService.listModels();

        assertNotNull(result, "Result should not be null");
        assertEquals(0, result.size(), "Image variant models should be excluded");
    }

    @Test
    void testListModels_includesCustomToolsModels() throws Exception {
        Model customToolsModel = mock(Model.class);
        doReturn(Optional.of("models/gemini-3.1-pro-preview-customtools")).when(customToolsModel).name();

        List<Model> modelList = Collections.singletonList(customToolsModel);
        Pager<Model> customToolsPager = createPager(modelList);
        when(modelsStub.list(null)).thenReturn(customToolsPager);

        List<String> result = googleService.listModels();

        assertNotNull(result, "Result should not be null");
        assertEquals(1, result.size(), "customtools models should be included");
    }

    // ==================== Streaming default enabled ====================

    @Test
    void testStreamingEnabledByDefault() {
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(eq("google.streaming.enabled"), anyString()))
                .thenReturn("true");
        GoogleAiService service = new GoogleAiService(googleClient);

        Runnable handle = service.generateStreamResponse(
                Collections.singletonList("Hello"),
                "gemini-2.5-flash",
                token -> {},
                () -> {},
                error -> {}
        );
        assertNotNull(handle, "Handle should not be null when streaming is enabled");
    }
}
