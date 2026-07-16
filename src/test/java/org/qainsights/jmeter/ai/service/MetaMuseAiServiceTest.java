package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link MetaMuseAiService}.
 */
@ExtendWith(MockitoExtension.class)
class MetaMuseAiServiceTest {

    private MetaMuseAiService metaService;
    private static MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeAll
    static void setUpAll() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    String defaultValue = invocation.getArgument(1);
                    if (key.equals("meta.api.key")) return "test-api-key";
                    if (key.equals("meta.base.url")) return "http://127.0.0.1:1";
                    if (key.equals("meta.default.model")) return "muse-spark-1.1";
                    if (key.equals("meta.temperature")) return "0.7";
                    if (key.equals("meta.max.tokens")) return "4096";
                    if (key.equals("meta.max.history.size")) return "10";
                    if (key.equals("meta.system.prompt")) return "You are a test assistant.";
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
    void setUp() {
        metaService = new MetaMuseAiService(
                null, "http://127.0.0.1:1", "muse-spark-1.1",
                0.7f, 10, 4096L, "You are a test assistant.");
    }

    @Test
    void testGetName() {
        assertEquals("Meta Muse", metaService.getName());
    }

    @Test
    void testGetCurrentModel() {
        assertEquals("muse-spark-1.1", metaService.getCurrentModel());
    }

    @Test
    void testSetModel() {
        metaService.setModel("muse-spark-2");
        assertEquals("muse-spark-2", metaService.getCurrentModel());
    }

    @Test
    void testGetClient() {
        assertNull(metaService.getClient());
    }

    @Test
    void testGenerateResponseNullClient() {
        List<String> conversation = List.of("Hello");
        String response = metaService.generateResponse(conversation);
        assertTrue(response.startsWith("Error:"));
    }

    @Test
    void testGenerateResponseWithModelNullClient() {
        List<String> conversation = List.of("Hello");
        String response = metaService.generateResponse(conversation, "muse-spark-1.1");
        assertTrue(response.startsWith("Error:"));
    }

    @Test
    void testGenerateStreamResponseNullClient() {
        AtomicBoolean completeCalled = new AtomicBoolean(false);
        Runnable cancelHandle = metaService.generateStreamResponse(
                List.of("Hello"), "muse-spark-1.1",
                token -> {},
                () -> completeCalled.set(true),
                error -> {});
        assertNotNull(cancelHandle);
        cancelHandle.run();
        assertFalse(completeCalled.get());
    }

    @Test
    void testGenerateResponseLargeHistory() {
        List<String> conversation = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            conversation.add("msg " + i);
        }
        String response = metaService.generateResponse(conversation);
        assertTrue(response.startsWith("Error:"));
    }

    @Test
    void testGenerateResponseErrorFiltering() {
        List<String> conversation = new ArrayList<>();
        conversation.add("Hello");
        conversation.add("Error: failed");
        conversation.add("Try again");
        String response = metaService.generateResponse(conversation);
        assertTrue(response.startsWith("Error:"));
    }

    @Test
    void testListModelsNullClient() {
        List<String> models = metaService.listModels();
        assertNotNull(models);
        assertEquals(1, models.size());
        assertEquals("muse-spark-1.1", models.get(0));
    }

    @Test
    void testCancelHandleIdempotent() {
        Runnable cancelHandle = metaService.generateStreamResponse(
                List.of("Hello"), "muse-spark-1.1",
                token -> {}, () -> {}, error -> {});
        cancelHandle.run();
        cancelHandle.run();
    }
}
