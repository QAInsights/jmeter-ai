package org.qainsights.jmeter.ai.service.attach;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link AttachmentRegistry} and {@link Attachment}:
 * registration, caps, lookup/removal, chip labels, prepared content, and
 * inline marker resolution.
 */
class AttachmentRegistryTest {

    private MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeEach
    void setUp() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @AfterEach
    void tearDown() {
        aiConfigMockedStatic.close();
    }

    @Test
    void restoreKeepsIdIsNotPendingAndAdvancesCounter() {
        AttachmentRegistry registry = new AttachmentRegistry();
        Attachment restored = registry.restore("f7", "jmeter.log", "body",
                FileContentPreparer.Mode.SMART);

        assertEquals("f7", restored.getId());
        assertSame(restored, registry.find("f7"));
        assertEquals(0, registry.pendingCount());

        // new registrations must not collide with the restored id
        Attachment next = registry.register("new.txt", "x", FileContentPreparer.Mode.SMART);
        assertEquals("f8", next.getId());

        // and restored markers resolve again
        String resolved = registry.resolveInlineMarkers("check [file:f7]");
        assertTrue(resolved.contains("jmeter.log"));
        assertFalse(resolved.contains("[file:f7]"));
    }

    @Test
    void registerAssignsSequentialIds() {
        AttachmentRegistry registry = new AttachmentRegistry();
        Attachment first = registry.register("a.txt", "hello", FileContentPreparer.Mode.SMART);
        Attachment second = registry.register("b.txt", "world", FileContentPreparer.Mode.SMART);
        assertEquals("f1", first.getId());
        assertEquals("f2", second.getId());
        assertEquals("[file:f1]", first.marker());
        assertEquals(2, registry.size());
        assertSame(first, registry.find("f1"));
        assertNull(registry.find("f99"));
    }

    @Test
    void capIsEnforced() {
        AttachmentRegistry registry = new AttachmentRegistry();
        registry.register("1.txt", "x", FileContentPreparer.Mode.SMART);
        registry.register("2.txt", "x", FileContentPreparer.Mode.SMART);
        registry.register("3.txt", "x", FileContentPreparer.Mode.SMART);
        assertFalse(registry.canAddMore());
        assertThrows(IllegalStateException.class,
                () -> registry.register("4.txt", "x", FileContentPreparer.Mode.SMART));
    }

    @Test
    void maxCountFromProperty() {
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(eq("jmeter.ai.file.max.count"), anyString()))
                .thenReturn("1");
        AttachmentRegistry registry = new AttachmentRegistry();
        registry.register("1.txt", "x", FileContentPreparer.Mode.SMART);
        assertThrows(IllegalStateException.class,
                () -> registry.register("2.txt", "x", FileContentPreparer.Mode.SMART));
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(eq("jmeter.ai.file.max.count"), anyString()))
                .thenReturn("bogus");
        assertEquals(3, AttachmentRegistry.maxCount());
    }

    @Test
    void removeAndClear() {
        AttachmentRegistry registry = new AttachmentRegistry();
        Attachment a = registry.register("a.txt", "x", FileContentPreparer.Mode.SMART);
        registry.register("b.txt", "y", FileContentPreparer.Mode.SMART);
        assertSame(a, registry.remove(a.getId()));
        assertNull(registry.remove("f99"));
        assertEquals(1, registry.size());
        registry.clear();
        assertEquals(0, registry.size());
        assertTrue(registry.canAddMore());
    }

    @Test
    void preparedContentIsWrappedWithProvenance() {
        AttachmentRegistry registry = new AttachmentRegistry();
        Attachment a = registry.register("notes.txt", "raw text here", FileContentPreparer.Mode.SMART);
        assertTrue(a.getPreparedContent().startsWith("<attached file=\"notes.txt\" mode=\"smart\">"));
        assertTrue(a.getPreparedContent().contains("raw text here"));
    }

    @Test
    void chipLabelHasNameSizeAndMode() {
        Attachment a = new Attachment("f1", "results.jtl", "x".repeat(2048), FileContentPreparer.Mode.SMART);
        assertEquals("results.jtl · 2.0 KB · smart", a.chipLabel());
        a.setMode(FileContentPreparer.Mode.RAW);
        assertEquals("results.jtl · 2.0 KB · raw", a.chipLabel());
    }

    @Test
    void nullFileNameIsNormalized() {
        Attachment a = new Attachment("f1", null, "x", FileContentPreparer.Mode.SMART);
        assertEquals("file", a.getFileName());
        assertTrue(a.chipLabel().startsWith("file ·"), "chipLabel must not NPE on a null name");
    }

    @Test
    void formatSizeBoundaries() {
        assertEquals("512 B", Attachment.formatSize(512));
        assertEquals("1.5 KB", Attachment.formatSize(1536));
        assertEquals("1.0 MB", Attachment.formatSize(1024 * 1024));
    }

    @Test
    void consumePendingClearsPendingButKeepsResolution() {
        AttachmentRegistry registry = new AttachmentRegistry();
        Attachment a = registry.register("a.txt", "file body", FileContentPreparer.Mode.SMART);

        assertEquals(1, registry.pendingCount());
        java.util.List<Attachment> consumed = registry.consumePending();
        assertEquals(1, consumed.size());
        assertSame(a, consumed.get(0));

        // cap is per-message again after consume
        assertEquals(0, registry.pendingCount());
        assertTrue(registry.canAddMore());
        // ...but the marker in history still resolves
        String resolved = registry.resolveInlineMarkers("check " + a.marker());
        assertTrue(resolved.contains("file body"));
    }

    @Test
    void secondMessageEmitsOnlyItsOwnMarkers() {
        AttachmentRegistry registry = new AttachmentRegistry();
        Attachment first = registry.register("a.txt", "a", FileContentPreparer.Mode.SMART);
        registry.consumePending(); // message 1 sent
        Attachment second = registry.register("b.txt", "b", FileContentPreparer.Mode.SMART);

        java.util.List<Attachment> pending = registry.consumePending();

        assertEquals(1, pending.size());
        assertSame(second, pending.get(0));
        assertNotSame(first, pending.get(0));
    }

    @Test
    void concurrentRegisterAndResolveDoNotCorrupt() throws Exception {
        // The EDT (chip add/remove) and background request threads (marker
        // resolution) share one registry - interleaving must never corrupt
        // the map or throw.
        AttachmentRegistry registry = new AttachmentRegistry();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<?> writer = pool.submit(() -> {
            try {
                for (int i = 0; i < 50; i++) {
                    registry.register("file" + i + ".txt", "body " + i, FileContentPreparer.Mode.SMART);
                    registry.consumePending();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        java.util.concurrent.Future<?> reader = pool.submit(() -> {
            try {
                for (int i = 0; i < 50; i++) {
                    registry.resolveInlineMarkers("check [file:f1] and [file:f50]");
                    registry.all();
                    registry.size();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        writer.get(30, java.util.concurrent.TimeUnit.SECONDS);
        reader.get(30, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        assertNull(failure.get(), "concurrent access must not throw");
        assertEquals(50, registry.size());
    }

    @Test
    void resolveInlineMarkersSubstitutesPreparedContent() {
        AttachmentRegistry registry = new AttachmentRegistry();
        Attachment a = registry.register("a.txt", "file body", FileContentPreparer.Mode.SMART);
        String resolved = registry.resolveInlineMarkers("check this " + a.marker() + " please");
        assertTrue(resolved.startsWith("check this \n<attached file=\"a.txt\" mode=\"smart\">\nfile body\n</attached>\n please"));
        assertFalse(resolved.contains("[file:"));
    }

    @Test
    void resolveInlineMarkersStripsUnknownIds() {
        AttachmentRegistry registry = new AttachmentRegistry();
        String resolved = registry.resolveInlineMarkers("look [file:f42] here");
        assertFalse(resolved.contains("[file:"));
        assertTrue(resolved.contains("[attachment no longer available]"));
    }

    @Test
    void resolveInlineMarkersNullSafe() {
        AttachmentRegistry registry = new AttachmentRegistry();
        assertNull(registry.resolveInlineMarkers(null));
        assertEquals("", registry.resolveInlineMarkers(""));
        assertEquals("no markers", registry.resolveInlineMarkers("no markers"));
    }
}
