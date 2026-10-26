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
                "requests per second", "req/min", "transactions per minute", "hits per hour"}) {
            WorkloadSpec spec = WorkloadParser.parse("12 " + unit);
            assertTrue(spec.requestsPerSecond() != null, unit);
            assertTrue(spec.unrecognized().isEmpty(), unit + ": " + spec.unrecognized());
        }
        assertEquals(1.0, WorkloadParser.parse("3,600 transactions per hour").requestsPerSecond(), 0.0001);
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
    void unsupportedShapesAreListedAndNeverParsedAsTargets() {
        for (String shape : new String[]{"spike", "step", "stair", "ramp-down", "wave"}) {
            WorkloadSpec spec = WorkloadParser.parse("200 rps " + shape);
            assertNull(spec.requestsPerSecond(), shape);
            assertEquals(1, spec.unsupported().size(), shape);
        }
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
        assertTrue(WorkloadParser.parse("spike to 100 rps").isEmpty());
    }
}
