package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.ElementMover;
import org.qainsights.jmeter.ai.agent.jmeter.JMeterTreeMutator;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The {@code move_element} agent tool. Reparents an element to become the last
 * child of another element, delegating the mutation to an {@link ElementMover}.
 * <p>
 * Guards: the Test Plan / tree root cannot be moved, and an element cannot be
 * moved onto itself or one of its own descendants. The element's id changes
 * after a move, so the new id is reported back for follow-up calls.
 */
public final class MoveElementHandler {

    public static final String MOVE_ELEMENT = "move_element";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_ELEMENT_NOT_FOUND = "element_not_found";
    public static final String ERR_PARENT_NOT_FOUND = "parent_not_found";
    public static final String ERR_CANNOT_MOVE_ROOT = "cannot_move_root";
    public static final String ERR_MOVE_FAILED = "move_failed";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final ElementIdResolver resolver;
    private final ElementMover mover;

    /** Production constructor wiring the live JMeter tree and tree mutator. */
    public MoveElementHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, new ElementIdResolver(), defaultMover());
    }

    public MoveElementHandler(Supplier<JMeterTreeNode> rootSupplier, ElementIdResolver resolver,
                              ElementMover mover) {
        this.rootSupplier = rootSupplier;
        this.resolver = resolver;
        this.mover = mover;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(MOVE_ELEMENT)
                .description("Moves an element to become the last child of another element.")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("Tree-path id of the element to move.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("new_parent_id", ParamType.STRING)
                        .description("Tree-path id of the destination parent element.")
                        .required(true).build())
                .addPrecondition("both ids must reference elements returned by get_tree_state")
                .addPrecondition("an element cannot be moved onto itself or one of its descendants")
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

        if (node == root || node.getParent() == null || node.getParent() == root) {
            return ToolResult.error(ERR_CANNOT_MOVE_ROOT, "The Test Plan / tree root cannot be moved.");
        }

        String newParentId = string(args.get("new_parent_id"));
        JMeterTreeNode newParent = resolver.resolve(root, newParentId);
        if (newParent == null) {
            return ToolResult.error(ERR_PARENT_NOT_FOUND,
                    "No parent element with id '" + newParentId + "'. Call get_tree_state to see current ids.");
        }

        if (!mover.move(node, newParent)) {
            return ToolResult.error(ERR_MOVE_FAILED,
                    "Could not move '" + elementId + "' under '" + newParentId
                            + "'. A node cannot be moved onto itself or a descendant.");
        }

        String newId = resolver.idOf(node);
        return ToolResult.ok("Moved '" + elementId + "' under '" + newParentId + "'. New id: '" + newId + "'.");
    }

    /** Live mover: pulls the tree model from the GUI and delegates to the mutator. */
    private static ElementMover defaultMover() {
        JMeterTreeMutator mutator = new JMeterTreeMutator();
        return (node, newParent) -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return false;
            }
            return mutator.moveElement(gui.getTreeModel(), node, newParent);
        };
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
