package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.attach.AttachmentRegistry;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link AttachmentBar}: attaching files, chip lifecycle,
 * marker consumption, limits, and error reporting.
 */
class AttachmentBarTest {

    @TempDir
    Path tempDir;

    private MockedStatic<AiConfig> aiConfigMockedStatic;
    private AttachmentRegistry registry;
    private List<String> systemMessages;
    private AttachmentBar bar;

    @BeforeEach
    void setUp() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        registry = new AttachmentRegistry();
        systemMessages = new ArrayList<>();
        bar = new AttachmentBar(registry, systemMessages::add);
    }

    @AfterEach
    void tearDown() {
        aiConfigMockedStatic.close();
    }

    private File writeFile(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        return path.toFile();
    }

    @Test
    void startsHiddenAndEmpty() {
        assertFalse(bar.isVisible());
        assertFalse(bar.hasAttachments());
        assertEquals(0, bar.getChipCount());
    }

    @Test
    void addFileCreatesChipAndRegisters() throws Exception {
        File file = writeFile("notes.txt", "hello");
        assertNotNull(bar.addFile(file));

        assertTrue(bar.isVisible());
        assertTrue(bar.hasAttachments());
        assertEquals(1, bar.getChipCount());
        assertEquals(1, registry.size());
        assertTrue(systemMessages.isEmpty());
    }

    @Test
    void missingFileReportsSystemMessage() {
        assertNull(bar.addFile(new File(tempDir.toFile(), "nope.txt")));
        assertEquals(1, systemMessages.size());
        assertFalse(bar.hasAttachments());
    }

    @Test
    void consumeMarkersYieldsMarkersAndClears() throws Exception {
        File a = writeFile("a.txt", "aaa");
        File b = writeFile("b.txt", "bbb");
        bar.addFile(a);
        bar.addFile(b);

        String markers = bar.consumeMarkers();

        assertEquals(" [file:f1] [file:f2]", markers);
        assertFalse(bar.hasAttachments());
        assertFalse(bar.isVisible());
        // the registry keeps the content for request-time resolution
        assertEquals(2, registry.size());
    }

    @Test
    void capProducesFriendlyMessage() throws Exception {
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(eq("jmeter.ai.file.max.count"), anyString()))
                .thenReturn("1");
        bar.addFile(writeFile("a.txt", "a"));
        assertNull(bar.addFile(writeFile("b.txt", "b")));
        assertEquals(1, systemMessages.size());
        assertTrue(systemMessages.get(0).contains("Attachment limit"));
    }

    @Test
    void removeChipViaBarClear() throws Exception {
        bar.addFile(writeFile("a.txt", "a"));
        bar.addFile(writeFile("b.txt", "b"));
        bar.clear();
        assertFalse(bar.hasAttachments());
        assertEquals(0, bar.getChipCount());
    }

    @Test
    void addFileAsyncRejectsSynchronously() {
        // cheap rejections must be reported immediately, not on a worker
        bar.addFileAsync(new File(tempDir.toFile(), "nope.txt"));
        assertEquals(1, systemMessages.size());
        assertFalse(bar.hasAttachments());
    }

    @Test
    void addFileAsyncAttachesOnWorkerThread() throws Exception {
        File file = writeFile("async.txt", "async content");
        bar.addFileAsync(file);

        // the read happens off-thread; the chip appears when the worker finishes
        long deadline = System.currentTimeMillis() + 5000;
        while (!bar.hasAttachments() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(bar.hasAttachments(), "async attach must complete");
        assertEquals(1, registry.size());
        assertTrue(systemMessages.isEmpty());
    }

    @Test
    void readCappedReadsNormalText() throws Exception {
        assertEquals("hello", AttachmentBar.readCapped(writeFile("n.txt", "hello")));
    }

    @Test
    void readCappedRejectsOversizeAtReadTime() throws Exception {
        // size re-verified on the bytes read, not just the pre-check
        File big = tempDir.resolve("big.txt").toFile();
        Files.write(big.toPath(), new byte[AttachmentBar.MAX_FILE_BYTES > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) AttachmentBar.MAX_FILE_BYTES + 1]);
        java.io.IOException e = assertThrows(java.io.IOException.class,
                () -> AttachmentBar.readCapped(big));
        assertTrue(e.getMessage().contains("exceeds 10 MB"));
    }

    @Test
    void readCappedRejectsBinaryAsNotUtf8() throws Exception {
        File binary = tempDir.resolve("img.png").toFile();
        Files.write(binary.toPath(), new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, (byte) 0xFF, (byte) 0xFE});
        java.io.IOException e = assertThrows(java.io.IOException.class,
                () -> AttachmentBar.readCapped(binary));
        assertTrue(e.getMessage().contains("not valid UTF-8"));
    }

    @Test
    void chipWithHtmlPrefixedNameRendersAsPlainText() {
        String payload = "<html><img src=https://tracker.example/x>";
        org.qainsights.jmeter.ai.service.attach.Attachment a =
                registry.register(payload, "x", null);
        bar.addChip(a);

        javax.swing.JLabel label = null;
        for (java.awt.Component chip : bar.getComponents()) {
            if (chip instanceof javax.swing.JPanel) {
                for (java.awt.Component inner : ((javax.swing.JPanel) chip).getComponents()) {
                    if (inner instanceof javax.swing.JLabel
                            && ((javax.swing.JLabel) inner).getIcon() != null) {
                        label = (javax.swing.JLabel) inner;
                    }
                }
            }
        }
        assertNotNull(label, "chip label must exist");
        assertTrue(label.getText().contains(payload), "the crafted name must appear literally");
        assertEquals(Boolean.TRUE, label.getClientProperty("html.disable"));
    }

    @Test
    void binaryFileGetsFriendlyMessageViaAddFile() throws Exception {
        File binary = tempDir.resolve("img.png").toFile();
        Files.write(binary.toPath(), new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, (byte) 0xFF});
        assertNull(bar.addFile(binary));
        assertEquals(1, systemMessages.size());
        assertTrue(systemMessages.get(0).contains("Couldn't read img.png as text"));
        assertFalse(bar.hasAttachments());
    }
}
