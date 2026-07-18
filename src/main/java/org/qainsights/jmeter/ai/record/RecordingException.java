package org.qainsights.jmeter.ai.record;

/**
 * Recording-specific unchecked exception.
 */
public final class RecordingException extends RuntimeException {
    public RecordingException(String message) {
        super(message);
    }

    public RecordingException(String message, Throwable cause) {
        super(message, cause);
    }
}
