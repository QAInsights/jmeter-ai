package org.qainsights.jmeter.ai.opencode;

import org.qainsights.jmeter.ai.cli.CliAuthState;

/** Availability of the local OpenCode CLI. */
public enum OpenCodeAuthStatus implements CliAuthState {

    READY("\u2713 Ready", true, true),
    CLI_NOT_INSTALLED("OpenCode CLI not installed", false, false),
    UNKNOWN("Unable to determine status", true, false);

    private final String label;
    private final boolean installed;
    private final boolean canRunPrompts;

    OpenCodeAuthStatus(String label, boolean installed, boolean canRunPrompts) {
        this.label = label;
        this.installed = installed;
        this.canRunPrompts = canRunPrompts;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public boolean isInstalled() {
        return installed;
    }

    @Override
    public boolean canRunPrompts() {
        return canRunPrompts;
    }
}
