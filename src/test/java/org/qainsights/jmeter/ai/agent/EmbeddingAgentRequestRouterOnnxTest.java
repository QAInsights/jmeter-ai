package org.qainsights.jmeter.ai.agent;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.qainsights.jmeter.ai.service.LocalEmbeddingModel;
import org.qainsights.jmeter.ai.service.OnnxEmbeddingClient;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The same accuracy check as {@link EmbeddingAgentRequestRouterOllamaTest}, with the model running inside
 * the plugin on ONNX Runtime. Opt-in, because the first run downloads about 200 MB into
 * {@code ~/.jmeter-ai/models}: set {@code FEATHER_WAND_ONNX_EMBED_MODEL} to a Hugging Face repo id such as
 * {@code onnx-community/embeddinggemma-300m-ONNX}.
 */
@EnabledIfEnvironmentVariable(named = "FEATHER_WAND_ONNX_EMBED_MODEL", matches = ".+")
class EmbeddingAgentRequestRouterOnnxTest {

    private static OnnxEmbeddingClient client;

    @BeforeAll
    static void load() throws Exception {
        String repo = System.getenv("FEATHER_WAND_ONNX_EMBED_MODEL");
        LocalEmbeddingModel model;
        try (MockedStatic<AiConfig> config = Mockito.mockStatic(AiConfig.class)) {
            config.when(() -> AiConfig.getProperty(anyString(), anyString()))
                    .thenAnswer(inv -> LocalEmbeddingModel.MODEL_KEY.equals(inv.getArgument(0)) ? repo : inv.getArgument(1));
            client = OnnxEmbeddingClient.fromConfig();
            model = client.model();
        }
        try {
            model.requireReady();
        } catch (RuntimeException downloading) {
            model.awaitDownloadForTests(Duration.ofMinutes(20));
        }
        long start = System.nanoTime();
        assertEquals(1, client.embed(List.of("warm up")).size());
        System.out.printf("%s: loaded and embedded one input in %d ms%n", repo, (System.nanoTime() - start) / 1_000_000);
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void compoundRequestsAcrossPacksAreNeverFocused() {
        EmbeddingAgentRequestRouterOllamaTest.assertCompoundRequestsNeverFocused(client::embed);
    }

    @Test
    void confidentPicksAreCorrectAndMostRequestsRouteRight() {
        long start = System.nanoTime();
        EmbeddingAgentRequestRouterOllamaTest.assertAccuracy(System.getenv("FEATHER_WAND_ONNX_EMBED_MODEL"), client::embed);
        System.out.printf("30 requests in %d ms%n", (System.nanoTime() - start) / 1_000_000);
    }
}
