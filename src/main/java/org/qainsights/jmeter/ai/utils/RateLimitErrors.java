package org.qainsights.jmeter.ai.utils;

/**
 * Recognises HTTP 429 (rate limit / quota exceeded) failures from the OpenAI and
 * Anthropic SDKs and turns them into a message that keeps the server's own
 * explanation, since gateways often put the actionable detail (quota size,
 * reset window) there.
 */
public final class RateLimitErrors {

    private RateLimitErrors() {
    }

    public static boolean isRateLimited(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof com.openai.errors.RateLimitException
                    || t instanceof com.anthropic.errors.RateLimitException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * User-facing text for a 429: the provider's message (trimmed of the SDK's
     * "429: " prefix) followed by concrete ways to cut token usage.
     */
    public static String describe(Throwable error) {
        String detail = serverMessage(error);
        StringBuilder sb = new StringBuilder("Rate limit or token quota exceeded (HTTP 429)");
        if (!detail.isEmpty()) {
            sb.append(": ").append(detail);
        }
        sb.append(".\n\nEach agent turn re-sends the system prompt, all tool definitions and the "
                + "conversation so far, so even a short request can use several thousand tokens. "
                + "To reduce usage: lower jmeter.ai.agent.max.tokens, start a new chat, set "
                + "openai.max.retries=0 / anthropic.max.retries=0 so a 429 is not retried, or use "
                + "plain chat mode. Per-turn token counts are logged in jmeter.log "
                + "(\"Agent token usage\").");
        return sb.toString();
    }

    static String serverMessage(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof com.openai.errors.RateLimitException
                    || t instanceof com.anthropic.errors.RateLimitException) {
                return stripStatusPrefix(t.getMessage());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return stripStatusPrefix(error == null ? null : error.getMessage());
    }

    private static String stripStatusPrefix(String message) {
        if (message == null) {
            return "";
        }
        String trimmed = message.trim();
        if (trimmed.startsWith("429:")) {
            trimmed = trimmed.substring(4).trim();
        }
        return trimmed;
    }
}
