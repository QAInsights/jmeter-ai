package org.qainsights.jmeter.ai.cli;

/**
 * Carries a message that is safe and useful to show in the chat UI: no stack
 * trace, no credentials. Thrown by the CLI-backed providers for the failure
 * modes the user can act on (CLI missing, not signed in, timeout, unsupported
 * CLI version, permission denied).
 */
public class CliProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CliProviderException(String message) {
        super(message);
    }

    public CliProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
