package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.ElementAdder;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.GuiElementAdder;
import org.qainsights.jmeter.ai.agent.schema.SchemaGrounding;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The {@code add_element} agent tool. Resolves {@code parent_id} to a live tree
 * node, validates the requested {@code element_type} against the schema, then
 * delegates creation to an {@link ElementAdder}. On success it returns the new
 * element's tree-path id so the agent can address it in follow-up calls.
 */
public final class AddElementHandler {

    public static final String ADD_ELEMENT = "add_element";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_UNKNOWN_ELEMENT_TYPE = "unknown_element_type";
    public static final String ERR_PARENT_NOT_FOUND = "parent_not_found";
    public static final String ERR_ADD_FAILED = "add_failed";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final ElementIdResolver resolver;
    private final SchemaGrounding schema;
    private final ElementAdder adder;

    /** Production constructor wiring the live JMeter tree and GUI-backed adder. */
    public AddElementHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, new ElementIdResolver(),
                new SchemaGrounding(), new GuiElementAdder());
    }

    public AddElementHandler(Supplier<JMeterTreeNode> rootSupplier, ElementIdResolver resolver,
                             SchemaGrounding schema, ElementAdder adder) {
        this.rootSupplier = rootSupplier;
        this.resolver = resolver;
        this.schema = schema;
        this.adder = adder;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(ADD_ELEMENT)
                .description("Adds a new JMeter element of the given type under the specified parent element.")
                .addParameter(ToolParameter.builder("element_type", ParamType.STRING)
                        .description("Logical element type from the schema hierarchy, e.g. HTTPSamplerProxy.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("parent_id", ParamType.STRING)
                        .description("Tree-path id of the parent, e.g. 'Test Plan/Thread Group'.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("name", ParamType.STRING)
                        .description("Optional name for the new element; a sensible default is used if omitted.")
                        .build())
                .addPrecondition("parent_id must reference an element returned by get_tree_state")
                .addPrecondition("element_type must be a type from the provided schema hierarchy")
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

        String type = string(args.get("element_type"));
        SchemaGrounding.ElementType elementType = schema.get(type);
        if (elementType == null) {
            return ToolResult.error(ERR_UNKNOWN_ELEMENT_TYPE,
                    "Unknown element type '" + type + "'. Choose a type from the provided schema hierarchy.");
        }

        String parentId = string(args.get("parent_id"));
        JMeterTreeNode parent = resolver.resolve(root, parentId);
        if (parent == null) {
            return ToolResult.error(ERR_PARENT_NOT_FOUND,
                    "No element with id '" + parentId + "'. Call get_tree_state to see current ids.");
        }

        String name = optionalName(args.get("name"));
        JMeterTreeNode created = adder.add(parent, elementType.getAddAlias(), name);
        if (created == null) {
            return ToolResult.error(ERR_ADD_FAILED,
                    "Could not add '" + type + "' under '" + parentId
                            + "'. The parent may not accept this element type.");
        }

        String label = name == null ? type : type + " '" + name + "'";
        return ToolResult.ok("Added " + label + " under '" + parentId + "'. New element id: "
                + resolver.idOf(created));
    }

    private static String optionalName(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
