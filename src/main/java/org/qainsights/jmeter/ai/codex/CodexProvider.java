package org.qainsights.jmeter.ai.codex;

import org.qainsights.jmeter.ai.cli.SubscriptionCliProvider;

/**
 * Codex-flavoured {@link SubscriptionCliProvider}: same contract, narrowed to
 * {@link CodexAuthStatus} so callers that care about the ChatGPT-vs-API-key
 * distinction do not have to downcast.
 */
public interface CodexProvider extends SubscriptionCliProvider {

    @Override
    CodexAuthStatus getAuthStatus();

    @Override
    CodexAuthStatus login();

    @Override
    CodexAuthStatus logout();
}
