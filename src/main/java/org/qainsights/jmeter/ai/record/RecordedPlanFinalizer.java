package org.qainsights.jmeter.ai.record;

import java.util.ArrayList;
import java.util.List;
import javax.swing.tree.TreeNode;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the captured recording into a plan a user would actually keep: everything under the
 * Recording Controller is promoted into the Thread Group, and the emptied Recording
 * Controller is removed.
 * <p>
 * A Recording Controller is a capture target, not test structure. Leaving it in place would
 * ship the scaffolding with the deliverable - the same manual tidy-up every JMeter user does
 * by hand after recording. Promotion is done rather than recording straight into the Thread
 * Group because {@code ProxyControl} needs a single stable target node while traffic is
 * arriving, and the business-step Transaction Controllers are created underneath it.
 * <p>
 * This must run only after the proxy has stopped. The recorder's target points at nodes
 * inside this subtree, so reparenting them mid-capture would route later samplers into a
 * node that is no longer where the proxy believes it is.
 */
public final class RecordedPlanFinalizer {

    private static final Logger log = LoggerFactory.getLogger(RecordedPlanFinalizer.class);

    private RecordedPlanFinalizer() {
    }

    /**
     * Moves the Recording Controller's children into the Thread Group, preserving their
     * order, then removes the Recording Controller.
     * <p>
     * Order is preserved because it is the recorded sequence of the journey: a plan whose
     * steps are shuffled does not reproduce the user's flow. Children are snapshotted before
     * any mutation, since removing from a live tree model shifts the indices underneath an
     * in-progress iteration.
     * <p>
     * When nothing was captured the tree is left exactly as it is. Removing the controller
     * would leave a bare Thread Group that gives no hint about what was attempted, and an
     * empty scaffold is more informative to a user diagnosing a recording that caught
     * nothing.
     *
     * @param model    the tree model being edited
     * @param scaffold the scaffold whose Recording Controller should be dissolved
     * @return the number of nodes promoted; 0 when nothing was captured
     * @throws RecordingException if {@code model} or {@code scaffold} is null
     */
    public static int promoteRecordedSteps(JMeterTreeModel model, RecordingScaffold scaffold) {
        if (model == null || scaffold == null) {
            throw new RecordingException("Cannot finalize a plan without a model and scaffold");
        }
        JMeterTreeNode threadGroupNode = scaffold.threadGroupNode();
        JMeterTreeNode recordingControllerNode = scaffold.recordingControllerNode();

        List<JMeterTreeNode> children = childrenOf(recordingControllerNode);
        if (children.isEmpty()) {
            log.info("Nothing was recorded, leaving the Recording Controller in place");
            return 0;
        }

        int insertAt = insertionIndex(threadGroupNode, recordingControllerNode);
        for (JMeterTreeNode child : children) {
            model.removeNodeFromParent(child);
            model.insertNodeInto(child, threadGroupNode, insertAt);
            insertAt++;
        }

        // Only ever removed once empty, so a failure here cannot lose recorded samplers.
        model.removeNodeFromParent(recordingControllerNode);
        log.info("Promoted {} recorded node(s) into thread group '{}'", children.size(),
                threadGroupNode.getName());
        return children.size();
    }

    /**
     * The number of HTTP samplers actually present under {@code node}.
     * <p>
     * This exists because {@code JMeterProxyRecorder.sampleCount()} counts sample
     * <em>listener notifications</em>, and {@code ProxyControl} notifies listeners even for
     * requests its filters reject. Reporting that number let a recording claim "1,557
     * samplers" when the include pattern matched nothing and every Transaction Controller was
     * empty. Counting the nodes in the plan cannot lie in that direction.
     *
     * @param node the subtree to count within, typically the recording Thread Group
     * @return the number of {@code HTTPSamplerBase} elements in the subtree, 0 for null
     */
    public static int countSamplers(JMeterTreeNode node) {
        if (node == null) {
            return 0;
        }
        int count = node.getTestElement() instanceof HTTPSamplerBase ? 1 : 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            TreeNode child = node.getChildAt(i);
            if (child instanceof JMeterTreeNode) {
                count += countSamplers((JMeterTreeNode) child);
            }
        }
        return count;
    }

    private static List<JMeterTreeNode> childrenOf(JMeterTreeNode parent) {
        List<JMeterTreeNode> children = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            TreeNode child = parent.getChildAt(i);
            if (child instanceof JMeterTreeNode) {
                children.add((JMeterTreeNode) child);
            }
        }
        return children;
    }

    /**
     * The promoted nodes take the Recording Controller's own slot, which keeps them after the
     * Cookie Manager, Cache Manager and HTTP Request Defaults. Appending at the end is the
     * fallback for an unexpected tree shape; config elements apply to the whole Thread Group
     * regardless of position, so the plan still behaves correctly.
     */
    private static int insertionIndex(JMeterTreeNode threadGroupNode,
                                      JMeterTreeNode recordingControllerNode) {
        int index = threadGroupNode.getIndex(recordingControllerNode);
        return index < 0 ? threadGroupNode.getChildCount() : index;
    }
}
