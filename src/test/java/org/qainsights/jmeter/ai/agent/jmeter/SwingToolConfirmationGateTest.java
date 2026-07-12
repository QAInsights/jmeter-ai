package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link SwingToolConfirmationGate} using a direct EDT and a fake dialog. */
class SwingToolConfirmationGateTest {

    @Test
    void confirm_approved_returnsTrue() {
        SwingToolConfirmationGate gate = new SwingToolConfirmationGate(EdtExecutor.direct(), message -> true);
        assertTrue(gate.confirm("delete_element", new LinkedHashMap<>()));
    }

    @Test
    void confirm_declined_returnsFalse() {
        SwingToolConfirmationGate gate = new SwingToolConfirmationGate(EdtExecutor.direct(), message -> false);
        assertFalse(gate.confirm("delete_element", new LinkedHashMap<>()));
    }

    @Test
    void confirm_messageIncludesToolNameAndElementId() {
        String[] captured = {null};
        SwingToolConfirmationGate gate = new SwingToolConfirmationGate(EdtExecutor.direct(), message -> {
            captured[0] = message;
            return true;
        });
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("element_id", "Test Plan/Thread Group/HTTP Request");

        gate.confirm("delete_element", args);

        assertNotNull(captured[0]);
        assertTrue(captured[0].contains("delete_element"));
        assertTrue(captured[0].contains("Test Plan/Thread Group/HTTP Request"));
    }

    @Test
    void confirm_messageIncludesMoveDestinationAndForceNote() {
        String[] captured = {null};
        SwingToolConfirmationGate gate = new SwingToolConfirmationGate(EdtExecutor.direct(), message -> {
            captured[0] = message;
            return true;
        });
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("element_id", "Test Plan/Thread Group");
        args.put("new_parent_id", "Test Plan/Thread Group 2");
        args.put("force", "true");

        gate.confirm("move_element", args);

        assertTrue(captured[0].contains("Test Plan/Thread Group 2"));
        assertTrue(captured[0].contains("children"));
    }

    @Test
    void confirm_delegatesThroughTheEdtExecutor() {
        boolean[] usedExecutor = {false};
        EdtExecutor tracking = task -> {
            usedExecutor[0] = true;
            task.run();
        };
        SwingToolConfirmationGate gate = new SwingToolConfirmationGate(tracking, message -> true);

        gate.confirm("delete_element", new LinkedHashMap<>());

        assertTrue(usedExecutor[0]);
    }
}
