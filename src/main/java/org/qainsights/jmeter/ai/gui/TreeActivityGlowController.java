package org.qainsights.jmeter.ai.gui;

import java.awt.Rectangle;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.AgentTargetResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the "agent is working on this element" glow on JMeter's live test-plan
 * tree: for each tool call the agent is about to run, resolves its
 * {@code element_id} argument (falling back to the Test Plan node - see
 * {@link AgentTargetResolver}) and feeds the resolved node into a
 * {@link TreeActivitySequencer}, which paces visibility. A shared {@link Timer}
 * (matching {@link GeminiBorderPanel}'s own animation rate) advances the rotation
 * angle, repaints just the affected row(s), and lazily installs an
 * {@link AgentActivityCellRenderer} decorator over the tree's existing renderer.
 * <p>
 * {@link #onToolCallStarted} / {@link #onRunFinished} are safe to call from any
 * thread (the agent runs off-EDT); they hop to the EDT before touching Swing state.
 */
public final class TreeActivityGlowController {

    private static final Logger log = LoggerFactory.getLogger(TreeActivityGlowController.class);
    private static final int TICK_MILLIS = 30;
    private static final int ROTATION_STEP_DEGREES = 4;

    private final AgentTargetResolver targetResolver;
    private final TreeActivitySequencer<JMeterTreeNode> sequencer = new TreeActivitySequencer<>();
    private final Timer timer;

    private JTree installedOn;
    private int rotationAngle;
    private JMeterTreeNode paintedNode;
    private float paintedAlpha;

    public TreeActivityGlowController() {
        this(new AgentTargetResolver());
    }

    TreeActivityGlowController(AgentTargetResolver targetResolver) {
        this.targetResolver = targetResolver;
        this.timer = new Timer(TICK_MILLIS, e -> tick());
    }

    /**
     * Called for every tool call the agent is about to execute, with that call's
     * {@code element_id} argument (or {@code null} if it has none).
     */
    public void onToolCallStarted(String elementId) {
        SwingUtilities.invokeLater(() -> handleToolCallStarted(elementId));
    }

    /** Called once the whole agent run has finished (successfully or not). */
    public void onRunFinished() {
        SwingUtilities.invokeLater(this::handleRunFinished);
    }

    /** Package-private for direct (EDT-hop-free) testing; real callers use {@link #onToolCallStarted}. */
    void handleToolCallStarted(String elementId) {
        JMeterTreeNode node = resolveTarget(elementId);
        if (node == null) {
            return;
        }
        ensureInstalled();
        sequencer.enqueue(node);
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    /** Package-private for direct (EDT-hop-free) testing; real callers use {@link #onRunFinished}. */
    void handleRunFinished() {
        sequencer.finish();
    }

    /** Test-only observability hook: whether the animation {@link Timer} is currently running. */
    boolean isAnimating() {
        return timer.isRunning();
    }

    /** Test-only observability hook: the {@link JTree} the activity renderer is installed on, if any. */
    JTree getInstalledTree() {
        return installedOn;
    }

    private JMeterTreeNode resolveTarget(String elementId) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            return null;
        }
        JMeterTreeModel model = gui.getTreeModel();
        if (model == null) {
            return null;
        }
        Object root = model.getRoot();
        if (!(root instanceof JMeterTreeNode)) {
            return null;
        }
        return targetResolver.resolve((JMeterTreeNode) root, elementId);
    }

    private void ensureInstalled() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getTreeListener() == null) {
            return;
        }
        JTree tree = gui.getTreeListener().getJTree();
        if (tree == null || tree == installedOn) {
            return;
        }
        try {
            TreeCellRenderer original = tree.getCellRenderer();
            tree.setCellRenderer(new AgentActivityCellRenderer(original, () -> paintedNode,
                    () -> rotationAngle, () -> paintedAlpha));
            installedOn = tree;
        } catch (RuntimeException e) {
            log.warn("Could not install the agent-activity tree renderer; highlight animation disabled", e);
        }
    }

    private void tick() {
        TreeActivitySequencer.Frame<JMeterTreeNode> frame = sequencer.tick(System.currentTimeMillis());
        JMeterTreeNode previous = paintedNode;
        paintedNode = frame.getTarget();
        paintedAlpha = frame.getAlpha();
        rotationAngle = (rotationAngle + ROTATION_STEP_DEGREES) % 360;

        repaintRowFor(previous);
        repaintRowFor(paintedNode);

        if (frame.isIdle() && previous == null) {
            timer.stop();
        }
    }

    private void repaintRowFor(JMeterTreeNode node) {
        if (node == null || installedOn == null) {
            return;
        }
        try {
            Rectangle bounds = installedOn.getPathBounds(new TreePath(node.getPath()));
            if (bounds != null) {
                installedOn.repaint(bounds);
            }
        } catch (RuntimeException ignored) {
            // The node may have been removed from the tree since it was queued; nothing to repaint.
        }
    }
}
