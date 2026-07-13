package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.TreeStateFormatter;
import org.qainsights.jmeter.ai.agent.schema.SchemaGrounding;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * Read-only agent tools: {@code get_tree_state}, {@code get_element_config},
 * {@code get_element_children} and {@code get_element_schema}. These let the
 * agent observe the live test plan and look up element-type schemas without
 * mutating anything.
 */
public final class ReadToolHandlers {

    public static final String GET_TREE_STATE = "get_tree_state";
    public static final String GET_ELEMENT_CONFIG = "get_element_config";
    public static final String GET_ELEMENT_CHILDREN = "get_element_children";
    public static final String GET_ELEMENT_SCHEMA = "get_element_schema";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_ELEMENT_NOT_FOUND = "element_not_found";
    public static final String ERR_UNKNOWN_ELEMENT_TYPE = "unknown_element_type";

    private static final int DEFAULT_DEPTH = 2;
    private static final int MAX_VALUE_CHARS = 300;

    /** Supplies the current tree root; abstracted so tests need no live GuiPackage. */
    @FunctionalInterface
    public interface TreeProvider {
        JMeterTreeNode getRoot();
    }

    private final TreeProvider treeProvider;
    private final ElementIdResolver resolver;
    private final TreeStateFormatter formatter;
    private final SchemaGrounding schema;

    /** Production constructor wiring the live JMeter tree. */
    public ReadToolHandlers() {
        this(guiPackageTree(), new ElementIdResolver(), new SchemaGrounding());
    }

    public ReadToolHandlers(TreeProvider treeProvider, ElementIdResolver resolver, SchemaGrounding schema) {
        this.treeProvider = treeProvider;
        this.resolver = resolver;
        this.schema = schema;
        this.formatter = new TreeStateFormatter(resolver);
    }

    /** Returns the live-tree provider backed by {@link GuiPackage}. */
    public static TreeProvider guiPackageTree() {
        return () -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return null;
            }
            JMeterTreeModel model = gui.getTreeModel();
            if (model == null) {
                return null;
            }
            Object root = model.getRoot();
            return root instanceof JMeterTreeNode ? (JMeterTreeNode) root : null;
        };
    }

    /** All read tools, ready to register in a {@code ToolRegistry}. */
    public List<Tool> tools() {
        return Arrays.asList(treeStateTool(), elementConfigTool(), elementChildrenTool(), elementSchemaTool());
    }

    // ── tool definitions ───────────────────────────────────────────────────────

    private Tool treeStateTool() {
        ToolSpec spec = ToolSpec.builder(GET_TREE_STATE)
                .description("Returns a compact, depth-limited snapshot of the test plan tree with element ids.")
                .addParameter(ToolParameter.builder("depth", ParamType.INTEGER)
                        .description("Levels below root to include (default " + DEFAULT_DEPTH + "; negative = full).")
                        .build())
                .build();
        return tool(spec, args -> {
            JMeterTreeNode root = treeProvider.getRoot();
            if (root == null) {
                return ToolResult.error(ERR_NO_TEST_PLAN, "No test plan is currently open.");
            }
            String snapshot = formatter.format(root, parseDepth(args));
            return ToolResult.ok(snapshot.isEmpty() ? "(empty test plan)" : snapshot);
        });
    }

    private Tool elementConfigTool() {
        ToolSpec spec = ToolSpec.builder(GET_ELEMENT_CONFIG)
                .description("Returns the properties of the element identified by element_id.")
                .addParameter(idParam())
                .build();
        return tool(spec, args -> withElement(args, this::formatConfig));
    }

    private Tool elementChildrenTool() {
        ToolSpec spec = ToolSpec.builder(GET_ELEMENT_CHILDREN)
                .description("Returns the direct children of the element identified by element_id.")
                .addParameter(idParam())
                .build();
        return tool(spec, args -> withElement(args, this::formatChildren));
    }

    private Tool elementSchemaTool() {
        ToolSpec spec = ToolSpec.builder(GET_ELEMENT_SCHEMA)
                .description("Returns the category, valid parents and description for an element type.")
                .addParameter(ToolParameter.builder("element_type", ParamType.STRING)
                        .description("Logical element type, e.g. HTTPSamplerProxy.").required(true).build())
                .build();
        return tool(spec, args -> {
            String type = string(args.get("element_type"));
            String detail = schema.schemaFor(type);
            if (detail == null) {
                return ToolResult.error(ERR_UNKNOWN_ELEMENT_TYPE,
                        "Unknown element type '" + type + "'. Choose a type from the provided hierarchy.");
            }
            return ToolResult.ok(detail);
        });
    }

    // ── shared helpers ─────────────────────────────────────────────────────────

    private ToolResult withElement(Map<String, Object> args, Function<JMeterTreeNode, ToolResult> action) {
        JMeterTreeNode root = treeProvider.getRoot();
        if (root == null) {
            return ToolResult.error(ERR_NO_TEST_PLAN, "No test plan is currently open.");
        }
        String id = string(args.get("element_id"));
        JMeterTreeNode node = resolver.resolve(root, id);
        if (node == null) {
            return ToolResult.error(ERR_ELEMENT_NOT_FOUND,
                    "No element with id '" + id + "'. Call get_tree_state to see current ids.");
        }
        return action.apply(node);
    }

    private ToolResult formatConfig(JMeterTreeNode node) {
        TestElement element = node.getTestElement();
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(element.getClass().getSimpleName()).append("] ").append(node.getName()).append('\n');
        sb.append("id=").append(resolver.idOf(node)).append('\n');
        sb.append("enabled=").append(node.isEnabled()).append('\n');
        sb.append("properties:\n");
        PropertyIterator it = element.propertyIterator();
        while (it.hasNext()) {
            JMeterProperty property = it.next();
            String name = property.getName();
            if (name == null || name.startsWith("TestElement.")) {
                continue;
            }
            sb.append("  ").append(name).append(" = ").append(truncate(property.getStringValue())).append('\n');
        }
        return ToolResult.ok(sb.toString());
    }

    private ToolResult formatChildren(JMeterTreeNode node) {
        int count = node.getChildCount();
        if (count == 0) {
            return ToolResult.ok("No children.");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            Object raw = node.getChildAt(i);
            if (!(raw instanceof JMeterTreeNode)) {
                continue;
            }
            JMeterTreeNode child = (JMeterTreeNode) raw;
            TestElement element = child.getTestElement();
            String type = element == null ? "?" : element.getClass().getSimpleName();
            sb.append('[').append(type).append("] ").append(child.getName())
                    .append(" (id=").append(resolver.idOf(child)).append(")\n");
        }
        return ToolResult.ok(sb.toString());
    }

    private int parseDepth(Map<String, Object> args) {
        Object raw = args.get("depth");
        if (raw == null) {
            return DEFAULT_DEPTH;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return DEFAULT_DEPTH;
        }
    }

    private static ToolParameter idParam() {
        return ToolParameter.builder("element_id", ParamType.STRING)
                .description("Tree-path id of the target element, e.g. 'Test Plan/Thread Group'.")
                .required(true).build();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replace("\r", "").replace("\n", "\\n");
        return oneLine.length() > MAX_VALUE_CHARS ? oneLine.substring(0, MAX_VALUE_CHARS) + "... [truncated]" : oneLine;
    }

    private static Tool tool(ToolSpec spec, Function<Map<String, Object>, ToolResult> fn) {
        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return fn.apply(arguments);
            }
        };
    }
}
