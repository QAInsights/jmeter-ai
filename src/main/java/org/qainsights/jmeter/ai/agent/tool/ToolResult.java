package org.qainsights.jmeter.ai.agent.tool;

import java.util.Objects;

/**
 * Immutable, provider-neutral result of a single tool call.
 * <p>
 * A successful result carries a {@code data} payload (typically a short text or
 * JSON snippet the agent reads back). A failed result carries a machine-readable
 * {@code errorCode} plus a human-readable {@code message} that the agent uses for
 * self-correction.
 */
public final class ToolResult {

    private final boolean success;
    private final String data;
    private final String errorCode;
    private final String message;

    private ToolResult(boolean success, String data, String errorCode, String message) {
        this.success = success;
        this.data = data;
        this.errorCode = errorCode;
        this.message = message;
    }

    /** Creates a successful result with the given payload. */
    public static ToolResult ok(String data) {
        return new ToolResult(true, data == null ? "" : data, null, null);
    }

    /** Creates a failed result with a machine-readable code and a descriptive message. */
    public static ToolResult error(String errorCode, String message) {
        if (errorCode == null || errorCode.trim().isEmpty()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        return new ToolResult(false, null, errorCode, message == null ? "" : message);
    }

    public boolean isSuccess() {
        return success;
    }

    /** The success payload, or {@code null} for a failed result. */
    public String getData() {
        return data;
    }

    /** The error code, or {@code null} for a successful result. */
    public String getErrorCode() {
        return errorCode;
    }

    /** The error message, or {@code null} for a successful result. */
    public String getMessage() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ToolResult)) {
            return false;
        }
        ToolResult that = (ToolResult) o;
        return success == that.success
                && Objects.equals(data, that.data)
                && Objects.equals(errorCode, that.errorCode)
                && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, data, errorCode, message);
    }

    @Override
    public String toString() {
        return success
                ? "ToolResult{ok, data='" + data + "'}"
                : "ToolResult{error, code='" + errorCode + "', message='" + message + "'}";
    }
}
