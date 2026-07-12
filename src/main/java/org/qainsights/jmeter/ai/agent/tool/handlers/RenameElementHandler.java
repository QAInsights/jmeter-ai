package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.ElementRenamer;
import org.qainsights.jmeter.ai.agent.jmeter.JMeterTreeMutator;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The {@code rename_element} agent tool. Resolves {@code element_id} to a live
 * tree node and renames it, delegating the mutation to an {@link ElementRenamer}.
 * A dedicated verb rather than routing through {@code update_element_property}
 * on {@code TestElement.name}, since the name isn't a schema-catalog property
 * and renaming changes the element's tree-path id - the new id is reported
 * back for follow-up calls.
 */
public final class RenameElementHandler {

    public static final String RENAME_ELEMENT = "rename_element";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_ELEMENT_NOT_FOUND = "element_not_found";
    public static final String ERR_INVALID_NAME = "invalid_name";
    public static final String ERR_RENAME_FAILED = "rename_failed";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final ElementIdResolver resolver;
    private final ElementRenamer renamer;

    /** Production constructor wiring the live JMeter tree and tree mutator. */
    public RenameElementHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, new ElementIdResolver(), defaultRenamer());
    }

    public RenameElementHandler(Supplier<JMeterTreeNode> rootSupplier, ElementIdResolver resolver,
                                 ElementRenamer renamer) {
        this.rootSupplier = rootSupplier;
        this.resolver = resolver;
        this.renamer = renamer;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(RENAME_ELEMENT)
                .description("Renames an element.")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("Tree-path id of the element to rename, e.g. 'Test Plan/Thread Group'.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("new_name", ParamType.STRING)
                        .description("The new name for the element.")
                        .required(true).build())
                .addPrecondition("element_id must reference an element returned by get_tree_state")
                .addPrecondition("new_name must not be blank")
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

        String newName = string(args.get("new_name")).trim();
        if (newName.isEmpty()) {
            return ToolResult.error(ERR_INVALID_NAME, "'new_name' must not be blank.");
        }

        if (!renamer.rename(node, newName)) {
            return ToolResult.error(ERR_RENAME_FAILED, "Could not rename '" + elementId + "'.");
        }

        return ToolResult.ok("Renamed '" + elementId + "' to '" + newName + "'. New element id: '"
                + resolver.idOf(node) + "'.");
    }

    /** Live renamer: pulls the tree model from the GUI and delegates to the mutator. */
    private static ElementRenamer defaultRenamer() {
        JMeterTreeMutator mutator = new JMeterTreeMutator();
        return (node, newName) -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return false;
            }
            return mutator.renameElement(gui.getTreeModel(), node, newName);
        };
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
