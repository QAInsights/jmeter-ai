package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.ElementReorderer;
import org.qainsights.jmeter.ai.agent.jmeter.JMeterTreeMutator;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The {@code reorder_element} agent tool. Repositions an element among its
 * current siblings (without changing its parent), delegating the mutation to
 * an {@link ElementReorderer}. Complements {@code move_element}, which always
 * appends as the last child of a (possibly different) parent.
 * <p>
 * Guards: the Test Plan / tree root cannot be reordered, and {@code new_index}
 * must be a valid 0-based position among the element's current siblings.
 */
public final class ReorderElementHandler {

    public static final String REORDER_ELEMENT = "reorder_element";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_ELEMENT_NOT_FOUND = "element_not_found";
    public static final String ERR_CANNOT_REORDER_ROOT = "cannot_reorder_root";
    public static final String ERR_INVALID_INDEX = "invalid_index";
    public static final String ERR_REORDER_FAILED = "reorder_failed";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final ElementIdResolver resolver;
    private final ElementReorderer reorderer;

    /** Production constructor wiring the live JMeter tree and tree mutator. */
    public ReorderElementHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, new ElementIdResolver(), defaultReorderer());
    }

    public ReorderElementHandler(Supplier<JMeterTreeNode> rootSupplier, ElementIdResolver resolver,
                                  ElementReorderer reorderer) {
        this.rootSupplier = rootSupplier;
        this.resolver = resolver;
        this.reorderer = reorderer;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(REORDER_ELEMENT)
                .description("Repositions an element among its current siblings (0-based new_index), without "
                        + "changing its parent. Complements move_element, which always appends as the last "
                        + "child of a (possibly different) parent.")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("Tree-path id of the element to reposition.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("new_index", ParamType.INTEGER)
                        .description("0-based position among the element's current siblings to move it to.")
                        .required(true).build())
                .addPrecondition("element_id must reference an element returned by get_tree_state")
                .addPrecondition("new_index must be within [0, sibling count - 1]")
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
            return ToolResult.error(ERR_CANNOT_REORDER_ROOT, "The Test Plan / tree root cannot be reordered.");
        }

        Integer newIndex = integer(args.get("new_index"));
        if (newIndex == null) {
            return ToolResult.error(ERR_INVALID_INDEX, "new_index is required and must be an integer.");
        }

        int siblingCount = node.getParent().getChildCount();
        if (newIndex < 0 || newIndex >= siblingCount) {
            return ToolResult.error(ERR_INVALID_INDEX,
                    "new_index must be between 0 and " + (siblingCount - 1) + " (inclusive) for '" + elementId
                            + "'.");
        }

        if (!reorderer.reorder(node, newIndex)) {
            return ToolResult.error(ERR_REORDER_FAILED,
                    "Could not reorder '" + elementId + "' to index " + newIndex + ".");
        }

        String newId = resolver.idOf(node);
        return ToolResult.ok("Reordered '" + elementId + "' to index " + newIndex + ". New id: '" + newId + "'.");
    }

    /** Live reorderer: pulls the tree model from the GUI and delegates to the mutator. */
    private static ElementReorderer defaultReorderer() {
        JMeterTreeMutator mutator = new JMeterTreeMutator();
        return (node, newIndex) -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return false;
            }
            return mutator.reorderElement(gui.getTreeModel(), node, newIndex);
        };
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
