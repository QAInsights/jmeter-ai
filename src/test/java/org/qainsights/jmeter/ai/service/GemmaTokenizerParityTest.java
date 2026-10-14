package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks the Java tokenizer against ids from Hugging Face tokenizers for a real EmbeddingGemma
 * {@code tokenizer.json} (v1 and v2 give the same ids for these inputs). Opt-in, because the file is 20 to 32 MB:
 * {@code FEATHER_WAND_GEMMA_TOKENIZER=/path/to/tokenizer.json mvn test -Dtest=GemmaTokenizerParityTest}.
 */
@EnabledIfEnvironmentVariable(named = "FEATHER_WAND_GEMMA_TOKENIZER", matches = ".+")
class GemmaTokenizerParityTest {

    private static GemmaTokenizer tokenizer;

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("task: classification | query: save the plan", new int[] {2, 8071, 236787, 15241, 1109, 7609, 236787, 5383, 506, 2731, 1}),
                Arguments.of("add a JSON extractor for ${token} \u2192 r\u00e9sum\u00e9 \ud83d\ude80", new int[] {2, 1282, 496, 10434, 138382, 573, 3356, 1114, 236783, 16130, 118515, 236743, 242015, 1}),
                Arguments.of("  leading  spaces\tand\nnewlines", new int[] {2, 138, 26016, 138, 35220, 255968, 624, 107, 208697, 1}),
                Arguments.of("\u00dcn\u00efc\u00f6d\u00e9 \u65e5\u672c\u8a9e \u30c6\u30b9\u30c8", new int[] {2, 238194, 236749, 238527, 236755, 55515, 236859, 33375, 238582, 71919, 9045, 1}),
                Arguments.of("GET /api/v2/orders?id=42&x=y", new int[] {2, 9850, 965, 3677, 236786, 236766, 236778, 236786, 11339, 236881, 547, 236784, 236812, 236778, 236880, 236781, 236784, 236762, 1}),
                Arguments.of("<bos> literal and \u2581 marker", new int[] {2, 2, 37087, 532, 139, 26488, 1}),
                Arguments.of("aaaaa\0\1", new int[] {2, 50354, 236746, 238, 249732, 1}),
                Arguments.of("", new int[] {2, 1}));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void matchesHuggingFaceTokenizers(String text, int[] expected) throws Exception {
        if (tokenizer == null) {
            tokenizer = GemmaTokenizer.load(Path.of(System.getenv("FEATHER_WAND_GEMMA_TOKENIZER")));
        }
        assertArrayEquals(expected, tokenizer.encode(text, 2048), text);
    }
}
