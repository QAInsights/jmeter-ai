package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.EdtExecutor;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.JMeterTreeMutator;
import org.qainsights.jmeter.ai.agent.jmeter.StructuredPropertyListUpdater;
import org.qainsights.jmeter.ai.agent.schema.ElementPropertyCatalog;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code set_structured_property_list} agent tool. Replaces a structured
 * list property (HTTP headers, User Defined Variables/HTTP parameters, or
 * HTTP Authorization entries) with the given entries, in full. Only usable
 * for properties curated in {@link ElementPropertyCatalog#isStructuredListProperty};
 * flat string lists (e.g. a Response Assertion's patterns) use
 * {@code set_property_list} instead.
 */
public final class SetStructuredPropertyListHandler {

    private static final Logger log = LoggerFactory.getLogger(SetStructuredPropertyListHandler.class);

    public static final String SET_STRUCTURED_PROPERTY_LIST = "set_structured_property_list";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_ELEMENT_NOT_FOUND = "element_not_found";
    public static final String ERR_INVALID_PROPERTY = "invalid_property";
    public static final String ERR_UNSUPPORTED_PROPERTY = "unsupported_property";
    public static final String ERR_UPDATE_FAILED = "update_failed";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final ElementIdResolver resolver;
    private final StructuredPropertyListUpdater updater;

    /** Production constructor wiring the live JMeter tree and tree mutator. */
    public SetStructuredPropertyListHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, new ElementIdResolver(), defaultUpdater());
    }

    public SetStructuredPropertyListHandler(Supplier<JMeterTreeNode> rootSupplier, ElementIdResolver resolver,
                                             StructuredPropertyListUpdater updater) {
        this.rootSupplier = rootSupplier;
        this.resolver = resolver;
        this.updater = updater;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(SET_STRUCTURED_PROPERTY_LIST)
                .description("Replaces a structured list property (HTTP headers, User Defined Variables/HTTP "
                        + "parameters, or HTTP Authorization entries) with the given entries, in full. Each "
                        + "entry's expected fields depend on 'property': HeaderManager.headers and "
                        + "Arguments.arguments expect {name, value}; AuthManager.auth_list expects "
                        + "{url, username, password, domain, realm, mechanism} (mechanism is one of "
                        + "BASIC, DIGEST, KERBEROS).")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("Tree-path id of the element, e.g. "
                                + "'Test Plan/Thread Group/HTTP Request/HTTP Header Manager'.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("property", ParamType.STRING)
                        .description("JMeter structured list property key, e.g. HeaderManager.headers.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("entries", ParamType.OBJECT_ARRAY)
                        .description("The full list of entries; replaces any existing ones. An empty array "
                                + "clears it. See the tool description for the fields each entry needs.")
                        .required(true).build())
                .addPrecondition("element_id must reference an element returned by get_tree_state")
                .addPrecondition("property must be a supported structured list property for that element type "
                        + "(see get_element_schema); flat string lists like a Response Assertion's patterns "
                        + "are not supported here - use set_property_list")
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
        if (!ElementPropertyCatalog.isStructuredListProperty(type, property)) {
            return ToolResult.error(ERR_UNSUPPORTED_PROPERTY,
                    "'" + property + "' is not a supported structured list property for " + type
                            + ". Check get_element_schema for the properties set_structured_property_list "
                            + "supports on that type.");
        }

        List<Map<String, String>> entries = objectList(args.get("entries"));

        if (!updater.update(node, property, entries)) {
            return ToolResult.error(ERR_UPDATE_FAILED,
                    "Could not set '" + property + "' on '" + elementId + "'. Check that each entry has the "
                            + "fields described in get_element_schema for " + type + ".");
        }

        return ToolResult.ok("Set '" + property + "' on '" + elementId + "' with " + entries.size() + " entrie(s).");
    }

    /** Live updater that keeps the open editor panel in sync (mirrors {@code SetPropertyListHandler}). */
    private static StructuredPropertyListUpdater defaultUpdater() {
        JMeterTreeMutator mutator = new JMeterTreeMutator();
        EdtExecutor edt = EdtExecutor.swing();
        return (node, property, entries) -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return false;
            }
            boolean isCurrent = node == currentNode(gui);
            if (isCurrent) {
                edt.run(gui::updateCurrentNode);
            }
            if (!mutator.replaceStructuredPropertyList(gui.getTreeModel(), node, property, entries)) {
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
            log.warn("Could not refresh GUI after structured property list update", e);
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> objectList(Object value) {
        List<Map<String, String>> result = new ArrayList<>();
        if (!(value instanceof List)) {
            return result;
        }
        for (Object item : (List<Object>) value) {
            if (item instanceof Map) {
                Map<String, String> entry = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) item).entrySet()) {
                    entry.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
                result.add(entry);
            }
        }
        return result;
    }
}
