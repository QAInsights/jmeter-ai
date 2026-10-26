package org.qainsights.jmeter.ai.load;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadChartModelTest {

    @Test
    void rateSeriesReachesItsCapTwoThirdsThroughTheHeadroomRamp() {
        WorkloadPlan plan = plan(WorkloadInputs.TargetType.REQUEST_RATE, 200, null, 500, 0, 600, 4200, 150);

        assertEquals(400, WorkloadChartModel.ratePlateauSeconds(plan), 0.0001);
        assertTrue(WorkloadChartModel.at(plan, 399.99).requestsPerSecond() < 200);
        assertEquals(200, WorkloadChartModel.at(plan, 400).requestsPerSecond(), 0.0001);
        assertEquals(200, WorkloadChartModel.at(plan, 600).requestsPerSecond(), 0.0001);
    }

    @Test
    void uncappedUserRatePlateausAtTheEndOfItsThreadRamp() {
        WorkloadPlan plan = plan(WorkloadInputs.TargetType.USERS, 500, null, 400, 3000, 60, 660, 500);

        assertEquals(60, WorkloadChartModel.ratePlateauSeconds(plan), 0.0001);
        assertEquals(147.06, WorkloadChartModel.maximumRequestsPerSecond(plan), 0.01);
    }

    @Test
    void insufficientUsersPlateauBelowTheirRequestedRateCap() {
        WorkloadPlan plan = plan(WorkloadInputs.TargetType.USERS, 200, 200.0, 500, 0, 60, 660, 50);

        assertEquals(60, WorkloadChartModel.ratePlateauSeconds(plan), 0.0001);
        assertEquals(100, WorkloadChartModel.maximumRequestsPerSecond(plan), 0.0001);
        assertEquals(200, WorkloadChartModel.requestRateAxisMaximum(plan), 0.0001);
    }

    @Test
    void choosesIndependentNiceAxesWithFourRoundIntervals() {
        WorkloadPlan threads150 = plan(WorkloadInputs.TargetType.USERS, 150, null,
                500, 0, 60, 660, 150);
        WorkloadPlan threads169 = plan(WorkloadInputs.TargetType.USERS, 169, null,
                500, 0, 60, 660, 169);
        WorkloadPlan samples250 = plan(WorkloadInputs.TargetType.REQUEST_RATE, 250, null,
                500, 0, 60, 660, 150);

        double threadMaximum = WorkloadChartModel.threadAxisMaximum(threads150);
        assertEquals(200, threadMaximum);
        assertEquals(50, threadMaximum / 4);
        assertEquals(200, WorkloadChartModel.threadAxisMaximum(threads169));
        assertEquals(List.of("0", "50", "100", "150", "200"),
                WorkloadChartModel.axisTicks(threadMaximum).stream()
                        .map(WorkloadChartModel::formatAxisTick).toList());

        double sampleMaximum = WorkloadChartModel.sampleRateAxisMaximum(samples250);
        assertEquals(300, sampleMaximum);
        assertEquals(75, sampleMaximum / 4);
        assertEquals(List.of("0", "75", "150", "225", "300"),
                WorkloadChartModel.axisTicks(sampleMaximum).stream()
                        .map(WorkloadChartModel::formatAxisTick).toList());
    }

    @Test
    void choosesNiceTicksAndFormatsHoursConsistently() {
        assertEquals(900, WorkloadChartModel.tickStepSeconds(4200));
        assertEquals(List.of(0L, 900L, 1800L, 2700L, 3600L),
                WorkloadChartModel.timeTicks(4200));
        assertEquals(List.of(0L, 120L, 240L, 360L, 480L, 600L),
                WorkloadChartModel.timeTicks(660));
        assertEquals("0:15", WorkloadChartModel.formatTime(900, 4200));
        assertEquals("1:10", WorkloadChartModel.formatTime(4200, 4200));
        assertEquals("1:30", WorkloadChartModel.formatTime(90, 1800));
    }

    private static WorkloadPlan plan(WorkloadInputs.TargetType type, double target, Double cap,
                                    long response, long think, long ramp, long duration, int threads) {
        Double rps;
        if (type == WorkloadInputs.TargetType.USERS) {
            rps = cap;
        } else {
            rps = target;
        }
        return new WorkloadPlan(type, target, threads, 1, rps,
                type == WorkloadInputs.TargetType.USERS ? 147.06 : null,
                rps == null ? null : rps * 60, ramp, duration - ramp, duration,
                1, null, null, response, think, "load", List.of(), List.of(), "");
    }
}
