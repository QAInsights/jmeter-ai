package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GemmaTokenizerTest {

    // ids: <pad>0 <eos>1 <bos>2 <unk>3 a4 b5 c6 ▁7 ab8 abc9 ▁a10 aa11 <0xC3>12 <0xA9>13 <mask>14
    private static final String JSON = "{\"version\":\"1.0\",\"added_tokens\":["
            + "{\"id\":0,\"content\":\"<pad>\",\"special\":true},{\"id\":1,\"content\":\"<eos>\",\"special\":true},"
            + "{\"id\":2,\"content\":\"<bos>\",\"special\":true},{\"id\":3,\"content\":\"<unk>\",\"special\":true},"
            + "{\"id\":14,\"content\":\"<mask>\",\"special\":false}],"
            + "\"normalizer\":{\"type\":\"Replace\"},\"model\":{\"type\":\"BPE\",\"byte_fallback\":true,"
            + "\"vocab\":{\"<pad>\":0,\"<eos>\":1,\"<bos>\":2,\"<unk>\":3,\"a\":4,\"b\":5,\"c\":6,\"\u2581\":7,"
            + "\"ab\":8,\"abc\":9,\"\u2581a\":10,\"aa\":11,\"<0xC3>\":12,\"<0xA9>\":13,\"<mask>\":14},"
            + "\"merges\":[[\"a\",\"b\"],\"ab c\",[\"\u2581\",\"a\"],[\"a\",\"a\"]]}}";

    private static GemmaTokenizer tokenizer;

    @BeforeAll
    static void load() throws IOException {
        tokenizer = GemmaTokenizer.parse(new ByteArrayInputStream(JSON.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void appliesMergesInRankOrderAndWrapsInBosEos() {
        assertArrayEquals(new int[] {2, 9, 1}, tokenizer.encode("abc", 64));
        assertArrayEquals(new int[] {2, 9, 10, 1}, tokenizer.encode("abc a", 64));
    }

    @Test
    void lowerRankWinsOverLeftmostAndTiesGoLeft() {
        // "ab" (rank 0) beats "aa" (rank 3) in "aab"; in "aaa" the leftmost pair merges first.
        assertArrayEquals(new int[] {2, 4, 8, 1}, tokenizer.encode("aab", 64));
        assertArrayEquals(new int[] {2, 11, 4, 1}, tokenizer.encode("aaa", 64));
    }

    @Test
    void unknownCharactersUseByteFallbackOrOneUnk() {
        assertArrayEquals(new int[] {2, 4, 12, 13, 1}, tokenizer.encode("a\u00e9", 64));
        assertArrayEquals(new int[] {2, 3, 4, 1}, tokenizer.encode("xyz a".replace(" ", ""), 64));
    }

    @Test
    void addedTokensInTheTextKeepTheirIds() {
        assertArrayEquals(new int[] {2, 4, 14, 5, 2, 1}, tokenizer.encode("a<mask>b<bos>", 64));
    }

    @Test
    void truncatesButKeepsEos() {
        assertArrayEquals(new int[] {2, 9, 9, 1}, tokenizer.encode("abcabcabcabc", 4));
        assertArrayEquals(new int[] {2, 1}, tokenizer.encode("", 4));
        assertThrows(IllegalArgumentException.class, () -> tokenizer.encode("a", 1));
    }

    @Test
    void rejectsFilesWithoutBpeVocabulary() {
        assertThrows(IOException.class, () -> GemmaTokenizer.parse(new ByteArrayInputStream(
                "{\"model\":{\"type\":\"Unigram\"}}".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> GemmaTokenizer.parse(new ByteArrayInputStream(
                "[]".getBytes(StandardCharsets.UTF_8))));
    }
}
