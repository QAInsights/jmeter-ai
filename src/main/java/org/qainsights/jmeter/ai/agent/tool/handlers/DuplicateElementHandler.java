package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.ElementDuplicator;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.JMeterTreeMutator;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The {@code duplicate_element} agent tool. Deep-clones an element's subtree
 * and inserts the clone as a new sibling immediately after it, under the same
 * parent - the same result as JMeter's own Copy+Paste/Duplicate menu command,
 * delegating the mutation to an {@link ElementDuplicator}.
 * <p>
 * Guard: the Test Plan / tree root cannot be duplicated (it has no parent to
 * insert a sibling under). The clone gets a new tree-path id, reported back
 * for follow-up calls.
 */
public final class DuplicateElementHandler {

    public static final String DUPLICATE_ELEMENT = "duplicate_element";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_ELEMENT_NOT_FOUND = "element_not_found";
    public static final String ERR_CANNOT_DUPLICATE_ROOT = "cannot_duplicate_root";
    public static final String ERR_DUPLICATE_FAILED = "duplicate_failed";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final ElementIdResolver resolver;
    private final ElementDuplicator duplicator;

    /** Production constructor wiring the live JMeter tree and tree mutator. */
    public DuplicateElementHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, new ElementIdResolver(), defaultDuplicator());
    }

    public DuplicateElementHandler(Supplier<JMeterTreeNode> rootSupplier, ElementIdResolver resolver,
                                    ElementDuplicator duplicator) {
        this.rootSupplier = rootSupplier;
        this.resolver = resolver;
        this.duplicator = duplicator;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(DUPLICATE_ELEMENT)
                .description("Duplicates an element (and its subtree) as a new sibling immediately after it, "
                        + "under the same parent - the same result as JMeter's own Copy+Paste/Duplicate menu "
                        + "command.")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("Tree-path id of the element to duplicate.")
                        .required(true).build())
                .addPrecondition("element_id must reference an element returned by get_tree_state")
                .addPrecondition("the Test Plan (tree root) cannot be duplicated")
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
            return ToolResult.error(ERR_CANNOT_DUPLICATE_ROOT, "The Test Plan / tree root cannot be duplicated.");
        }

        JMeterTreeNode duplicate = duplicator.duplicate(node);
        if (duplicate == null) {
            return ToolResult.error(ERR_DUPLICATE_FAILED, "Could not duplicate '" + elementId + "'.");
        }

        return ToolResult.ok("Duplicated '" + elementId + "'. New element id: '" + resolver.idOf(duplicate) + "'.");
    }

    /** Live duplicator: pulls the tree model from the GUI and delegates to the mutator. */
    private static ElementDuplicator defaultDuplicator() {
        JMeterTreeMutator mutator = new JMeterTreeMutator();
        return node -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return null;
            }
            return mutator.duplicateElement(gui.getTreeModel(), node);
        };
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
