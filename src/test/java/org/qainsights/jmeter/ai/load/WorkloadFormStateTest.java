package org.qainsights.jmeter.ai.load;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadFormStateTest {
    @Test
    void evaluatesValidFormAndRejectsMissingTargetWithoutSwing() {
        WorkloadFormState.Evaluation valid = WorkloadFormState.evaluate(
                WorkloadInputs.TargetType.REQUEST_RATE, "200", "", "", "", "", "s",
                "", "min", "1", 4, "load", "plan.jmx");
        assertTrue(valid.valid());
        assertEquals(150, valid.plan().threads());
        assertEquals(12_000.0, valid.plan().rpm());

        WorkloadFormState.Evaluation invalid = WorkloadFormState.evaluate(
                WorkloadInputs.TargetType.REQUEST_RATE, "", "", "", "", "", "s",
                "", "min", "1", 4, "load", "plan.jmx");
        assertFalse(invalid.valid());
        assertEquals("Enter a positive target value.", invalid.error());
    }
}
