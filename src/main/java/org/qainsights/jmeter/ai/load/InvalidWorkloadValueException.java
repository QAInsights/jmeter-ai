package org.qainsights.jmeter.ai.load;

public final class InvalidWorkloadValueException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final WorkloadField field;

    public InvalidWorkloadValueException(WorkloadField field, String message) {
        super(message);
        this.field = field;
    }

    public WorkloadField field() {
        return field;
    }
}
