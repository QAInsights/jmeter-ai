package org.qainsights.jmeter.ai.agent.dev;

import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.handlers.ToggleElementHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEV-ONLY orchestration for manually smoke-testing the {@code toggle_element}
 * tool from inside JMeter. The selected node is the target element; the user is
 * prompted for whether to enable or disable it. All UI touch points are injected
 * as seams so the flow is fully unit-testable without a live GUI.
 */
public final class ToggleElementDevRunner {

    private static final Logger log = LoggerFactory.getLogger(ToggleElementDevRunner.class);

    /** Supplies the currently selected tree node (or {@code null}). */
    @FunctionalInterface
    public interface NodeSupplier {
        JMeterTreeNode selectedNode();
    }

    /** Prompts for a value; returns {@code null} if the user cancels. */
    @FunctionalInterface
    public interface Prompter {
        String prompt(String label, String defaultValue);
    }

    /** Displays a result message to the user. */
    @FunctionalInterface
    public interface Notifier {
        void show(String message);
    }

    private final ToggleElementHandler handler;
    private final ElementIdResolver resolver;

    public ToggleElementDevRunner(ToggleElementHandler handler, ElementIdResolver resolver) {
        this.handler = handler;
        this.resolver = resolver;
    }

    /**
     * Runs the dev flow: read the selection, prompt for enable/disable, invoke
     * {@code toggle_element}, and show the result.
     *
     * @return the message shown to the user, or {@code null} if the user cancelled
     */
    public String run(NodeSupplier nodes, Prompter prompter, Notifier notifier) {
        JMeterTreeNode node = nodes.selectedNode();
        if (node == null) {
            String message = "Select the element to toggle in the test plan first, then retry.";
            notifier.show(message);
            return message;
        }

        String enabledStr = prompter.prompt("Enable or disable? (true/false)", "true");
        if (enabledStr == null || enabledStr.trim().isEmpty()) {
            return null;
        }

        String elementId = resolver.idOf(node);
        Map<String, Object> args = new HashMap<>();
        args.put("element_id", elementId);
        args.put("enabled", enabledStr.trim());

        ToolResult result = handler.tool().execute(args);
        String message = format(result, elementId);
        log.info("[dev toggle_element] id='{}' enabled='{}' -> {}", elementId, enabledStr.trim(), message);
        notifier.show(message);
        return message;
    }

    private static String format(ToolResult result, String elementId) {
        if (result.isSuccess()) {
            return "toggle_element OK\n" + result.getData();
        }
        return "toggle_element FAILED [" + result.getErrorCode() + "] on '" + elementId + "'\n"
                + result.getMessage();
    }
}
