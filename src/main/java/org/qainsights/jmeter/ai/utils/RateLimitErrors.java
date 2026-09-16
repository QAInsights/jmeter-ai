package org.qainsights.jmeter.ai.utils;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Recognises HTTP 429 (rate limit / quota exceeded) failures from the OpenAI,
 * Anthropic and Google GenAI SDKs and turns them into a message that keeps the
 * server's own explanation, since gateways often put the actionable detail
 * (quota size, reset window) there.
 */
public final class RateLimitErrors {

    private RateLimitErrors() {
    }

    public static boolean isRateLimited(Throwable error) {
        return findRateLimit(error) != null;
    }

    /**
     * User-facing text for a 429 raised by an Agent Mode run.
     */
    public static String describe(Throwable error) {
        return describe(error, true);
    }

    /**
     * User-facing text for a 429: the provider's message (trimmed of the SDK's
     * "429: " prefix) followed by concrete ways to cut usage. Agent-specific
     * advice (tool schemas, max.tokens, switching to plain chat) is only
     * included when {@code agentMode} is true.
     */
    public static String describe(Throwable error, boolean agentMode) {
        String detail = serverMessage(error);
        StringBuilder sb = new StringBuilder("Rate limit or token quota exceeded (HTTP 429)");
        if (!detail.isEmpty()) {
            sb.append(": ").append(detail);
            if (!endsWithPunctuation(detail)) {
                sb.append('.');
            }
        } else {
            sb.append('.');
        }
        sb.append("\n\n");
        if (agentMode) {
            sb.append("Each agent turn re-sends the system prompt, all tool definitions and the "
                    + "conversation so far, so even a short request can use several thousand tokens. "
                    + "To reduce usage: lower jmeter.ai.agent.max.tokens, start a new chat, set "
                    + "openai.max.retries=0 / anthropic.max.retries=0 so a 429 is not retried, or use "
                    + "plain chat mode. Per-turn token counts are logged in jmeter.log "
                    + "(\"Agent token usage\").");
        } else {
            sb.append("Each request re-sends the conversation so far. To reduce usage: start a new "
                    + "chat, wait for the quota window to reset, or set openai.max.retries=0 / "
                    + "anthropic.max.retries=0 so a 429 is not retried automatically.");
        }
        return sb.toString();
    }

    static String serverMessage(Throwable error) {
        Throwable cause = findRateLimit(error);
        Throwable source = cause != null ? cause : error;
        return stripStatusPrefix(source == null ? null : source.getMessage());
    }

    private static Throwable findRateLimit(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof com.openai.errors.RateLimitException
                    || t instanceof com.anthropic.errors.RateLimitException
                    || (t instanceof com.google.genai.errors.ApiException
                            && ((com.google.genai.errors.ApiException) t).code() == 429)) {
                return t;
            }
        }
        return null;
    }

    private static boolean endsWithPunctuation(String s) {
        char last = s.charAt(s.length() - 1);
        return last == '.' || last == '!' || last == '?';
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
