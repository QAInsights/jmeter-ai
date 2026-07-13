package org.qainsights.jmeter.ai.agent.dev;

import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.handlers.MoveElementHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEV-ONLY orchestration for manually smoke-testing the {@code move_element}
 * tool from inside JMeter. The selected node is the element to move; the user is
 * prompted for the destination parent's tree-path id. All UI touch points are
 * injected as seams so the flow is fully unit-testable without a live GUI.
 */
public final class MoveElementDevRunner {

    private static final Logger log = LoggerFactory.getLogger(MoveElementDevRunner.class);

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

    private final MoveElementHandler handler;
    private final ElementIdResolver resolver;

    public MoveElementDevRunner(MoveElementHandler handler, ElementIdResolver resolver) {
        this.handler = handler;
        this.resolver = resolver;
    }

    /**
     * Runs the dev flow: read the selection, prompt for the destination parent id,
     * invoke {@code move_element}, and show the result.
     *
     * @return the message shown to the user, or {@code null} if the user cancelled
     */
    public String run(NodeSupplier nodes, Prompter prompter, Notifier notifier) {
        JMeterTreeNode node = nodes.selectedNode();
        if (node == null) {
            String message = "Select the element to move in the test plan first, then retry.";
            notifier.show(message);
            return message;
        }

        String elementId = resolver.idOf(node);
        String newParentId = prompter.prompt(
                "Destination parent id (tree-path, e.g. 'Test Plan/Thread Group')", "");
        if (newParentId == null || newParentId.trim().isEmpty()) {
            return null;
        }

        Map<String, Object> args = new HashMap<>();
        args.put("element_id", elementId);
        args.put("new_parent_id", newParentId.trim());

        ToolResult result = handler.tool().execute(args);
        String message = format(result, elementId);
        log.info("[dev move_element] id='{}' newParent='{}' -> {}", elementId, newParentId.trim(), message);
        notifier.show(message);
        return message;
    }

    private static String format(ToolResult result, String elementId) {
        if (result.isSuccess()) {
            return "move_element OK\n" + result.getData();
        }
        return "move_element FAILED [" + result.getErrorCode() + "] on '" + elementId + "'\n"
                + result.getMessage();
    }
}
