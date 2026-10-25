package org.qainsights.jmeter.ai.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.ProviderConnectionTester;
import org.qainsights.jmeter.ai.service.ProviderSetupOption;

class TelemetryTest {
    private static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,40}");

    private TelemetryCounters counters;
    private TelemetryCounters previous;

    @BeforeEach
    void setUp() {
        counters = new TelemetryCounters();
        previous = Telemetry.swapCountersForTesting(counters);
    }

    @AfterEach
    void tearDown() {
        Telemetry.swapCountersForTesting(previous);
    }

    @Test
    void everyFixedFeatureKeyIsServerSafe() {
        for (TelemetryFeature feature : TelemetryFeature.values()) {
            assertTrue(KEY.matcher(feature.wireName()).matches(), feature.name() + ": " + feature.wireName());
        }
    }

    @Test
    void providerHelpersRecordOnlyNormalizedProviderKeys() {
        Telemetry.recordChatProvider("claude-code:sonnet");
        Telemetry.recordAgentProvider("opencode:openai/gpt-4o");

        assertEquals(Map.of("msg_claudecode", 1, "agentrun_opencode", 1), counters.snapshot());
    }

    @Test
    void setupKeysAreSafeForEveryOptionAndOutcome() {
        Set<String> expected = new HashSet<>();
        for (ProviderSetupOption option : ProviderSetupOption.values()) {
            String optionKey = option.name().toLowerCase(Locale.ROOT).replace("_", "");
            Telemetry.recordSetupSave(option.name());
            expected.add("setup_save_" + optionKey);
            for (ProviderConnectionTester.Outcome outcome : ProviderConnectionTester.Outcome.values()) {
                String outcomeKey = outcome.name().toLowerCase(Locale.ROOT);
                Telemetry.recordSetupTest(option.name(), outcome.name());
                String key = "setup_test_" + optionKey + "_" + outcomeKey;
                assertTrue(KEY.matcher(key).matches(), key);
                expected.add(key);
            }
        }

        assertEquals(expected, counters.snapshot().keySet());
        assertTrue(KEY.matcher("setup_test_appleondevice_network_error").matches());
    }

    @Test
    void setupHelpersRejectUnknownLabels() {
        Telemetry.recordSetupTest("custom-provider", "ok");
        Telemetry.recordSetupTest("openai", "some server message");
        Telemetry.recordSetupSave("../private/path");

        assertTrue(counters.snapshot().isEmpty());
    }

    @Test
    void decisionKeysUseOnlyKnownBackendAndErrorLabels() {
        Telemetry.recordDecisionResult("OpenRouter", "ok", null);
        Telemetry.recordDecisionResult("typesafe", "fail", "not_found");
        Telemetry.recordDecisionResult("openai", "slow", null);
        Telemetry.recordDecisionResult("custom backend", "ok", null);
        Telemetry.recordDecisionResult("openai", "fail", "private message");

        assertEquals(Map.of(
                "decide_ok_openrouter", 1,
                "decide_fail_typesafe_not_found", 1,
                "decide_slow_openai", 1,
                "decide_fail_openai_other", 1), counters.snapshot());
    }

    @Test
    void agentStepBucketsIncludeZeroAndBoundaryValues() {
        for (int value : new int[] {0, 1, 5, 6, 15, 16}) {
            Telemetry.recordAgentSteps(value);
        }

        assertEquals(Map.of(
                "agent_steps_1_5", 3,
                "agent_steps_6_15", 2,
                "agent_steps_16p", 1), counters.snapshot());
        assertFalse(counters.snapshot().isEmpty());
    }
}
