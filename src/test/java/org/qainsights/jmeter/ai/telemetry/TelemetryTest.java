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
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.ListedHashTree;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary;
import org.qainsights.jmeter.ai.fix.FixLoop;
import org.qainsights.jmeter.ai.fix.FixPrompt;
import org.qainsights.jmeter.ai.fix.FixRequest;
import org.qainsights.jmeter.ai.fix.PlanCensus;

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

    @Test
    void fixLoopCountsSoftFailuresAndAssertionInstallationOncePerRun() throws Exception {
        HTTPSampleResult soft = new HTTPSampleResult();
        soft.setSampleLabel("Request");
        soft.setSuccessful(true);
        soft.setResponseCode("200");
        soft.setContentType("text/html");
        soft.setResponseData("<title>Not Found</title>", "UTF-8");
        TestRunSummary softSummary = TestRunSummary.of(java.util.List.of(soft), false, 1);
        FixLoop softLoop = fixLoop(() -> softSummary, noAssertions());

        softLoop.run(new FixRequest(1, 120, false), null);

        assertEquals(1, counters.snapshot().get("fix_soft_failure"));

        counters = new TelemetryCounters();
        Telemetry.swapCountersForTesting(counters);
        java.util.ArrayDeque<TestRunSummary> summaries = new java.util.ArrayDeque<>(java.util.List.of(
                summary(2, 1), summary(2, 1), summary(2, 0), summary(2, 0)));
        FixLoop.AssertionInstaller installer = new FixLoop.AssertionInstaller() {
            @Override
            public java.util.List<String> addMissing(java.util.List<TestRunSummary.Observation> observations) {
                return java.util.List.of("Request 0");
            }

            @Override
            public void removeAdded(java.util.Collection<String> labels) {
            }
        };
        FixLoop assertionLoop = fixLoop(summaries::removeFirst, installer);

        assertionLoop.run(new FixRequest(1, 120, false), null);

        assertEquals(1, counters.snapshot().get("fix_assertions_added"));
    }

    private static FixLoop fixLoop(java.util.function.Supplier<TestRunSummary> summarySupplier,
                                   FixLoop.AssertionInstaller installer) {
        return new FixLoop(timeout -> summarySupplier.get(), new FixLoop.BackupStore() {
            @Override public java.io.File backup() { return null; }
            @Override public java.io.File roundBackup() { return null; }
            @Override public void restore(java.io.File file) { }
            @Override public void discard(java.io.File file) { }
        }, () -> {
            ListedHashTree tree = new ListedHashTree();
            tree.add(new TestPlan("Test Plan"));
            return PlanCensus.capture(tree);
        }, ignored -> null, () -> false, new FixPrompt(), installer);
    }

    private static FixLoop.AssertionInstaller noAssertions() {
        return new FixLoop.AssertionInstaller() {
            @Override
            public java.util.List<String> addMissing(java.util.List<TestRunSummary.Observation> observations) {
                return java.util.List.of();
            }

            @Override
            public void removeAdded(java.util.Collection<String> labels) {
            }
        };
    }

    private static TestRunSummary summary(int total, int failed) {
        java.util.ArrayList<SampleResult> results = new java.util.ArrayList<>();
        for (int index = 0; index < total; index++) {
            SampleResult sample = new SampleResult();
            sample.setSampleLabel("Request " + index);
            sample.setSuccessful(index >= failed);
            sample.setResponseCode(index < failed ? "500" : "200");
            results.add(sample);
        }
        return TestRunSummary.of(results, false, 1);
    }
}
