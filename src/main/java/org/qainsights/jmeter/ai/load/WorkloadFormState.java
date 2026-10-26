package org.qainsights.jmeter.ai.load;

/** Pure form parsing and validation used by the dialog and its headless tests. */
public final class WorkloadFormState {
    private WorkloadFormState() {
    }

    public static Evaluation evaluate(WorkloadInputs.TargetType targetType, String target,
                                      String rateCap, String response, String think,
                                      String ramp, String rampUnit, String hold, String holdUnit,
                                      String agents, int samplerCount, String prefix, String planFile) {
        try {
            double targetValue = requiredNumber(target, "Enter a positive target value.");
            Double cap = optionalNumber(rateCap);
            Long responseMs = optionalLong(response, "Average response time must be a whole number of milliseconds.");
            Long thinkMs = optionalLong(think, "Think time must be a whole number of milliseconds.");
            Long rampSeconds = optionalDuration(ramp, rampUnit);
            Long holdSeconds = optionalDuration(hold, holdUnit);
            Integer agentCount = optionalInteger(agents);
            WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(targetType, targetValue,
                    cap, responseMs, thinkMs, rampSeconds, holdSeconds, agentCount, samplerCount,
                    prefix, planFile));
            return new Evaluation(plan, "");
        } catch (IllegalArgumentException | ArithmeticException e) {
            return new Evaluation(null, e.getMessage() == null ? "Check the workload values." : e.getMessage());
        }
    }

    private static double requiredNumber(String value, String error) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(error);
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed) || parsed <= 0) {
                throw new IllegalArgumentException(error);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error);
        }
    }

    private static Double optionalNumber(String value) {
        return value == null || value.isBlank() ? null : requiredNumber(value, "Enter a positive requests-per-second cap.");
    }

    private static Long optionalLong(String value, String error) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error);
        }
    }

    private static Integer optionalInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Load generators must be a whole number.");
        }
    }

    private static Long optionalDuration(String value, String unit) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double amount = Double.parseDouble(value.trim());
            if (!Double.isFinite(amount) || amount < 0) {
                throw new IllegalArgumentException("Ramp-up and hold values cannot be negative.");
            }
            double multiplier = switch (unit) {
                case "min" -> 60;
                case "h" -> 3600;
                default -> 1;
            };
            return (long) Math.ceil(amount * multiplier);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter a valid duration.");
        }
    }

    public record Evaluation(WorkloadPlan plan, String error) {
        public boolean valid() {
            return plan != null;
        }
    }
}
