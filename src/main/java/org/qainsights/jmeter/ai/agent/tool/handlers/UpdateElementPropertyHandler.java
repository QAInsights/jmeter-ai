package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.EdtExecutor;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.JMeterTreeMutator;
import org.qainsights.jmeter.ai.agent.jmeter.PropertyUpdater;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code update_element_property} agent tool. Resolves {@code element_id} to
 * a live tree node and sets a single property on it, delegating the mutation to
 * a {@link PropertyUpdater}. Used to configure elements after they are added
 * (e.g. an HTTP Sampler's {@code HTTPSampler.path}).
 */
public final class UpdateElementPropertyHandler {

    private static final Logger log = LoggerFactory.getLogger(UpdateElementPropertyHandler.class);

    public static final String UPDATE_ELEMENT_PROPERTY = "update_element_property";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_ELEMENT_NOT_FOUND = "element_not_found";
    public static final String ERR_INVALID_PROPERTY = "invalid_property";
    public static final String ERR_UPDATE_FAILED = "update_failed";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final ElementIdResolver resolver;
    private final PropertyUpdater updater;

    /** Production constructor wiring the live JMeter tree and tree mutator. */
    public UpdateElementPropertyHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, new ElementIdResolver(), defaultUpdater());
    }

    public UpdateElementPropertyHandler(Supplier<JMeterTreeNode> rootSupplier, ElementIdResolver resolver,
                                        PropertyUpdater updater) {
        this.rootSupplier = rootSupplier;
        this.resolver = resolver;
        this.updater = updater;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(UPDATE_ELEMENT_PROPERTY)
                .description("Sets a single property on an existing JMeter element.")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("Tree-path id of the element, e.g. 'Test Plan/Thread Group/HTTP Request'.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("property", ParamType.STRING)
                        .description("JMeter property key, e.g. HTTPSampler.path or HTTPSampler.domain.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("value", ParamType.STRING)
                        .description("New value for the property; empty string clears it.")
                        .required(true).build())
                .addPrecondition("element_id must reference an element returned by get_tree_state")
                .addPrecondition("property must be a valid property key for that element type (see get_element_schema)")
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

        String value = string(args.get("value"));
        if (!updater.update(node, property, value)) {
            return ToolResult.error(ERR_UPDATE_FAILED,
                    "Could not set '" + property + "' on '" + elementId + "'.");
        }

        return ToolResult.ok("Set '" + property + "' = '" + value + "' on '" + elementId + "'.");
    }

    /**
     * Live updater that keeps the open editor panel in sync. Order matters:
     * <ol>
     *   <li>If the target is the selected node, {@code updateCurrentNode()} first
     *       flushes any genuine pending panel edits into the element.</li>
     *   <li>The mutator sets the property and fires {@code nodeChanged}.</li>
     *   <li>The panel is reloaded via {@code getGui(element)} +
     *       {@code clearGui()/configure()}.</li>
     * </ol>
     * We deliberately avoid {@code getCurrentGui()} for the reload: it internally
     * calls {@code updateCurrentNode()}, which would write the stale panel values
     * back over the property we just set. {@code getGui(element)} returns the same
     * displayed component instance without that side effect.
     */
    private static PropertyUpdater defaultUpdater() {
        JMeterTreeMutator mutator = new JMeterTreeMutator();
        EdtExecutor edt = EdtExecutor.swing();
        return (node, property, value) -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return false;
            }
            boolean isCurrent = node == currentNode(gui);
            if (isCurrent) {
                edt.run(gui::updateCurrentNode);
            }
            if (!mutator.updateProperty(gui.getTreeModel(), node, property, value)) {
                return false;
            }
            edt.run(() -> reloadPanel(gui, node, isCurrent));
            return true;
        };
    }

    private static JMeterTreeNode currentNode(GuiPackage gui) {
        return gui.getTreeListener() == null ? null : gui.getTreeListener().getCurrentNode();
    }

    /** Reloads the open editor panel for {@code node} from its element, without a stale write-back. */
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
            log.warn("Could not refresh GUI after property update", e);
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
