package org.qainsights.jmeter.ai.service.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConversationExporter}: Markdown structure, HTML
 * escaping, attachment-marker substitution, and file writing.
 */
class ConversationExporterTest {

    @TempDir
    Path tempDir;

    private static ConversationSession session() {
        return new ConversationSession("s1", 1700000000000L, "openai:gpt-5.1",
                List.of(new ConversationSession.Turn("user", "check this [file:f1]", 1700000001000L),
                        new ConversationSession.Turn("assistant", "here you go", 1700000002000L)),
                List.of(new ConversationSession.AttachmentSnapshot("f1", "jmeter.log", "smart", "body")));
    }

    @Test
    void markdownHasHeaderAndTurns() {
        String md = ConversationExporter.toMarkdown(session());
        assertTrue(md.startsWith("# Feather Wand conversation"));
        assertTrue(md.contains("- Model: `openai:gpt-5.1`"));
        assertTrue(md.contains("## You — "));
        assertTrue(md.contains("## Feather Wand — "));
        assertTrue(md.contains("here you go"));
    }

    @Test
    void markdownSubstitutesAttachmentMarkers() {
        String md = ConversationExporter.toMarkdown(session());
        assertTrue(md.contains("check this [jmeter.log]"));
        assertFalse(md.contains("[file:"));
    }

    @Test
    void unknownMarkerBecomesGenericNote() {
        ConversationSession noAttachments = new ConversationSession("s2", 0L, "",
                List.of(new ConversationSession.Turn("user", "see [file:f9]", 0L)), List.of());
        String md = ConversationExporter.toMarkdown(noAttachments);
        assertTrue(md.contains("see [attachment]"));
    }

    @Test
    void htmlEscapesUserContent() {
        ConversationSession evil = new ConversationSession("s3", 0L, "",
                List.of(new ConversationSession.Turn("user", "<script>alert(1)</script>", 0L)),
                List.of());
        String html = ConversationExporter.toHtml(evil);
        assertFalse(html.contains("<script>alert"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void htmlHasTurnStructure() {
        String html = ConversationExporter.toHtml(session());
        assertTrue(html.contains("<div class=\"turn user\">"));
        assertTrue(html.contains("<div class=\"turn assistant\">"));
        assertTrue(html.contains("check this [jmeter.log]"));
    }

    @Test
    void writeProducesFile() throws Exception {
        Path target = tempDir.resolve("chat.md");
        ConversationExporter.write(session(), target, ConversationExporter.Format.MARKDOWN);
        assertTrue(Files.exists(target));
        assertTrue(Files.readString(target).startsWith("# Feather Wand conversation"));

        Path htmlTarget = tempDir.resolve("chat.html");
        ConversationExporter.write(session(), htmlTarget, ConversationExporter.Format.HTML);
        assertTrue(Files.readString(htmlTarget).startsWith("<!DOCTYPE html>"));
    }

    @Test
    void emptyTurnsStillRender() {
        ConversationSession empty = new ConversationSession("s4", 0L, "", List.of(), List.of());
        assertDoesNotThrow(() -> ConversationExporter.toMarkdown(empty));
        assertDoesNotThrow(() -> ConversationExporter.toHtml(empty));
    }
}
