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

    @Test
    void derivesAgentCountFromValidatedHostsAndConvertsMessageRate() {
        WorkloadFormState.Context context = new WorkloadFormState.Context(
                "load-a,load-b", "1000", "10", java.util.List.of(), java.util.List.of());
        WorkloadFormState.Evaluation evaluation = WorkloadFormState.evaluate(
                WorkloadInputs.TargetType.MESSAGE_RATE, "2000", "", "", "", "", "s",
                "", "min", "1", 4, "load", "plan.jmx", context);

        assertTrue(evaluation.valid());
        assertEquals(2, evaluation.plan().agents());
        assertEquals(200.0, evaluation.plan().targetRequestsPerSecond());
        assertTrue(evaluation.plan().cliCommand().contains("-R load-a,load-b"));
    }

    @Test
    void rejectsInvalidGeneratorHostNames() {
        WorkloadFormState.Context context = new WorkloadFormState.Context(
                "good-host,bad host", "1000", "1", java.util.List.of(), java.util.List.of());

        WorkloadFormState.Evaluation evaluation = WorkloadFormState.evaluate(
                WorkloadInputs.TargetType.REQUEST_RATE, "200", "", "", "", "", "s",
                "", "min", "1", 4, "load", "plan.jmx", context);

        assertFalse(evaluation.valid());
        assertEquals("Enter generator hosts using letters, numbers, dots, underscores, colons, or hyphens.",
                evaluation.error());
    }

    @Test
    void ignoresHiddenMessagesPerSampleValueForNonMessageTargets() {
        WorkloadFormState.Context context = new WorkloadFormState.Context(
                "", "1000", "not-a-number", java.util.List.of(), java.util.List.of());

        WorkloadFormState.Evaluation evaluation = WorkloadFormState.evaluate(
                WorkloadInputs.TargetType.REQUEST_RATE, "200", "", "", "", "", "s",
                "", "min", "1", 1, "load", "plan.jmx", context);

        assertTrue(evaluation.valid());
    }
}
