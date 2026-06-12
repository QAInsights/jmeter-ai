package org.qainsights.jmeter.ai.headless;

/** Thrown when command-line arguments for the headless runner are invalid. */
public class HeadlessUsageException extends RuntimeException {
    public HeadlessUsageException(String message) {
        super(message);
    }
}
