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

    @Test
    void reportsInvalidGeneratorVcpuValuesWithFieldSpecificMessage() {
        for (String value : java.util.List.of("abc", "2.5")) {
            WorkloadFormState.Context context = new WorkloadFormState.Context(
                    "", "", "", java.util.List.of(), java.util.List.of(),
                    value, "", "", false);

            WorkloadFormState.Evaluation evaluation = WorkloadFormState.evaluate(
                    WorkloadInputs.TargetType.REQUEST_RATE, "200", "", "", "", "", "s",
                    "", "min", "1", 4, "load", "plan.jmx", context);

            assertFalse(evaluation.valid());
            assertEquals("Generator vCPUs must be a whole number.", evaluation.error());
        }
    }

    @Test
    void tagsTargetAndGeneratorBoundsInsteadOfTreatingLongIntegersAsMalformed() {
        WorkloadFormState.Evaluation target = evaluate("9999999999", "", "", "", "", "1");
        assertFalse(target.valid());
        assertEquals(WorkloadField.TARGET, target.field());
        assertTrue(target.error().contains("1,000,000"));

        WorkloadFormState.Evaluation agents = evaluate("100", "99999999999", "", "", "", "1");
        assertFalse(agents.valid());
        assertEquals(WorkloadField.AGENTS, agents.field());
        assertTrue(agents.error().contains("at most 1,000"));
        assertFalse(agents.error().contains("whole number"));
    }

    @Test
    void tagsDurationAndResponseTimeBounds() {
        WorkloadFormState.Evaluation hold = evaluate("100", "1", "", "", "192", "1");
        assertEquals(WorkloadField.HOLD, hold.field());
        assertTrue(hold.error().contains("7 days"));

        WorkloadFormState.Evaluation response = evaluate("100", "1", "3600001", "", "", "1");
        assertEquals(WorkloadField.RESPONSE, response.field());
        assertTrue(response.error().contains("3,600,000"));
    }

    @Test
    void tagsConcurrentUserLimit() {
        WorkloadFormState.Evaluation users = WorkloadFormState.evaluate(
                WorkloadInputs.TargetType.USERS, "1000001", "100", "", "", "", "s",
                "", "h", "1", 1, "load", "plan.jmx");
        assertEquals(WorkloadField.TARGET, users.field());
        assertTrue(users.error().contains("1,000,000"));
    }

    private static WorkloadFormState.Evaluation evaluate(String target, String agents,
                                                          String response, String ramp,
                                                          String hold, String cap) {
        return WorkloadFormState.evaluate(WorkloadInputs.TargetType.ITERATION_RATE,
                target, cap, response, "", ramp, "s", hold, "h", agents, 1,
                "load", "plan.jmx");
    }
}
