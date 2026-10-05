package org.qainsights.jmeter.ai.utils;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Turns provider failures (OpenAI-compatible, Anthropic and Google GenAI SDKs, plain
 * java.net errors) into a short message that says what went wrong and what to change,
 * instead of a bare "Sorry, I encountered an error".
 */
public final class ProviderErrors {

    static final String LOG_HINT = "Full details are in jmeter.log.";
    private static final int MAX_DETAIL = 300;

    private ProviderErrors() {
    }

    /** User-facing text for a failed chat request. */
    public static String describe(Throwable error) {
        return describe(error, "processing your request", false);
    }

    /**
     * User-facing text for a failure. {@code action} completes "Sorry, I encountered an
     * error while ..." for failures that are not recognised.
     */
    public static String describe(Throwable error, String action, boolean agentMode) {
        if (RateLimitErrors.isRateLimited(error)) {
            return "Error: " + RateLimitErrors.describe(error, agentMode);
        }
        int status = statusCode(error);
        String detail = detail(error);
        String text;
        if (status == 401) {
            text = "Authentication failed (HTTP 401): the provider rejected the API key. "
                    + "Check the API key for the selected provider in user.properties or "
                    + "jmeter.properties and restart JMeter.";
        } else if (status == 403) {
            text = "Access denied (HTTP 403): the API key is not allowed to use this model or "
                    + "endpoint. Check your plan, organization, or gateway permissions.";
        } else if (status == 404) {
            text = withDetail("Not found (HTTP 404): the selected model or the configured base URL "
                    + "does not exist for this provider. Pick another model or check the "
                    + "provider's base.url setting.", detail);
        } else if (status == 400 || status == 422) {
            text = withDetail("The provider rejected the request (HTTP " + status + ").", detail);
        } else if (status == 408 || status >= 500) {
            text = withDetail("The provider had a server error (HTTP " + status + "). "
                    + "This is usually temporary; try again in a moment.", detail);
        } else if (status > 0) {
            text = withDetail("The provider returned HTTP " + status + ".", detail);
        } else if (isTimeout(error)) {
            text = "The request to the provider timed out. Check your network or proxy, or "
                    + "raise the provider's timeout setting.";
        } else if (isUnreachable(error)) {
            text = withDetail("Could not reach the provider. Check your network, proxy, and the "
                    + "provider's base URL.", detail);
        } else {
            text = withDetail("Sorry, I encountered an error while " + action + ".", detail);
        }
        return "Error: " + text + "\n\n" + LOG_HINT;
    }

    /**
     * True when retrying the same request elsewhere (e.g. Agent Mode falling back to plain
     * chat) would hit the same wall: a rejected key, missing permission, or unknown model.
     */
    public static boolean isConfigurationError(Throwable error) {
        int status = statusCode(error);
        return status == 401 || status == 403 || status == 404;
    }

    /** HTTP status carried by an SDK exception anywhere in the cause chain, or -1. */
    static int statusCode(Throwable error) {
        for (Throwable t : chain(error)) {
            if (t instanceof com.openai.errors.OpenAIServiceException) {
                return ((com.openai.errors.OpenAIServiceException) t).statusCode();
            }
            if (t instanceof com.anthropic.errors.AnthropicServiceException) {
                return ((com.anthropic.errors.AnthropicServiceException) t).statusCode();
            }
            if (t instanceof com.google.genai.errors.ApiException) {
                return ((com.google.genai.errors.ApiException) t).code();
            }
        }
        return -1;
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable t : chain(error)) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException
                    || t instanceof TimeoutException
                    || (t instanceof InterruptedIOException && mentionsTimeout(t))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnreachable(Throwable error) {
        for (Throwable t : chain(error)) {
            if (t instanceof UnknownHostException || t instanceof ConnectException
                    || t instanceof NoRouteToHostException
                    || t instanceof com.openai.errors.OpenAIIoException
                    || t instanceof com.anthropic.errors.AnthropicIoException
                    || t instanceof com.google.genai.errors.GenAiIOException) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsTimeout(Throwable t) {
        String m = t.getMessage();
        if (m == null) {
            return false;
        }
        String lower = m.toLowerCase();
        return lower.contains("timeout") || lower.contains("timed out");
    }

    /** The innermost non-blank message, without an SDK "NNN: " / "NNN STATUS." prefix, capped in length. */
    static String detail(Throwable error) {
        String message = null;
        for (Throwable t : chain(error)) {
            String m = t.getMessage();
            if (m != null && !m.isBlank()) {
                message = m;
            }
        }
        if (message == null) {
            return "";
        }
        String trimmed = message.trim().replaceFirst("^\\d{3}(:|\\s+[A-Z_]+\\.)\\s*", "");
        if (trimmed.length() > MAX_DETAIL) {
            trimmed = trimmed.substring(0, MAX_DETAIL).trim() + "...";
        }
        return trimmed;
    }

    private static String withDetail(String text, String detail) {
        if (detail.isEmpty()) {
            return text;
        }
        char last = detail.charAt(detail.length() - 1);
        boolean punctuated = last == '.' || last == '!' || last == '?';
        return text + " Provider said: " + detail + (punctuated ? "" : ".");
    }

    private static Iterable<Throwable> chain(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> list = new ArrayList<>();
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
            list.add(t);
        }
        return list;
    }
}
