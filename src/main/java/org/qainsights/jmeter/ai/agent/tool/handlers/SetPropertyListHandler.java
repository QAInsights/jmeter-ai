package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.EdtExecutor;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.JMeterTreeMutator;
import org.qainsights.jmeter.ai.agent.jmeter.PropertyListUpdater;
import org.qainsights.jmeter.ai.agent.schema.ElementPropertyCatalog;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code set_property_list} agent tool. Replaces a flat string-list
 * property (e.g. a Response Assertion's patterns) with the given values, in
 * full. Only usable for properties curated as flat string lists in
 * {@link ElementPropertyCatalog#isFlatStringListProperty} - structured lists
 * (headers, arguments, ...) need a different shape and aren't supported here.
 */
public final class SetPropertyListHandler {

    private static final Logger log = LoggerFactory.getLogger(SetPropertyListHandler.class);

    public static final String SET_PROPERTY_LIST = "set_property_list";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_ELEMENT_NOT_FOUND = "element_not_found";
    public static final String ERR_INVALID_PROPERTY = "invalid_property";
    public static final String ERR_UNSUPPORTED_PROPERTY = "unsupported_property";
    public static final String ERR_UPDATE_FAILED = "update_failed";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final ElementIdResolver resolver;
    private final PropertyListUpdater updater;

    /** Production constructor wiring the live JMeter tree and tree mutator. */
    public SetPropertyListHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, new ElementIdResolver(), defaultUpdater());
    }

    public SetPropertyListHandler(Supplier<JMeterTreeNode> rootSupplier, ElementIdResolver resolver,
                                   PropertyListUpdater updater) {
        this.rootSupplier = rootSupplier;
        this.resolver = resolver;
        this.updater = updater;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(SET_PROPERTY_LIST)
                .description("Replaces a flat string-list property (e.g. a Response Assertion's patterns) "
                        + "with the given values, in full.")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("Tree-path id of the element, e.g. 'Test Plan/Thread Group/HTTP Request/Response Assertion'.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("property", ParamType.STRING)
                        .description("JMeter list property key, e.g. Asserion.test_strings.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("values", ParamType.STRING_ARRAY)
                        .description("The full list of string values; replaces any existing entries. An empty array clears it.")
                        .required(true).build())
                .addPrecondition("element_id must reference an element returned by get_tree_state")
                .addPrecondition("property must be a flat string-list property for that element type "
                        + "(see get_element_schema); structured lists like headers or arguments are not supported")
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

        String property = string(args.get("property")).trim();
        if (property.isEmpty()) {
            return ToolResult.error(ERR_INVALID_PROPERTY, "A non-empty 'property' key is required.");
        }

        String type = node.getTestElement() == null ? null : node.getTestElement().getClass().getSimpleName();
        if (!ElementPropertyCatalog.isFlatStringListProperty(type, property)) {
            return ToolResult.error(ERR_UNSUPPORTED_PROPERTY,
                    "'" + property + "' is not a supported flat string-list property for " + type
                            + ". Check get_element_schema for the properties set_property_list supports on that type.");
        }

        List<String> values = stringList(args.get("values"));

        if (!updater.update(node, property, values)) {
            return ToolResult.error(ERR_UPDATE_FAILED,
                    "Could not set '" + property + "' on '" + elementId + "'.");
        }

        return ToolResult.ok("Set '" + property + "' = " + values + " on '" + elementId + "'.");
    }

    /** Live updater that keeps the open editor panel in sync (mirrors {@code UpdateElementPropertyHandler}). */
    private static PropertyListUpdater defaultUpdater() {
        JMeterTreeMutator mutator = new JMeterTreeMutator();
        EdtExecutor edt = EdtExecutor.swing();
        return (node, property, values) -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return false;
            }
            boolean isCurrent = node == currentNode(gui);
            if (isCurrent) {
                edt.run(gui::updateCurrentNode);
            }
            if (!mutator.replacePropertyList(gui.getTreeModel(), node, property, values)) {
                return false;
            }
            edt.run(() -> reloadPanel(gui, node, isCurrent));
            return true;
        };
    }

    private static JMeterTreeNode currentNode(GuiPackage gui) {
        return gui.getTreeListener() == null ? null : gui.getTreeListener().getCurrentNode();
    }

    private static void reloadPanel(GuiPackage gui, JMeterTreeNode node, boolean isCurrent) {
        try {
            if (isCurrent) {
                JMeterGUIComponent comp = gui.getGui(node.getTestElement());
                if (comp != null) {
                    comp.clearGui();
                    comp.configure(node.getTestElement());
                }
            }
            gui.getMainFrame().repaint();
        } catch (RuntimeException e) {
            log.warn("Could not refresh GUI after property list update", e);
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List)) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            result.add(item == null ? "" : String.valueOf(item));
        }
        return result;
    }
}
