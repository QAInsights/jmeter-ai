package org.qainsights.jmeter.ai.pet;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.reporters.AbstractListenerElement;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.Visualizer;
import org.apache.jorphan.collections.HashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Taps per-sample results out of the listener elements (View Results Tree, Summary
 * Report, ...) already present in the test plan. Listener elements are
 * {@code NoThreadClone}, so the exact instances in the GUI tree receive
 * {@code sampleOccurred} during GUI-initiated runs; wrapping their {@link Visualizer}
 * lets the pet see failures without adding anything to the plan. Original visualizers
 * are always restored on {@link #uninstall()}. Plans with no listener element simply
 * produce no per-sample reactions.
 */
public final class PetSampleTap {
    private static final Logger log = LoggerFactory.getLogger(PetSampleTap.class);

    private final Runnable failureCallback;
    private final Map<AbstractListenerElement, TapVisualizer> taps = new IdentityHashMap<>();

    public PetSampleTap(Runnable failureCallback) {
        this.failureCallback = failureCallback;
    }

    /** Wraps the visualizer of every given listener element. Idempotent per element. */
    public synchronized void install(Collection<? extends AbstractListenerElement> listeners) {
        for (AbstractListenerElement element : listeners) {
            if (element == null || taps.containsKey(element)) {
                continue;
            }
            try {
                Visualizer original = readVisualizer(element);
                if (original instanceof TapVisualizer) {
                    continue;
                }
                TapVisualizer tap = new TapVisualizer(original, failureCallback);
                element.setListener(tap);
                taps.put(element, tap);
            } catch (Exception e) {
                log.warn("Pet could not tap listener element '{}': {}", element.getName(), e.toString());
            }
        }
    }

    /** Restores every wrapped visualizer to its original value. */
    public synchronized void uninstall() {
        for (Map.Entry<AbstractListenerElement, TapVisualizer> entry : taps.entrySet()) {
            try {
                entry.getKey().setListener(entry.getValue().delegate);
            } catch (Exception e) {
                log.warn("Pet could not restore listener element '{}': {}",
                        entry.getKey().getName(), e.toString());
            }
        }
        taps.clear();
    }

    /** Number of currently tapped listener elements. */
    synchronized int tappedCount() {
        return taps.size();
    }

    /** Collects every enabled listener element from the live GUI test plan tree. */
    public static List<AbstractListenerElement> findListenersInGuiTree() {
        List<AbstractListenerElement> found = new ArrayList<>();
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null && gui.getTreeModel() != null) {
            collect(gui.getTreeModel().getTestPlan(), found);
        }
        return found;
    }

    private static void collect(HashTree tree, List<AbstractListenerElement> found) {
        if (tree == null) {
            return;
        }
        for (Object key : tree.list()) {
            Object element = key;
            boolean enabled = true;
            if (key instanceof JMeterTreeNode) {
                JMeterTreeNode node = (JMeterTreeNode) key;
                element = node.getTestElement();
                enabled = node.isEnabled();
            } else if (key instanceof TestElement) {
                enabled = ((TestElement) key).isEnabled();
            }
            if (enabled && element instanceof AbstractListenerElement) {
                found.add((AbstractListenerElement) element);
            }
            if (enabled) {
                collect(tree.getTree(key), found);
            }
        }
    }

    /**
     * Reads the element's current visualizer. {@code getVisualizer()} is protected, so
     * this goes through the {@code listener} {@link WeakReference} field reflectively.
     */
    @SuppressWarnings("unchecked")
    static Visualizer readVisualizer(AbstractListenerElement element) throws ReflectiveOperationException {
        Field field = AbstractListenerElement.class.getDeclaredField("listener");
        field.setAccessible(true);
        WeakReference<Visualizer> reference = (WeakReference<Visualizer>) field.get(element);
        return reference == null ? null : reference.get();
    }

    /**
     * Delegating visualizer that notifies the pet about failed samples. Held strongly
     * by the tap map because {@code setListener} only keeps a {@link WeakReference}.
     */
    static final class TapVisualizer implements Visualizer {
        final Visualizer delegate;
        private final Runnable failureCallback;

        TapVisualizer(Visualizer delegate, Runnable failureCallback) {
            this.delegate = delegate;
            this.failureCallback = failureCallback;
        }

        @Override
        public void add(SampleResult sample) {
            if (sample != null && !sample.isSuccessful()) {
                try {
                    failureCallback.run();
                } catch (RuntimeException e) {
                    log.warn("Pet failure reaction threw: {}", e.toString());
                }
            }
            if (delegate != null) {
                delegate.add(sample);
            }
        }

        @Override
        public boolean isStats() {
            return delegate != null && delegate.isStats();
        }
    }
}
