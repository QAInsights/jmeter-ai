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
}
