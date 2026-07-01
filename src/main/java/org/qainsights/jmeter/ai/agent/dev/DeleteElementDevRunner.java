package org.qainsights.jmeter.ai.agent.dev;

import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEV-ONLY orchestration for manually smoke-testing the {@code delete_element}
 * tool from inside JMeter. The selected node is the target; the user must confirm
 * before deletion (force=true is used so subtrees delete in one step). All UI
 * touch points are injected as seams for unit-testing without a live GUI.
 */
public final class DeleteElementDevRunner {

    private static final Logger log = LoggerFactory.getLogger(DeleteElementDevRunner.class);

    /** Supplies the currently selected tree node (or {@code null}). */
    @FunctionalInterface
    public interface NodeSupplier {
        JMeterTreeNode selectedNode();
    }

    /** Asks the user to confirm a destructive action; returns true to proceed. */
    @FunctionalInterface
    public interface Confirmer {
        boolean confirm(String message);
    }

    /** Displays a result message to the user. */
    @FunctionalInterface
    public interface Notifier {
        void show(String message);
    }

    private final DeleteElementHandler handler;
    private final ElementIdResolver resolver;

    public DeleteElementDevRunner(DeleteElementHandler handler, ElementIdResolver resolver) {
        this.handler = handler;
        this.resolver = resolver;
    }

    /**
     * Runs the dev flow: read the selection, confirm, invoke {@code delete_element}
     * with force=true, and show the result.
     *
     * @return the message shown, or {@code null} if there was no selection-less abort
     *         path; {@code null} when the user cancels confirmation
     */
    public String run(NodeSupplier nodes, Confirmer confirmer, Notifier notifier) {
        JMeterTreeNode node = nodes.selectedNode();
        if (node == null) {
            String message = "Select the element to delete in the test plan first, then retry.";
            notifier.show(message);
            return message;
        }

        String elementId = resolver.idOf(node);
        if (!confirmer.confirm("Delete '" + elementId + "' and any child elements?")) {
            return null;
        }

        Map<String, Object> args = new HashMap<>();
        args.put("element_id", elementId);
        args.put("force", Boolean.TRUE);

        ToolResult result = handler.tool().execute(args);
        String message = format(result, elementId);
        log.info("[dev delete_element] id='{}' -> {}", elementId, message);
        notifier.show(message);
        return message;
    }

    private static String format(ToolResult result, String elementId) {
        if (result.isSuccess()) {
            return "delete_element OK\n" + result.getData();
        }
        return "delete_element FAILED [" + result.getErrorCode() + "] on '" + elementId + "'\n"
                + result.getMessage();
    }
}
