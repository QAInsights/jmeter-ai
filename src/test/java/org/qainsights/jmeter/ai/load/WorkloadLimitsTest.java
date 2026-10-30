package org.qainsights.jmeter.ai.load;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkloadLimitsTest {
    @Test
    void totalDurationMessageIncludesMinutesWhenDaysHaveNoHours() {
        assertEquals("Total test length can be at most 7 days (this plan is 7 d 15 min).",
                WorkloadLimits.totalDurationLimitMessage(605_700));
        assertEquals("Total test length can be at most 7 days (this plan is 7 d).",
                WorkloadLimits.totalDurationLimitMessage(604_800));
        assertEquals("Total test length can be at most 7 days (this plan is 7 d 1 h).",
                WorkloadLimits.totalDurationLimitMessage(608_400));
    }
}
