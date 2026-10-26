package org.qainsights.jmeter.ai.load;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadCalculatorTest {

    @Test
    void calculatesRateTargetUsingHeadroomAndParsesRequestedDefaults() {
        WorkloadSpec spec = WorkloadParser.parse("@load 200 rps, 10 min ramp-up, 1 hour soak");
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, spec.requestsPerSecond(), null,
                spec.responseMillis(), spec.thinkMillis(), spec.rampSeconds(), spec.holdSeconds(),
                spec.agents(), 4, "load", "checkout.jmx"));

        assertEquals(150, plan.threads());
        assertEquals(12_000.0, plan.rpm());
        assertEquals(600, plan.rampSeconds());
        assertEquals(4200, plan.durationSeconds());
        assertEquals(1, plan.assumptions().size());
        assertTrue(plan.assumptions().get(0).contains("Assumed 500 ms average response time"));
        assertEquals("export HEAP=\"-Xms1g -Xmx1g\"\n"
                + "jmeter -n -t checkout.jmx -Jload.threads=150 -Jload.rampup=600 "
                + "-Jload.duration=4200 -Jload.rpm=12000 -l results.jtl -e -o report", plan.cliCommand());
    }

    @Test
    void calculatesConcurrentUsersAndExpectedRateWithoutThroughputTimer() {
        WorkloadSpec spec = WorkloadParser.parse("@load 500 users, think time 3 s, response time 400 ms");
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.USERS, spec.users(), null, spec.responseMillis(),
                spec.thinkMillis(), spec.rampSeconds(), spec.holdSeconds(), spec.agents(),
                5, "load", "plan.jmx"));

        assertEquals(500, plan.threads());
        assertEquals(147.06, plan.expectedRequestsPerSecond());
        assertNull(plan.rpm());
        assertEquals(600, plan.holdSeconds());
        assertEquals(60, plan.rampSeconds());
        assertEquals(2, plan.assumptions().size());
        assertFalse(plan.cliCommand().contains(".rpm="));
        assertTrue(plan.cliCommand().contains("-Jload.think_ms=3000"));
    }

    @Test
    void warnsWhenUserCountCannotReachTheRequestedRateCap() {
        WorkloadSpec spec = WorkloadParser.parse("@load 50 users, 200 rps");
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.USERS, spec.users(), spec.requestsPerSecond(),
                null, null, null, null, null, 1, "load", "plan.jmx"));

        assertEquals(100.0, plan.expectedRequestsPerSecond());
        assertEquals(12_000.0, plan.rpm());
        assertEquals(1, plan.warnings().size());
        assertTrue(plan.warnings().get(0).contains("at most 100 samples/s"));
        assertTrue(plan.warnings().get(0).contains("Use at least 150 users."));
    }

    @Test
    void roundsThreadsUpAndDistributesThePlanAcrossAgents() {
        WorkloadSpec spec = WorkloadParser.parse("@load 200 rps, 10 min ramp-up, 1 hour soak across 4 agents");
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, spec.requestsPerSecond(), null,
                spec.responseMillis(), spec.thinkMillis(), spec.rampSeconds(), spec.holdSeconds(),
                spec.agents(), 4, "load", "shop.jmx"));

        assertEquals(150, plan.threads());
        assertEquals(38, plan.perAgentThreads());
        assertEquals(3000.0, plan.perAgentRpm());
        assertTrue(plan.cliCommand().contains(" -R agent1,agent2,agent3,agent4 "));
        assertTrue(plan.cliCommand().contains("-Gload.threads=38"));
        assertTrue(plan.cliCommand().contains("-Gload.rpm=3000"));
    }

    @Test
    void convertsIterationRateToRequestRateUsingSamplerCount() {
        WorkloadSpec spec = WorkloadParser.parse("@load 10 journeys per second");
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.ITERATION_RATE, spec.iterationsPerSecond(), null,
                null, null, null, null, null, 6, "load", "plan.jmx"));

        assertEquals(45, plan.threads());
        assertEquals(60.0, plan.targetRequestsPerSecond());
        assertEquals(3600.0, plan.rpm());
        assertTrue(plan.notes().contains("Samples per iteration counts each enabled sampler once; "
                + "If, Loop, Throughput and Transaction (parent sample) controllers change the real number."));
    }

    @Test
    void emitsWarningsForVeryLargeAndSteepWorkloads() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 2000, null, 500L,
                null, 10L, 600L, null, 1, "load", "plan.jmx"));

        assertTrue(plan.warnings().stream().anyMatch(value ->
                value.equals("1500 threads need at least 2 generators: each generator supports 1000 threads (default capacity).")));
        assertTrue(plan.warnings().stream().anyMatch(value -> value.contains("Steep ramp-up")));
    }

    @Test
    void convertsMessageRateToSamplesPerSecondAndSizesTheGeneratorHeap() {
        WorkloadInputs inputs = new WorkloadInputs(
                WorkloadInputs.TargetType.MESSAGE_RATE, 2000, null, null, null, null, null,
                null, 4, "load", "plan.jmx", 10.0, 1000, List.of(),
                List.of(org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy.class,
                        KafkaProducerSampler.class), List.of());
        WorkloadPlan calculated = WorkloadCalculator.calculate(inputs);

        assertEquals(150, calculated.threads());
        assertEquals(200.0, calculated.targetRequestsPerSecond());
        assertEquals(12_000.0, calculated.rpm());
        assertEquals(1024, calculated.heapPerGeneratorMb());
        assertTrue(calculated.notes().stream().anyMatch(note -> note.contains("Kafka samples may carry multiple messages")));
    }

    @Test
    void usesProvidedHostsAndQuotesUnsafePlanNamesInTheRemoteCommand() {
        WorkloadInputs inputs = new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 200, null, 500L, null, 600L, 3600L,
                null, 1, "load", "Checkout's plan.jmx", 1.0, 1000,
                List.of("load-a", "10.0.0.2", "host.example:1099", "node_b"),
                List.of(), List.of());
        WorkloadPlan plan = WorkloadCalculator.calculate(inputs);

        assertEquals(4, plan.agents());
        assertEquals(38, plan.perAgentThreads());
        assertTrue(plan.cliCommand().contains("-t 'Checkout'\\''s plan.jmx' -R load-a,10.0.0.2,host.example:1099,node_b"));
        assertTrue(plan.cliCommand().contains("-Gload.threads=38"));
        assertTrue(plan.notes().contains("-G properties go to all generators; values are per generator."));
    }

    @Test
    void warnsWhenTheSelectedGeneratorsCannotFitTheThreadCount() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 2000, null, 500L,
                null, 60L, 600L, 1, 1, "load", "plan.jmx",
                1.0, 500, List.of(), List.of(), List.of()));

        assertEquals(3, plan.generatorsNeeded());
        assertTrue(plan.warnings().contains(
                "1500 threads need at least 3 generators: each generator supports 500 threads (measured capacity)."));
    }

    @Test
    void derivesTheMemoryThreadCapFromRam() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 200, null, 500L, null,
                60L, 600L, 1, 1, "load", "plan.jmx", 1.0, null,
                List.of(), List.of(), List.of(), null, 8.0, null, false));

        assertEquals(2816, plan.maxThreadsPerGenerator());
        assertEquals("memory", plan.threadCapacityBasis());
        assertEquals("2816 threads (memory)", plan.capacityPerGenerator());
    }

    @Test
    void appliesCpuCapacityAndHalvesTheDefaultForScriptHeavyGroups() {
        WorkloadInputs regular = new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 2500, null, 500L, null,
                60L, 600L, 1, 1, "load", "plan.jmx", 1.0, null,
                List.of(), List.of(), List.of(), 4, null, null, false);
        WorkloadPlan regularPlan = WorkloadCalculator.calculate(regular);
        WorkloadPlan scriptPlan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 2500, null, 500L, null,
                60L, 600L, 1, 1, "load", "plan.jmx", 1.0, null,
                List.of(), List.of(), List.of(), 4, null, null, true));

        assertEquals(1000.0, regularPlan.cpuSamplesPerSecond());
        assertEquals(500.0, scriptPlan.cpuSamplesPerSecond());
        assertEquals(3, regularPlan.generatorsNeeded());
        assertEquals(5, scriptPlan.generatorsNeeded());
        assertTrue(regularPlan.warnings().stream().anyMatch(value ->
                value.contains("each 4 vCPU generator handles about 1000 samples/s (CPU estimate)")));
    }

    @Test
    void measuredCapacityOverridesMemoryAndCpuLimits() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 200, null, 500L, null,
                60L, 600L, 1, 1, "load", "plan.jmx", 1.0, 800,
                List.of(), List.of(), List.of(), 4, 8.0, null, false));

        assertEquals(800, plan.maxThreadsPerGenerator());
        assertEquals("measured", plan.threadCapacityBasis());
        assertNull(plan.cpuSamplesPerSecond());
        assertEquals("800 threads (measured)", plan.capacityPerGenerator());
    }

    @Test
    void warnsWhenPerGeneratorHeapExceedsSpecifiedRamBudget() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 10000, null, 500L, null,
                60L, 600L, 1, 1, "load", "plan.jmx", 1.0, null,
                List.of(), List.of(), List.of(), null, 2.0, null, false));

        assertTrue(plan.warnings().stream().anyMatch(value ->
                value.startsWith("Each generator needs about ") && value.contains("75% of 2 GB RAM is")));
    }

    @Test
    void defaultsToOneThousandThreadsWithoutGeneratorSpecifications() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 200, null, 500L, null,
                60L, 600L, null, 1, "load", "plan.jmx"));

        assertEquals(1000, plan.maxThreadsPerGenerator());
        assertEquals("default", plan.threadCapacityBasis());
    }

    @Test
    void formatsTwoGigabytesAsGigabytesInTheHeapEnvironment() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 1024, null, 500L,
                null, 60L, 600L, 1, 1, "load", "plan.jmx"));

        assertEquals(2048, plan.heapPerGeneratorMb());
        assertTrue(plan.cliCommand().startsWith("export HEAP=\"-Xms2g -Xmx2g\"\n"));
    }

    @Test
    void warnsWhenJdbcPoolCapacityIsBelowThreadsPerGenerator() {
        WorkloadInputs inputs = new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 300, null, 500L, null, 60L, 600L,
                null, 1, "load", "plan.jmx", 1.0, 1000, List.of(),
                List.of(org.apache.jmeter.protocol.jdbc.sampler.FakeJDBCSampler.class),
                List.of(new JdbcPool("payments", 10)));
        WorkloadPlan plan = WorkloadCalculator.calculate(inputs);

        assertTrue(plan.warnings().contains("JDBC pool \"payments\" allows 10 connections but 225 threads share it; "
                + "threads will queue and the target may not be reached. Set Max Number of Connections to 0 "
                + "(one connection per thread) or at least 225."));
        assertFalse(plan.notes().stream().anyMatch(note -> note.startsWith("JDBC pool \"")));
    }

    private static final class KafkaProducerSampler {
    }

    @Test
    void steepRampWarningUsesThePerAgentThreadCount() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 2000, null, 500L,
                null, 8L, 600L, 4, 1, "load", "plan.jmx"));

        assertEquals(1500, plan.threads());
        assertEquals(375, plan.perAgentThreads());
        assertFalse(plan.warnings().stream().anyMatch(value -> value.contains("Steep ramp-up")));
    }

    @Test
    void formatsWholeAndFractionalCliValuesWithoutUnnecessaryZeros() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 1.2345, null, 500L,
                null, 60L, 600L, null, 1, "load", null));

        assertTrue(plan.cliCommand().contains("-t plan.jmx"));
        assertTrue(plan.cliCommand().contains("-Jload.rpm=74.07"));
        assertFalse(plan.cliCommand().contains("74.070"));
    }

    @Test
    void generatesCommandsForEachShellWithOneGenerator() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 10, null, 500L, null,
                60L, 600L, 1, 1, "load", "plan.jmx"));

        assertEquals(plan.cliCommand(), plan.command());
        assertTrue(plan.command(CommandShell.BASH).startsWith("export HEAP=\"-Xms1g -Xmx1g\"\n"));
        assertTrue(plan.command(CommandShell.CMD).startsWith(
                "set HEAP=-Xms1g -Xmx1g\njmeter.bat -n -t plan.jmx "));
        assertTrue(plan.command(CommandShell.CMD).contains("\"-Jload.threads=8\""));
        assertTrue(plan.command(CommandShell.CMD).contains("\"-Jload.rampup=60\""));
        assertTrue(plan.command(CommandShell.CMD).contains("\"-Jload.duration=660\""));
        assertTrue(plan.command(CommandShell.POWERSHELL).startsWith(
                "$env:HEAP = \"-Xms1g -Xmx1g\"\njmeter.bat -n -t 'plan.jmx' "));
        assertTrue(plan.command(CommandShell.POWERSHELL).contains("\"-Jload.threads=8\""));
        assertTrue(plan.command(CommandShell.POWERSHELL).contains("\"-Jload.rampup=60\""));
        assertTrue(plan.command(CommandShell.POWERSHELL).contains("\"-Jload.duration=660\""));
    }

    @Test
    void generatesDistributedCommandsForEachShell() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 10, null, 500L, null,
                60L, 600L, 1, 1, "load", "plan.jmx", 1.0, 1000,
                List.of("load-a", "load-b"), List.of(), List.of()));

        assertTrue(plan.command(CommandShell.BASH).contains("-R load-a,load-b"));
        assertTrue(plan.command(CommandShell.BASH).contains("-Gload.threads=4"));
        for (CommandShell shell : List.of(CommandShell.CMD, CommandShell.POWERSHELL)) {
            assertTrue(plan.command(shell).contains("-R \"load-a,load-b\""));
            assertTrue(plan.command(shell).contains("\"-Gload.threads=4\""));
            assertFalse(plan.command(shell).contains("-Jload.threads="));
        }
        assertTrue(plan.command(CommandShell.CMD).contains("jmeter.bat -n -t plan.jmx"));
        assertTrue(plan.command(CommandShell.POWERSHELL).contains("jmeter.bat -n -t 'plan.jmx'"));
    }

    @Test
    void quotesPathsWithSpacesInEveryShellAndDoublesPowerShellApostrophes() {
        WorkloadPlan spaced = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 10, null, 500L, null,
                60L, 600L, 1, 1, "load", "My Plan.jmx"));

        assertTrue(spaced.command(CommandShell.BASH).contains("-t 'My Plan.jmx'"));
        assertTrue(spaced.command(CommandShell.CMD).contains("-t \"My Plan.jmx\""));
        assertTrue(spaced.command(CommandShell.POWERSHELL).contains("-t 'My Plan.jmx'"));

        WorkloadPlan apostrophe = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 10, null, 500L, null,
                60L, 600L, 1, 1, "load", "O'Brien plan.jmx"));
        assertTrue(apostrophe.command(CommandShell.POWERSHELL).contains("-t 'O''Brien plan.jmx'"));
    }

    @Test
    void fallsBackForPlanNamesThatCannotBeSafelyQuotedInCmd() {
        for (String unsafe : List.of("plan\".jmx", "plan%.jmx", "plan^.jmx", "plan&.jmx",
                "plan|.jmx", "plan<.jmx", "plan>.jmx")) {
            WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                    WorkloadInputs.TargetType.REQUEST_RATE, 10, null, 500L, null,
                    60L, 600L, 1, 1, "load", unsafe));

            assertTrue(plan.command(CommandShell.CMD).contains("-t plan.jmx"));
            assertTrue(plan.notes().stream().anyMatch(note -> note.contains("Windows cmd command uses plan.jmx")));
        }
    }

    @Test
    void selectsDefaultShellFromOperatingSystemName() {
        assertEquals(CommandShell.CMD, CommandShell.forOsName("Windows 11"));
        assertEquals(CommandShell.BASH, CommandShell.forOsName("Mac OS X"));
        assertEquals(CommandShell.BASH, CommandShell.forOsName("Linux"));
        assertEquals(CommandShell.BASH, CommandShell.forOsName(null));
    }

    @Test
    void rejectsInvalidOrUnrepresentableInputs() {
        assertThrows(IllegalArgumentException.class, () -> WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 0, null, null, null, null, null, null,
                1, "load", "plan.jmx")));
        assertThrows(IllegalArgumentException.class, () -> WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.USERS, 1, null, null, null, null, null, null,
                0, "load", "plan.jmx")));
    }

    @Test
    void rejectsUnsafePropertyPrefixes() {
        for (String prefix : List.of("my load", "a,b", "bad)prefix")) {
            assertThrows(IllegalArgumentException.class, () -> WorkloadCalculator.calculate(new WorkloadInputs(
                    WorkloadInputs.TargetType.REQUEST_RATE, 200, null, null, null, null, null,
                    null, 1, prefix, "plan.jmx")));
        }
    }
}
