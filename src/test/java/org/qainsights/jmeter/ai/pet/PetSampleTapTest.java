package org.qainsights.jmeter.ai.pet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jmeter.reporters.AbstractListenerElement;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.Visualizer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PetSampleTap}.
 */
class PetSampleTapTest {

    /** Minimal concrete listener element - avoids ResultCollector's file plumbing. */
    static final class FakeListenerElement extends AbstractListenerElement {
    }

    static final class RecordingVisualizer implements Visualizer {
        final List<SampleResult> added = new ArrayList<>();
        final boolean stats;

        RecordingVisualizer(boolean stats) {
            this.stats = stats;
        }

        @Override
        public void add(SampleResult sample) {
            added.add(sample);
        }

        @Override
        public boolean isStats() {
            return stats;
        }
    }

    private static SampleResult result(boolean successful) {
        SampleResult result = new SampleResult();
        result.setSuccessful(successful);
        return result;
    }

    @Test
    void should_wrapVisualizer_when_installed() throws Exception {
        FakeListenerElement element = new FakeListenerElement();
        RecordingVisualizer original = new RecordingVisualizer(false);
        element.setListener(original);

        PetSampleTap tap = new PetSampleTap(() -> { });
        tap.install(Collections.singletonList(element));

        assertEquals(1, tap.tappedCount());
        assertTrue(PetSampleTap.readVisualizer(element) instanceof PetSampleTap.TapVisualizer);
    }

    @Test
    void should_notifyOnFailureAndDelegate_when_samplesArrive() throws Exception {
        FakeListenerElement element = new FakeListenerElement();
        RecordingVisualizer original = new RecordingVisualizer(false);
        element.setListener(original);

        AtomicInteger failures = new AtomicInteger();
        PetSampleTap tap = new PetSampleTap(failures::incrementAndGet);
        tap.install(Collections.singletonList(element));

        Visualizer wrapped = PetSampleTap.readVisualizer(element);
        wrapped.add(result(true));
        wrapped.add(result(false));
        wrapped.add(result(false));

        assertEquals(2, failures.get());
        assertEquals(3, original.added.size());
    }

    @Test
    void should_stillNotify_when_elementHasNoVisualizer() throws Exception {
        FakeListenerElement element = new FakeListenerElement();
        AtomicInteger failures = new AtomicInteger();
        PetSampleTap tap = new PetSampleTap(failures::incrementAndGet);
        tap.install(Collections.singletonList(element));

        Visualizer wrapped = PetSampleTap.readVisualizer(element);
        assertDoesNotThrow(() -> wrapped.add(result(false)));
        assertEquals(1, failures.get());
        assertFalse(wrapped.isStats());
    }

    @Test
    void should_delegateIsStats_when_originalPresent() throws Exception {
        FakeListenerElement element = new FakeListenerElement();
        element.setListener(new RecordingVisualizer(true));
        PetSampleTap tap = new PetSampleTap(() -> { });
        tap.install(Collections.singletonList(element));
        assertTrue(PetSampleTap.readVisualizer(element).isStats());
    }

    @Test
    void should_restoreOriginalVisualizer_when_uninstalled() throws Exception {
        FakeListenerElement element = new FakeListenerElement();
        RecordingVisualizer original = new RecordingVisualizer(false);
        element.setListener(original);

        PetSampleTap tap = new PetSampleTap(() -> { });
        tap.install(Collections.singletonList(element));
        tap.uninstall();

        assertEquals(0, tap.tappedCount());
        assertSame(original, PetSampleTap.readVisualizer(element));
    }

    @Test
    void should_notDoubleWrap_when_installedTwice() throws Exception {
        FakeListenerElement element = new FakeListenerElement();
        element.setListener(new RecordingVisualizer(false));

        PetSampleTap tap = new PetSampleTap(() -> { });
        tap.install(Collections.singletonList(element));
        tap.install(Collections.singletonList(element));
        assertEquals(1, tap.tappedCount());

        tap.uninstall();
        assertFalse(PetSampleTap.readVisualizer(element) instanceof PetSampleTap.TapVisualizer);
    }

    @Test
    void should_skipNullElements_when_installing() {
        PetSampleTap tap = new PetSampleTap(() -> { });
        tap.install(Arrays.asList(null, null));
        assertEquals(0, tap.tappedCount());
    }

    @Test
    void should_swallowCallbackErrors_when_reactionThrows() throws Exception {
        FakeListenerElement element = new FakeListenerElement();
        PetSampleTap tap = new PetSampleTap(() -> {
            throw new IllegalStateException("boom");
        });
        tap.install(Collections.singletonList(element));
        Visualizer wrapped = PetSampleTap.readVisualizer(element);
        assertDoesNotThrow(() -> wrapped.add(result(false)));
    }

    @Test
    void should_returnEmptyList_when_noGuiAvailable() {
        assertTrue(PetSampleTap.findListenersInGuiTree().isEmpty());
    }
}
