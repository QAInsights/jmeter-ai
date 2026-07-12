package org.qainsights.jmeter.ai.gui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link TextChunker}. */
class TextChunkerTest {

    @Test
    void chunk_null_returnsEmptyList() {
        assertTrue(TextChunker.chunk(null).isEmpty());
    }

    @Test
    void chunk_empty_returnsEmptyList() {
        assertTrue(TextChunker.chunk("").isEmpty());
    }

    @Test
    void chunk_singleWord_returnsOneChunk() {
        List<String> chunks = TextChunker.chunk("Done.");
        assertEquals(1, chunks.size());
        assertEquals("Done.", chunks.get(0));
    }

    @Test
    void chunk_multipleWords_reproducesOriginalTextWhenJoined() {
        String text = "I added a Thread Group under the Test Plan.";
        List<String> chunks = TextChunker.chunk(text);

        assertTrue(chunks.size() > 1);
        assertEquals(text, String.join("", chunks));
    }

    @Test
    void chunk_preservesNewlinesAndMultipleSpaces() {
        String text = "Line one.\n\nLine two  with  extra spaces.";
        List<String> chunks = TextChunker.chunk(text);

        assertEquals(text, String.join("", chunks));
    }

    @Test
    void chunk_leadingWhitespace_isDropped() {
        List<String> chunks = TextChunker.chunk("  hello world");
        assertEquals("hello world", String.join("", chunks));
    }

    @Test
    void chunk_eachChunkIsNonEmpty() {
        String text = "a b c d e";
        for (String c : TextChunker.chunk(text)) {
            assertFalse(c.isEmpty());
        }
    }
}
