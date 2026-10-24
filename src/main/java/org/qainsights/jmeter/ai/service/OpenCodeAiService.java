package org.qainsights.jmeter.ai.service;

import org.qainsights.jmeter.ai.opencode.OpenCodeCliProvider;

/** Uses the local OpenCode CLI as a Chat and Agent Mode backend. */
public class OpenCodeAiService extends CliSubscriptionAiService {

    public OpenCodeAiService() {
        this(new OpenCodeCliProvider());
    }

    public OpenCodeAiService(OpenCodeCliProvider provider) {
        super(provider);
    }

    /** The OpenCode provider, narrowed for callers that need model discovery. */
    public OpenCodeCliProvider getOpenCodeProvider() {
        return (OpenCodeCliProvider) getProvider();
    }

    @Override
    public String getName() {
        return "OpenCode";
    }
}
