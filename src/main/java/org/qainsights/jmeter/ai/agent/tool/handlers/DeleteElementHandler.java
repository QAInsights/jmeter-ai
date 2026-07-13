package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Map;
import java.util.function.Supplier;

import javax.swing.tree.TreeNode;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.ElementDeleter;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.JMeterTreeMutator;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The {@code delete_element} agent tool. Resolves {@code element_id} to a live
 * tree node and removes it, delegating the mutation to an {@link ElementDeleter}.
 * <p>
 * Safety guards: the Test Plan / tree root cannot be deleted, and deleting a node
 * that still has children requires an explicit {@code force=true} (a two-step
 * confirmation that prevents accidental subtree removal).
 */
public final class DeleteElementHandler {

    public static final String DELETE_ELEMENT = "delete_element";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_ELEMENT_NOT_FOUND = "element_not_found";
    public static final String ERR_CANNOT_DELETE_ROOT = "cannot_delete_root";
    public static final String ERR_CONFIRM_REQUIRED = "confirm_required";
    public static final String ERR_DELETE_FAILED = "delete_failed";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final ElementIdResolver resolver;
    private final ElementDeleter deleter;

    /** Production constructor wiring the live JMeter tree and tree mutator. */
    public DeleteElementHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, new ElementIdResolver(), defaultDeleter());
    }

    public DeleteElementHandler(Supplier<JMeterTreeNode> rootSupplier, ElementIdResolver resolver,
                                ElementDeleter deleter) {
        this.rootSupplier = rootSupplier;
        this.resolver = resolver;
        this.deleter = deleter;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(DELETE_ELEMENT)
                .description("Deletes an element (and, with force, its subtree) from the test plan.")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("Tree-path id of the element to delete, e.g. 'Test Plan/Thread Group/HTTP Request'.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("force", ParamType.BOOLEAN)
                        .description("Set true to delete an element that still has children (deletes the whole subtree).")
                        .build())
                .addPrecondition("element_id must reference an element returned by get_tree_state")
                .addPrecondition("the Test Plan (tree root) cannot be deleted")
                .build();

        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return handle(arguments);
            }
        };
    }

    private ToolResult handle(Map<String, Object> args) {
        JMeterTreeNode root = rootSupplier.get();
        if (root == null) {
            return ToolResult.error(ERR_NO_TEST_PLAN, "No test plan is currently open.");
        }

        String elementId = string(args.get("element_id"));
        JMeterTreeNode node = resolver.resolve(root, elementId);
        if (node == null) {
            return ToolResult.error(ERR_ELEMENT_NOT_FOUND,
                    "No element with id '" + elementId + "'. Call get_tree_state to see current ids.");
        }

        if (isTopLevel(node, root)) {
            return ToolResult.error(ERR_CANNOT_DELETE_ROOT, "The Test Plan / tree root cannot be deleted.");
        }

        boolean force = bool(args.get("force"));
        int childCount = node.getChildCount();
        if (childCount > 0 && !force) {
            return ToolResult.error(ERR_CONFIRM_REQUIRED,
                    "'" + elementId + "' has " + childCount + " child element(s). "
                            + "Re-call with force=true to delete it and its subtree.");
        }

        if (!deleter.delete(node, force)) {
            return ToolResult.error(ERR_DELETE_FAILED, "Could not delete '" + elementId + "'.");
        }

        return ToolResult.ok("Deleted '" + elementId + "'.");
    }

    /** True if the node is the origin root itself or a direct child of it (the Test Plan). */
    private static boolean isTopLevel(JMeterTreeNode node, JMeterTreeNode root) {
        TreeNode parent = node.getParent();
        return node == root || parent == null || parent == root;
    }

    /** Live deleter: pulls the tree model from the GUI and delegates to the mutator. */
    private static ElementDeleter defaultDeleter() {
        JMeterTreeMutator mutator = new JMeterTreeMutator();
        return (node, force) -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return false;
            }
            return mutator.deleteElement(gui.getTreeModel(), node, force);
        };
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
