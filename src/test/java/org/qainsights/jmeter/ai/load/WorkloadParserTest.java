package org.qainsights.jmeter.ai.load;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadParserTest {

    @Test
    void parsesRequestRateUnitsAndThousandSeparators() {
        assertEquals(200.0, WorkloadParser.parse("@load 12,000 requests per minute").requestsPerSecond(), 0.0001);
        assertEquals(10_000.0 / 60, WorkloadParser.parse("10k rpm").requestsPerSecond(), 0.0001);
        for (String unit : new String[]{"rps", "tps", "qps", "hits/s", "req/s", "requests/s",
                "requests per second", "queries per second", "transactions per second",
                "req/min", "transactions per minute", "hits per hour"}) {
            WorkloadSpec spec = WorkloadParser.parse("12 " + unit);
            assertTrue(spec.requestsPerSecond() != null, unit);
            assertTrue(spec.unrecognized().isEmpty(), unit + ": " + spec.unrecognized());
        }
        assertEquals(1.0, WorkloadParser.parse("3,600 transactions per hour").requestsPerSecond(), 0.0001);
    }

    @Test
    void normalizesSlashHourRateUnits() {
        for (String unit : new String[]{"requests/h", "req/h", "hits/h", "transactions/h"}) {
            assertEquals(100.0 / 3600, WorkloadParser.parse("100 " + unit).requestsPerSecond(), 0.0001, unit);
        }
    }

    @Test
    void parsesMessageRateAliasesSeparatelyFromSampleRate() {
        WorkloadSpec shortAlias = WorkloadParser.parse("@load 2000 msg/s");
        WorkloadSpec longAlias = WorkloadParser.parse("2k messages per second");

        assertEquals(2000, shortAlias.messagesPerSecond());
        assertNull(shortAlias.requestsPerSecond());
        assertEquals(2000, longAlias.messagesPerSecond());
    }

    @Test
    void parsesGeneratorVcpuAndRamSpecificationsWithLastValueWins() {
        for (String value : new String[]{"4 vcpu", "4 vcpus", "4 cpu", "4 cpus", "4 core", "4 cores"}) {
            assertEquals(4, WorkloadParser.parse(value).generatorVcpus(), value);
        }
        for (String value : new String[]{"8 gb", "8gb ram", "8 gb memory"}) {
            assertEquals(8.0, WorkloadParser.parse(value).generatorRamGb(), value);
        }
        WorkloadSpec repeated = WorkloadParser.parse("4 vcpus, 8 gb, 6 cores, 16gb ram");
        assertEquals(6, repeated.generatorVcpus());
        assertEquals(16.0, repeated.generatorRamGb());
        assertEquals(2, repeated.duplicates().size());
    }

    @Test
    void parsesIterationRatesAndDoesNotTreatUsersPerSecondAsConcurrentUsers() {
        for (String noun : new String[]{"iterations", "journeys", "sessions", "arrivals", "users"}) {
            WorkloadSpec spec = WorkloadParser.parse("10 " + noun + " per second");
            assertEquals(10.0, spec.iterationsPerSecond(), noun);
            assertNull(spec.users(), noun);
        }
        assertEquals(2.0, WorkloadParser.parse("120 iterations per minute").iterationsPerSecond(), 0.0001);
        assertEquals(3.0 / 3600, WorkloadParser.parse("3 sessions per hour").iterationsPerSecond(), 0.0001);
    }

    @Test
    void parsesConcurrentUsersAndKiloAndMegaSuffixes() {
        assertEquals(500, WorkloadParser.parse("@load 500 concurrent users").users());
        assertEquals(12_000, WorkloadParser.parse("12k virtual users").users());
        assertEquals(1_000_000, WorkloadParser.parse("1m threads").users());
        assertEquals(10, WorkloadParser.parse("10 users per second").iterationsPerSecond());
        assertNull(WorkloadParser.parse("10 users per second").users());
    }

    @Test
    void parsesRampUpDurationsInBothOrders() {
        assertEquals(600, WorkloadParser.parse("10-minute ramp-up").rampSeconds());
        assertEquals(2, WorkloadParser.parse("ramp up over 1.5 s").rampSeconds());
        assertEquals(20, WorkloadParser.parse("ramp-up of 20 secs").rampSeconds());
        assertEquals(60, WorkloadParser.parse("1 minute ramp up").rampSeconds());
    }

    @Test
    void parsesHoldAndSoakDurations() {
        assertEquals(3600, WorkloadParser.parse("for 1 hour").holdSeconds());
        assertEquals(120, WorkloadParser.parse("hold 2 min").holdSeconds());
        assertEquals(30, WorkloadParser.parse("holding 30 seconds").holdSeconds());
        assertEquals(45, WorkloadParser.parse("steady state for 45 seconds").holdSeconds());
        assertEquals(7200, WorkloadParser.parse("soak for 2 hours").holdSeconds());
        assertEquals(180, WorkloadParser.parse("duration 3 mins").holdSeconds());
        assertEquals(240, WorkloadParser.parse("4 minutes soak").holdSeconds());
    }

    @Test
    void parsesThinkAndPauseDurations() {
        assertEquals(3000, WorkloadParser.parse("think time 3 s").thinkMillis());
        assertEquals(250, WorkloadParser.parse("think 250ms").thinkMillis());
        assertEquals(1500, WorkloadParser.parse("1.5 seconds think time").thinkMillis());
        assertEquals(400, WorkloadParser.parse("pause 400 ms").thinkMillis());
    }

    @Test
    void parsesResponseTimeAndLatencyDurations() {
        assertEquals(400, WorkloadParser.parse("avg response time of 400 ms").responseMillis());
        assertEquals(1250, WorkloadParser.parse("average latency around 1.25s").responseMillis());
        assertEquals(200, WorkloadParser.parse("rt ~ 200 ms").responseMillis());
        assertEquals(350, WorkloadParser.parse("350ms response time").responseMillis());
    }

    @Test
    void parsesAgentCounts() {
        for (String phrase : new String[]{"across 4 agents", "on 4 generators", "over 4 engines",
                "using 4 machines", "across 4 injectors", "on 4 servers", "over 4 nodes"}) {
            assertEquals(4, WorkloadParser.parse(phrase).agents(), phrase);
        }
    }

    @Test
    void parsesCoreWorkloadShapePhrases() {
        WorkloadSpec step = WorkloadParser.parse("@load step 100 to 500 rps by 100 every 5 min ramp down 2 min");
        assertEquals(WorkloadShape.STEP, step.shapeConfig().shape());
        assertEquals(100.0, step.shapeConfig().startTarget());
        assertEquals(500.0, step.shapeConfig().endTarget());
        assertEquals(100.0, step.shapeConfig().stepTarget());
        assertEquals(300, step.shapeConfig().stepDurationSeconds());
        assertEquals(120, step.shapeConfig().rampDownSeconds());

        WorkloadSpec userStep = WorkloadParser.parse("step 50 to 250 users by 50 every 5 min");
        assertEquals(WorkloadShape.STEP, userStep.shapeConfig().shape());
        assertEquals(250, userStep.users());
        assertEquals(50.0, userStep.shapeConfig().startTarget());

        WorkloadSpec spike = WorkloadParser.parse("spike from 100 to 500 rps for 2 min");
        assertEquals(WorkloadShape.SPIKE, spike.shapeConfig().shape());
        assertEquals(100.0, spike.shapeConfig().baselineTarget());
        assertEquals(500.0, spike.shapeConfig().peakTarget());
        assertEquals(120, spike.shapeConfig().spikeSeconds());

        WorkloadSpec stress = WorkloadParser.parse("stress up to 1000 rps step 100 every 2 min");
        assertEquals(WorkloadShape.STRESS, stress.shapeConfig().shape());
        assertEquals(100.0, stress.shapeConfig().startTarget());
        assertEquals(1000.0, stress.shapeConfig().endTarget());
        assertEquals(120, stress.shapeConfig().stepDurationSeconds());

        WorkloadSpec endurance = WorkloadParser.parse("endurance 200 rps for 8 h");
        assertEquals(WorkloadShape.ENDURANCE, endurance.shapeConfig().shape());
        assertEquals(200.0, endurance.requestsPerSecond());
        assertEquals(28_800, endurance.holdSeconds());
        assertEquals(WorkloadShape.ENDURANCE, WorkloadParser.parse("soak 200 rps for 8 h")
                .shapeConfig().shape());
    }

    @Test
    void reportsRampDownAsUnsupportedForLoadAndEndurance() {
        String message = "Ramp-down needs an Open Model shape (Step, Spike, Stress or Custom).";

        WorkloadSpec load = WorkloadParser.parse("@load 200 rps ramp down 2 min");
        WorkloadSpec endurance = WorkloadParser.parse("@load endurance 200 rps for 8 h, ramp down 2 min");

        assertTrue(load.unsupported().contains(message));
        assertTrue(endurance.unsupported().contains(message));
    }

    @Test
    void unknownShapeWordsFallBackToLoad() {
        WorkloadSpec spec = WorkloadParser.parse("200 rps wave");

        assertEquals(WorkloadShape.LOAD, spec.shapeConfig().shape());
        assertEquals(200.0, spec.requestsPerSecond());
        assertTrue(spec.unsupported().isEmpty());
    }

    @Test
    void unrecognizedFragmentsAreRetainedAndDuplicateValuesUseTheLastOne() {
        WorkloadSpec spec = WorkloadParser.parse("50 users, 200 users, maybe fast");
        assertEquals(200, spec.users());
        assertEquals(1, spec.duplicates().size());
        assertTrue(spec.duplicates().get(0).contains("last value wins"));
        assertEquals(java.util.List.of("maybe fast"), spec.unrecognized());
    }

    @Test
    void emptyAndUnsupportedOnlyInputHaveNoRecognizedValues() {
        assertTrue(WorkloadParser.parse("@load").isEmpty());
        assertTrue(WorkloadParser.parse(null).isEmpty());
        assertTrue(WorkloadParser.parse("do a more realistic test").isEmpty());
    }
}
