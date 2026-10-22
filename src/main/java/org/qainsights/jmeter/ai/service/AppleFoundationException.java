package org.qainsights.jmeter.ai.service;

public class AppleFoundationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    public AppleFoundationException(String message, String code) {
        super(message);
        this.code = code;
    }

    public AppleFoundationException(String message, String code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
