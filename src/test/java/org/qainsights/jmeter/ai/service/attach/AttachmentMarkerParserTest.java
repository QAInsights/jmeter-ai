package org.qainsights.jmeter.ai.service.attach;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AttachmentMarkerParser}: marker discovery and
 * display-safe stripping.
 */
class AttachmentMarkerParserTest {

    @Test
    void findMarkerIdsInOrder() {
        assertEquals(List.of("f1", "f2", "f1"),
                AttachmentMarkerParser.findMarkerIds("a [file:f1] b [file:f2] c [file:f1]"));
        assertTrue(AttachmentMarkerParser.findMarkerIds("plain text").isEmpty());
        assertTrue(AttachmentMarkerParser.findMarkerIds(null).isEmpty());
    }

    @Test
    void hasMarkers() {
        assertTrue(AttachmentMarkerParser.hasMarkers("x [file:f9]"));
        assertFalse(AttachmentMarkerParser.hasMarkers("x [image:f9]"));
        assertFalse(AttachmentMarkerParser.hasMarkers(null));
    }

    @Test
    void stripMarkersForDisplay() {
        assertEquals("check this please",
                AttachmentMarkerParser.stripMarkers("check this [file:f1] please"));
        assertEquals("multi line\ntext",
                AttachmentMarkerParser.stripMarkers("multi [file:f2] line\ntext [file:f3]"));
        assertEquals("", AttachmentMarkerParser.stripMarkers("[file:f1]"));
        assertEquals("", AttachmentMarkerParser.stripMarkers(null));
    }

    @Test
    void pathLikeAndSpecialIdsAreRejected() {
        // the id grammar ([A-Za-z0-9]+) is the security boundary: nothing that
        // looks like a path or payload can ever reach the registry lookup
        String[] malicious = {
                "x [file:../etc/passwd]",
                "x [file:..]",
                "x [file:a/b]",
                "x [file:a.b]",
                "x [file:a b]",
                "x [file:$(rm -rf)]",
                "x [file:<script>]",
                "x [file:]",
        };
        for (String text : malicious) {
            assertFalse(AttachmentMarkerParser.hasMarkers(text), "must not parse: " + text);
            assertTrue(AttachmentMarkerParser.findMarkerIds(text).isEmpty(), "must not parse: " + text);
            assertEquals(text, AttachmentMarkerParser.stripMarkers(text),
                    "invalid markers pass through as literal text: " + text);
        }
    }

    @Test
    void doesNotTouchOtherBracketSyntax() {
        assertEquals("[image:f1] stays", AttachmentMarkerParser.stripMarkers("[image:f1] stays"));
    }
}
