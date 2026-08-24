package org.qainsights.jmeter.ai.service;

import org.qainsights.jmeter.ai.codex.CodexCliProvider;

/**
 * Uses the authenticated Codex CLI session (ChatGPT subscription or the API key
 * configured inside Codex) as an AI backend. Independent of {@link OpenAiService},
 * which keeps talking to the OpenAI API with {@code openai.api.key}.
 */
public class CodexAiService extends CliSubscriptionAiService {

    public CodexAiService() {
        this(new CodexCliProvider());
    }

    public CodexAiService(CodexCliProvider provider) {
        super(provider);
    }

    /** The Codex provider, narrowed for callers that need auth actions or model lists. */
    public CodexCliProvider getCodexProvider() {
        return (CodexCliProvider) getProvider();
    }

    @Override
    public String getName() {
        return "ChatGPT / Codex";
    }
}
