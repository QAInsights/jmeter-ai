package org.qainsights.jmeter.ai.correlation;

public class PluginException extends RuntimeException {
    public PluginException(String message) {
        super(message);
    }

    public PluginException(String message, Throwable cause) {
        super(message, cause);
    }
}
