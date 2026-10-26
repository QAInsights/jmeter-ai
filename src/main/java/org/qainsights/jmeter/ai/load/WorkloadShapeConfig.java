package org.qainsights.jmeter.ai.load;

import java.util.List;

/** Shape-specific values. User-entered rates use the selected target type. */
public record WorkloadShapeConfig(
        WorkloadShape shape,
        Double startTarget,
        Double endTarget,
        Double stepTarget,
        Long stepDurationSeconds,
        Long rampSeconds,
        Double baselineTarget,
        Double peakTarget,
        Long warmupSeconds,
        Long spikeSeconds,
        Long recoverySeconds,
        List<WorkloadStage> customStages,
        Long rampDownSeconds,
        ArrivalMode arrivalMode) {

    public WorkloadShapeConfig {
        shape = shape == null ? WorkloadShape.LOAD : shape;
        customStages = customStages == null ? List.of() : List.copyOf(customStages);
        arrivalMode = arrivalMode == null ? ArrivalMode.RANDOM : arrivalMode;
    }

    public static WorkloadShapeConfig load() {
        return new WorkloadShapeConfig(WorkloadShape.LOAD, null, null, null, null, null,
                null, null, null, null, null, List.of(), null, ArrivalMode.RANDOM);
    }
}
