package org.qainsights.jmeter.ai.service;

import org.qainsights.jmeter.ai.claudecode.ClaudeCodeCliProvider;

/**
 * Uses the authenticated Claude Code CLI session (Claude subscription or the
 * credential configured inside the CLI) as an AI backend. Independent of
 * {@link ClaudeService}, which keeps talking to the Anthropic API with
 * {@code anthropic.api.key}.
 */
public class ClaudeCodeAiService extends CliSubscriptionAiService {

    public ClaudeCodeAiService() {
        this(new ClaudeCodeCliProvider());
    }

    public ClaudeCodeAiService(ClaudeCodeCliProvider provider) {
        super(provider);
    }

    /** The Claude Code provider, narrowed for callers that need auth actions or model lists. */
    public ClaudeCodeCliProvider getClaudeCodeProvider() {
        return (ClaudeCodeCliProvider) getProvider();
    }

    @Override
    public String getName() {
        return "Claude Code";
    }
}
