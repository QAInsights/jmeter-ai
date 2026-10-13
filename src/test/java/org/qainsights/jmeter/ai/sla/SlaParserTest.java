package org.qainsights.jmeter.ai.sla;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlaParserTest {

    @Test
    void parsesPercentileLatencyAndNo5xx() {
        SlaParser.SlaSpec spec = SlaParser.parse("@sla p95 under 800 ms and no 5xx");

        assertEquals(800, spec.maxMillis());
        assertEquals("p95", spec.statistic());
        assertEquals(SlaParser.CodeRule.NO_5XX, spec.codeRule());
        assertTrue(spec.unrecognized().isEmpty());
        assertTrue(spec.unsupported().isEmpty());
    }

    @Test
    void convertsSecondsAndKeepsTheStrictestLimit() {
        SlaParser.SlaSpec spec = SlaParser.parse("@sla below 2 s, within 1.5 seconds");

        assertEquals(1500, spec.maxMillis());
        assertEquals("", spec.statistic());
    }

    @Test
    void recognizesCodeRuleVariants() {
        assertEquals(SlaParser.CodeRule.NO_4XX_5XX, SlaParser.parse("@sla no 4xx or 5xx").codeRule());
        assertEquals(SlaParser.CodeRule.NO_4XX_5XX, SlaParser.parse("@sla no errors").codeRule());
        assertEquals(SlaParser.CodeRule.NO_4XX, SlaParser.parse("@sla zero client errors").codeRule());
        assertEquals(SlaParser.CodeRule.NO_5XX, SlaParser.parse("@sla without server errors").codeRule());
        assertEquals(SlaParser.CodeRule.ONLY_2XX, SlaParser.parse("@sla only 2xx").codeRule());
        assertEquals(SlaParser.CodeRule.NO_4XX_5XX, SlaParser.parse("@sla no 4xx and no 5xx").codeRule());
    }

    @Test
    void parsesExactStatusCode() {
        SlaParser.SlaSpec spec = SlaParser.parse("@sla status 200 and max 300ms");

        assertEquals("200", spec.exactCode());
        assertNull(spec.codeRule());
        assertEquals(300, spec.maxMillis());
    }

    @Test
    void runLevelTargetsAreUnsupportedAndNoiseIsUnrecognized() {
        SlaParser.SlaSpec spec = SlaParser.parse("@sla error rate under 1% and throughput above 50 tps and be fast");

        assertTrue(spec.isEmpty());
        assertEquals(2, spec.unsupported().size());
        assertEquals("be fast", spec.unrecognized().get(0));
    }

    @Test
    void emptyInputIsEmpty() {
        assertTrue(SlaParser.parse("@sla").isEmpty());
        assertTrue(SlaParser.parse(null).isEmpty());
    }

    @Test
    void runLevelClauseWithATimeUnitIsNotTurnedIntoADurationAssertion() {
        SlaParser.SlaSpec spec = SlaParser.parse("@sla throughput under 2 s and p90 below 900 ms");

        assertEquals(900, spec.maxMillis());
        assertEquals("throughput under 2 s", spec.unsupported().get(0));
    }

    @Test
    void negated2xxIsNotReversedIntoOnly2xx() {
        SlaParser.SlaSpec spec = SlaParser.parse("@sla no 2xx");

        assertNull(spec.codeRule());
        assertTrue(spec.isEmpty());
        assertEquals("no 2xx", spec.unrecognized().get(0));
    }

    @Test
    void codeRuleWithoutSeparatorAfterDurationIsStillParsed() {
        for (String text : java.util.List.of("@sla under 800 ms no 5xx", "@sla under 800 ms with no 5xx")) {
            SlaParser.SlaSpec spec = SlaParser.parse(text);
            assertEquals(800, spec.maxMillis(), text);
            assertEquals(SlaParser.CodeRule.NO_5XX, spec.codeRule(), text);
            assertTrue(spec.unrecognized().isEmpty(), text);
        }
    }

    @Test
    void negatedLimitIsNotTurnedIntoAnUpperBound() {
        SlaParser.SlaSpec spec = SlaParser.parse("@sla not under 800 ms");
        assertEquals(0, spec.maxMillis());
        assertEquals(1, spec.unrecognized().size());
    }

    @Test
    void contradictoryCodeRulesAreReportedNotDropped() {
        SlaParser.SlaSpec conflict = SlaParser.parse("@sla status 500 and no 5xx");
        assertNull(conflict.codeRule());
        assertEquals("", conflict.exactCode());
        assertEquals(1, conflict.conflicts().size());

        SlaParser.SlaSpec compatible = SlaParser.parse("@sla status 200 and no 5xx");
        assertEquals("200", compatible.exactCode());
        assertNull(compatible.codeRule());
        assertTrue(compatible.conflicts().isEmpty());

        assertEquals(1, SlaParser.parse("@sla status 200 and status 201").conflicts().size());
    }

    @Test
    void noErrorRateIsRunLevelNotAPerSampleCodeRule() {
        SlaParser.SlaSpec spec = SlaParser.parse("@sla no error rate");
        assertNull(spec.codeRule());
        assertEquals(1, spec.unsupported().size());
    }

    @Test
    void codeClauseWithUnparsedPartsIsReported() {
        SlaParser.SlaSpec spec = SlaParser.parse("@sla only 2xx or 3xx");
        assertNull(spec.codeRule());
        assertEquals(java.util.List.of("only 2xx or 3xx"), spec.unrecognized());
    }

    @Test
    void fractionalLimitsRoundDown() {
        assertEquals(800, SlaParser.parse("@sla under 800.6 ms").maxMillis());
        assertEquals(1234, SlaParser.parse("@sla under 1.2349 s").maxMillis());
    }

    @Test
    void looserLimitsAreReportedAsSuperseded() {
        SlaParser.SlaSpec spec = SlaParser.parse("@sla p95 under 1.5 s and avg under 800 ms");
        assertEquals(800, spec.maxMillis());
        assertEquals("avg", spec.statistic());
        assertEquals(java.util.List.of("p95 under 1.5 s"), spec.superseded());
    }

    @Test
    void httpPrefixedExactCodeIsAccepted() {
        assertEquals("200", SlaParser.parse("@sla http 200").exactCode());
    }
}
