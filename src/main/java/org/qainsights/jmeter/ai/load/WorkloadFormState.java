package org.qainsights.jmeter.ai.load;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Pure form parsing and validation used by the dialog and its headless tests. */
public final class WorkloadFormState {
    private WorkloadFormState() {
    }

    public static Evaluation evaluate(WorkloadInputs.TargetType targetType, String target,
                                      String rateCap, String response, String think,
                                      String ramp, String rampUnit, String hold, String holdUnit,
                                      String agents, int samplerCount, String prefix, String planFile) {
        return evaluate(targetType, target, rateCap, response, think, ramp, rampUnit, hold, holdUnit,
                agents, samplerCount, prefix, planFile, Context.empty());
    }

    public static Evaluation evaluate(WorkloadInputs.TargetType targetType, String target,
                                      String rateCap, String response, String think,
                                      String ramp, String rampUnit, String hold, String holdUnit,
                                      String agents, int samplerCount, String prefix, String planFile,
                                      Context context) {
        return evaluate(targetType, target, rateCap, response, think, ramp, rampUnit,
                hold, holdUnit, agents, samplerCount, prefix, planFile, context,
                WorkloadShapeConfig.load());
    }

    public static Evaluation evaluate(WorkloadInputs.TargetType targetType, String target,
                                      String rateCap, String response, String think,
                                      String ramp, String rampUnit, String hold, String holdUnit,
                                      String agents, int samplerCount, String prefix, String planFile,
                                      Context context, WorkloadShapeConfig shapeConfig) {
        try {
            double targetValue = shapeConfig.shape().usesOpenModelThreadGroup()
                    ? 1 : requiredNumber(target, "Enter a positive target value.", WorkloadField.TARGET);
            Double cap = optionalNumber(rateCap, "Enter a positive samples-per-second cap.", WorkloadField.RATE_CAP);
            Long responseMs = optionalLong(response, "Average response time must be a whole number of milliseconds.",
                    WorkloadField.RESPONSE, WorkloadLimits.MAX_MILLIS, "Average response time", "ms (1 h)");
            Long thinkMs = optionalLong(think, "Think time must be a whole number of milliseconds.",
                    WorkloadField.THINK, WorkloadLimits.MAX_MILLIS, "Think time", "ms (1 h)");
            Long rampSeconds = optionalDuration(ramp, rampUnit, WorkloadField.RAMP, "Ramp-up");
            Long holdSeconds = optionalDuration(hold, holdUnit, WorkloadField.HOLD, "Hold");
            Integer agentCount = optionalInteger(agents, "Load generators must be a whole number.",
                    WorkloadField.AGENTS, WorkloadLimits.MAX_GENERATORS, "Load generators", "");
            Integer messagesPerSample = targetType == WorkloadInputs.TargetType.MESSAGE_RATE
                    ? optionalInteger(context.messagesPerSample(),
                            "Messages per sample must be a whole number.", WorkloadField.MESSAGES_PER_SAMPLE,
                            WorkloadLimits.MAX_MESSAGES_PER_SAMPLE, "Messages per sample", "")
                    : null;
            Integer maxThreads = optionalInteger(context.maxThreadsPerGenerator(),
                    "Measured max threads per generator must be a whole number.",
                    WorkloadField.MAX_THREADS_PER_GENERATOR, WorkloadLimits.MAX_THREADS_PER_GENERATOR,
                    "Max threads per generator", "threads");
            Integer generatorVcpus = optionalInteger(context.generatorVcpus(),
                    "Generator vCPUs must be a whole number.", WorkloadField.VCPUS,
                    WorkloadLimits.MAX_VCPUS, "Generator vCPUs", "");
            if (generatorVcpus != null && generatorVcpus < 1) {
                throw new InvalidWorkloadValueException(WorkloadField.VCPUS, "Generator vCPUs must be at least 1.");
            }
            Integer generatorRamGb = optionalInteger(context.generatorRamGb(),
                    "Generator RAM must be a whole number of GB.", WorkloadField.RAM,
                    WorkloadLimits.MAX_RAM_GB, "Generator RAM", "GB");
            Integer samplesPerVcpu = optionalInteger(context.samplesPerVcpu(),
                    "Samples per vCPU must be a whole number.", WorkloadField.SAMPLES_PER_VCPU,
                    WorkloadLimits.MAX_SAMPLES_PER_VCPU, "Samples per vCPU", "samples/s");
            List<String> hosts = context.generatorHosts() == null || context.generatorHosts().isBlank()
                    ? List.of() : Arrays.stream(context.generatorHosts().split(",", -1))
                    .map(String::trim).toList();
            WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(targetType, targetValue,
                    cap, responseMs, thinkMs, rampSeconds, holdSeconds, agentCount, samplerCount,
                    prefix, planFile, messagesPerSample == null ? 1.0 : messagesPerSample,
                    maxThreads, hosts, context.samplerClasses(), context.jdbcPools(),
                    generatorVcpus, generatorRamGb == null ? null : generatorRamGb.doubleValue(),
                    samplesPerVcpu == null ? null : samplesPerVcpu.doubleValue(),
                    context.scriptHeavy(), shapeConfig));
            return new Evaluation(plan, "", null);
        } catch (IllegalArgumentException | ArithmeticException e) {
            WorkloadField field = e instanceof InvalidWorkloadValueException invalid ? invalid.field() : null;
            return new Evaluation(null,
                    e.getMessage() == null ? "Check the workload values." : e.getMessage(), field);
        }
    }

    private static double requiredNumber(String value, String error, WorkloadField field) {
        if (value == null || value.isBlank()) {
            throw new InvalidWorkloadValueException(field, error);
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed)) {
                throw new InvalidWorkloadValueException(field, error);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new InvalidWorkloadValueException(
                    field, value.trim().equals(".") ? "Enter a number." : error);
        }
    }

    private static Double optionalNumber(String value, String error, WorkloadField field) {
        return value == null || value.isBlank() ? null : requiredNumber(value, error, field);
    }

    private static Long optionalLong(String value, String error, WorkloadField field,
                                     long limit, String label, String unit) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            BigInteger parsed = new BigDecimal(value.trim()).toBigIntegerExact();
            if (parsed.compareTo(BigInteger.valueOf(limit)) > 0) {
                throw new InvalidWorkloadValueException(field,
                        WorkloadLimits.maxMessage(label, limit, unit));
            }
            return parsed.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new InvalidWorkloadValueException(field, error);
        }
    }

    private static Integer optionalInteger(String value, String error, WorkloadField field,
                                           int limit, String label, String unit) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            BigInteger parsed = new BigDecimal(value.trim()).toBigIntegerExact();
            if (parsed.compareTo(BigInteger.valueOf(limit)) > 0) {
                throw new InvalidWorkloadValueException(field, WorkloadLimits.maxMessage(label, limit, unit));
            }
            return parsed.intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new InvalidWorkloadValueException(field, error);
        }
    }

    private static Long optionalDuration(String value, String unit, WorkloadField field, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(value.trim());
            if (amount.signum() < 0) {
                throw new InvalidWorkloadValueException(field, "Ramp-up and hold values cannot be negative.");
            }
            long multiplier = switch (unit) {
                case "min" -> 60L;
                case "h" -> 3600L;
                default -> 1L;
            };
            BigDecimal seconds = amount.multiply(BigDecimal.valueOf(multiplier));
            if (seconds.compareTo(BigDecimal.valueOf(WorkloadLimits.MAX_DURATION_SECONDS)) > 0) {
                throw new InvalidWorkloadValueException(field, WorkloadLimits.durationLimitMessage(label));
            }
            return seconds.setScale(0, java.math.RoundingMode.CEILING).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new InvalidWorkloadValueException(field, "Enter a valid duration.");
        }
    }

    public record Context(String generatorHosts, String maxThreadsPerGenerator,
                          String messagesPerSample, List<Class<?>> samplerClasses,
                          List<JdbcPool> jdbcPools, String generatorVcpus,
                          String generatorRamGb, String samplesPerVcpu, boolean scriptHeavy) {
        public Context {
            samplerClasses = samplerClasses == null ? List.of() : List.copyOf(samplerClasses);
            jdbcPools = jdbcPools == null ? List.of() : List.copyOf(jdbcPools);
        }

        public Context(String generatorHosts, String maxThreadsPerGenerator, String messagesPerSample,
                       List<Class<?>> samplerClasses, List<JdbcPool> jdbcPools) {
            this(generatorHosts, maxThreadsPerGenerator, messagesPerSample, samplerClasses, jdbcPools,
                    "", "", "", false);
        }

        public static Context empty() {
            return new Context("", "", "1", List.of(), List.of(), "", "", "", false);
        }
    }

    public record Evaluation(WorkloadPlan plan, String error, WorkloadField field) {
        public Evaluation(WorkloadPlan plan, String error) {
            this(plan, error, null);
        }

        public boolean valid() {
            return plan != null;
        }
    }
}
