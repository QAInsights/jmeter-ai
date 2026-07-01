package org.qainsights.jmeter.ai.agent.dev;

import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.handlers.AddElementHandler;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEV-ONLY orchestration for manually smoke-testing the {@code add_element} tool
 * from inside JMeter. All UI touch points (current selection, prompts, message
 * display) are injected as seams so the flow is fully unit-testable without a
 * live GUI. This class is a temporary scaffold and is expected to be removed once
 * the real agent loop is wired up.
 */
public final class AddElementDevRunner {

    private static final Logger log = LoggerFactory.getLogger(AddElementDevRunner.class);

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

    private final AddElementHandler handler;
    private final ElementIdResolver resolver;

    public AddElementDevRunner(AddElementHandler handler, ElementIdResolver resolver) {
        this.handler = handler;
        this.resolver = resolver;
    }

    /**
     * Runs the dev flow: read the selection, prompt for type/name, invoke
     * {@code add_element}, and show the result.
     *
     * @return the message shown to the user, or {@code null} if the user cancelled
     */
    public String run(NodeSupplier nodes, Prompter prompter, Notifier notifier) {
        JMeterTreeNode node = nodes.selectedNode();
        if (node == null) {
            return notify(notifier, "Select a node in the test plan first, then retry.");
        }

        String type = prompter.prompt("Element type (e.g. HTTPSamplerProxy)", "HTTPSamplerProxy");
        if (type == null || type.trim().isEmpty()) {
            return null;
        }
        String name = prompter.prompt("Name (optional, blank for default)", "");

        String parentId = resolver.idOf(node);
        Map<String, Object> args = new HashMap<>();
        args.put("element_type", type.trim());
        args.put("parent_id", parentId);
        if (name != null && !name.trim().isEmpty()) {
            args.put("name", name.trim());
        }

        ToolResult result = handler.tool().execute(args);
        String message = format(result, parentId);
        log.info("[dev add_element] parent='{}' type='{}' -> {}", parentId, type.trim(), message);
        notifier.show(message);
        return message;
    }

    private static String format(ToolResult result, String parentId) {
        if (result.isSuccess()) {
            return "add_element OK\n" + result.getData();
        }
        return "add_element FAILED [" + result.getErrorCode() + "] under '" + parentId + "'\n"
                + result.getMessage();
    }

    private static String notify(Notifier notifier, String message) {
        notifier.show(message);
        return message;
    }
}
