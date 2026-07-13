package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.ElementEnabler;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.JMeterTreeMutator;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The {@code toggle_element} agent tool. Resolves {@code element_id} to a live
 * tree node and enables or disables it, delegating the mutation to an
 * {@link ElementEnabler}. Disabled elements are skipped when the test runs.
 */
public final class ToggleElementHandler {

    public static final String TOGGLE_ELEMENT = "toggle_element";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_ELEMENT_NOT_FOUND = "element_not_found";
    public static final String ERR_INVALID_STATE = "invalid_state";
    public static final String ERR_TOGGLE_FAILED = "toggle_failed";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final ElementIdResolver resolver;
    private final ElementEnabler enabler;

    /** Production constructor wiring the live JMeter tree and tree mutator. */
    public ToggleElementHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, new ElementIdResolver(), defaultEnabler());
    }

    public ToggleElementHandler(Supplier<JMeterTreeNode> rootSupplier, ElementIdResolver resolver,
                                ElementEnabler enabler) {
        this.rootSupplier = rootSupplier;
        this.resolver = resolver;
        this.enabler = enabler;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(TOGGLE_ELEMENT)
                .description("Enables or disables a JMeter element (disabled elements are skipped when the test runs).")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("Tree-path id of the element, e.g. 'Test Plan/Thread Group/HTTP Request'.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("enabled", ParamType.BOOLEAN)
                        .description("true to enable the element, false to disable it.")
                        .required(true).build())
                .addPrecondition("element_id must reference an element returned by get_tree_state")
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

        Boolean enabled = parseBoolean(args.get("enabled"));
        if (enabled == null) {
            return ToolResult.error(ERR_INVALID_STATE, "'enabled' must be true or false.");
        }

        if (!enabler.setEnabled(node, enabled)) {
            return ToolResult.error(ERR_TOGGLE_FAILED,
                    "Could not " + (enabled ? "enable" : "disable") + " '" + elementId + "'.");
        }

        return ToolResult.ok((enabled ? "Enabled '" : "Disabled '") + elementId + "'.");
    }

    /** Live enabler: pulls the tree model from the GUI and delegates to the mutator. */
    private static ElementEnabler defaultEnabler() {
        JMeterTreeMutator mutator = new JMeterTreeMutator();
        return (node, enabled) -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return false;
            }
            return mutator.setEnabled(gui.getTreeModel(), node, enabled);
        };
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** Parses a boolean from a Boolean or the strings "true"/"false"; null if absent/invalid. */
    private static Boolean parseBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim().toLowerCase();
        if (s.equals("true")) {
            return Boolean.TRUE;
        }
        if (s.equals("false")) {
            return Boolean.FALSE;
        }
        return null;
    }
}
