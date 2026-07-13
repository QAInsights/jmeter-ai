package org.qainsights.jmeter.ai.agent.dev;

import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.handlers.UpdateElementPropertyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEV-ONLY orchestration for manually smoke-testing the
 * {@code update_element_property} tool from inside JMeter. The selected node is
 * the target element; the user is prompted for a property key and value. All UI
 * touch points are injected as seams so the flow is fully unit-testable without
 * a live GUI. Temporary scaffold, removed once the agent loop is wired up.
 */
public final class UpdateElementPropertyDevRunner {

    private static final Logger log = LoggerFactory.getLogger(UpdateElementPropertyDevRunner.class);

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

    private final UpdateElementPropertyHandler handler;
    private final ElementIdResolver resolver;

    public UpdateElementPropertyDevRunner(UpdateElementPropertyHandler handler, ElementIdResolver resolver) {
        this.handler = handler;
        this.resolver = resolver;
    }

    /**
     * Runs the dev flow: read the selection, prompt for property/value, invoke
     * {@code update_element_property}, and show the result.
     *
     * @return the message shown to the user, or {@code null} if the user cancelled
     */
    public String run(NodeSupplier nodes, Prompter prompter, Notifier notifier) {
        JMeterTreeNode node = nodes.selectedNode();
        if (node == null) {
            String message = "Select the element to edit in the test plan first, then retry.";
            notifier.show(message);
            return message;
        }

        String property = prompter.prompt("Property key (e.g. HTTPSampler.path)", "HTTPSampler.path");
        if (property == null || property.trim().isEmpty()) {
            return null;
        }
        String value = prompter.prompt("New value (blank clears it)", "");
        if (value == null) {
            return null;
        }

        String elementId = resolver.idOf(node);
        Map<String, Object> args = new HashMap<>();
        args.put("element_id", elementId);
        args.put("property", property.trim());
        args.put("value", value);

        ToolResult result = handler.tool().execute(args);
        String message = format(result, elementId);
        log.info("[dev update_element_property] id='{}' property='{}' -> {}", elementId, property.trim(), message);
        notifier.show(message);
        return message;
    }

    private static String format(ToolResult result, String elementId) {
        if (result.isSuccess()) {
            return "update_element_property OK\n" + result.getData();
        }
        return "update_element_property FAILED [" + result.getErrorCode() + "] on '" + elementId + "'\n"
                + result.getMessage();
    }
}
