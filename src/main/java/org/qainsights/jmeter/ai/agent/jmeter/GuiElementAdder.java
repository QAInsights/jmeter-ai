package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.concurrent.atomic.AtomicReference;

import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link ElementAdder}. {@code JMeterElementManager.addElement} adds
 * to whichever node is currently selected in the tree, so this adapter first
 * <em>selects</em> the requested parent (the selection bridge), then delegates,
 * and finally returns the newly created child. All work runs on the EDT.
 * <p>
 * The two GUI/static touch points are injected as {@link Selector} and
 * {@link Creator} seams so the adapter can be unit-tested without a live JMeter
 * GUI or static mocking.
 */
public final class GuiElementAdder implements ElementAdder {

    private static final Logger log = LoggerFactory.getLogger(GuiElementAdder.class);

    /** Selects the parent node in the tree; returns false if the GUI is unavailable. */
    @FunctionalInterface
    public interface Selector {
        boolean select(JMeterTreeNode parent);
    }

    /** Creates the element under the currently selected node; returns success. */
    @FunctionalInterface
    public interface Creator {
        boolean create(String addAlias, String name);
    }

    private final EdtExecutor edt;
    private final Selector selector;
    private final Creator creator;

    public GuiElementAdder() {
        this(EdtExecutor.swing(), defaultSelector(), JMeterElementManager::addElement);
    }

    public GuiElementAdder(EdtExecutor edt, Selector selector, Creator creator) {
        this.edt = edt == null ? EdtExecutor.swing() : edt;
        this.selector = selector;
        this.creator = creator;
    }

    @Override
    public JMeterTreeNode add(JMeterTreeNode parent, String addAlias, String name) {
        if (parent == null || addAlias == null) {
            return null;
        }
        AtomicReference<JMeterTreeNode> result = new AtomicReference<>();
        edt.run(() -> {
            try {
                result.set(doAdd(parent, addAlias, name));
            } catch (RuntimeException e) {
                log.error("Failed to add element '{}' under '{}'", addAlias, parent.getName(), e);
                result.set(null);
            }
        });
        return result.get();
    }

    private JMeterTreeNode doAdd(JMeterTreeNode parent, String addAlias, String name) {
        // Selection bridge: addElement targets the currently selected node.
        if (!selector.select(parent)) {
            return null;
        }
        if (!creator.create(addAlias, name)) {
            return null;
        }
        int count = parent.getChildCount();
        if (count == 0) {
            return null;
        }
        TreeNode created = parent.getChildAt(count - 1);
        return created instanceof JMeterTreeNode ? (JMeterTreeNode) created : null;
    }

    /** Default selection bridge backed by the live {@link GuiPackage}. */
    private static Selector defaultSelector() {
        return parent -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return false;
            }
            gui.getTreeListener().getJTree().setSelectionPath(new TreePath(parent.getPath()));
            return true;
        };
    }
}
