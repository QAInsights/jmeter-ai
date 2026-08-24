package org.qainsights.jmeter.ai.gui;

import java.awt.FlowLayout;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import org.qainsights.jmeter.ai.cli.CliAuthState;
import org.qainsights.jmeter.ai.cli.SubscriptionCliProvider;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

/**
 * Footer of the model picker showing each subscription CLI provider's sign-in
 * state (Codex, Claude Code) with Sign in / Sign out / Refresh actions. Every
 * CLI call runs in a {@link SwingWorker}, so neither status checks nor the
 * browser login flow block the EDT. Sign-in always ends by re-reading the state
 * from the CLI rather than trusting the exit code.
 * <p>
 * Each row also offers "Custom model…": these CLIs expose no model list, so a
 * newly released id can be typed in here and used immediately instead of
 * editing a properties file and restarting JMeter. Asking for that id is left
 * to the owner of this panel - a dialog parented here would be owned by the
 * picker popup, which disposes itself on focus loss and would take the prompt
 * down with it.
 */
class CliProviderStatusPanel extends JPanel {

    private final List<SubscriptionCliProvider> providers;

    CliProviderStatusPanel(List<SubscriptionCliProvider> providers,
                           Consumer<SubscriptionCliProvider> onCustomModelRequest) {
        super();
        this.providers = providers;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_2, 0, 0, 0));
        for (SubscriptionCliProvider provider : providers) {
            add(new ProviderRow(provider, onCustomModelRequest));
        }
    }

    /** The providers rendered by this panel (visible for tests). */
    List<SubscriptionCliProvider> providers() {
        return providers;
    }

    /** One provider: name, status text and the actions that apply to it. */
    private static final class ProviderRow extends JPanel {

        private final SubscriptionCliProvider provider;
        private final JLabel statusLabel = new JLabel("Checking\u2026");
        private final JButton signInButton;
        private final JButton signOutButton = new QuietButton("Sign out", QuietButton.Kind.GHOST).compact();
        private final JButton refreshButton = new QuietButton("Refresh", QuietButton.Kind.GHOST).compact();
        private final JButton customModelButton =
                new QuietButton("Custom model…", QuietButton.Kind.GHOST).compact();

        ProviderRow(SubscriptionCliProvider provider,
                    Consumer<SubscriptionCliProvider> onCustomModelRequest) {
            // Stacked: the label line above the actions line, so a long
            // "Sign in with ..." button can never collide with the status text.
            super();
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            this.provider = provider;
            this.signInButton = new QuietButton(provider.signInActionLabel(),
                    QuietButton.Kind.OUTLINED).compact();
            setOpaque(false);

            JLabel name = new JLabel(provider.displayName());
            statusLabel.setForeground(ThemeColors.secondaryText());

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.SPACE_1, 0));
            actions.setOpaque(false);
            signInButton.setToolTipText("Runs the " + provider.displayName()
                    + " CLI login in your browser; Feather Wand never sees your credentials");
            signInButton.addActionListener(e -> run(provider::login, "Signing in\u2026"));
            signOutButton.addActionListener(e -> run(provider::logout, "Signing out\u2026"));
            refreshButton.addActionListener(e -> run(provider::getAuthStatus, "Checking\u2026"));
            customModelButton.setToolTipText("Use a " + provider.displayName()
                    + " model id the CLI does not advertise; remembered for next time");
            customModelButton.addActionListener(e -> onCustomModelRequest.accept(provider));
            actions.add(signInButton);
            actions.add(signOutButton);
            actions.add(refreshButton);
            actions.add(customModelButton);

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.SPACE_2, 0));
            left.setOpaque(false);
            left.add(name);
            left.add(Box.createHorizontalStrut(UiTokens.SPACE_1));
            left.add(statusLabel);

            left.setAlignmentX(LEFT_ALIGNMENT);
            actions.setAlignmentX(LEFT_ALIGNMENT);
            add(left);
            add(actions);

            run(provider::getAuthStatus, "Checking\u2026");
        }

        /** Runs a blocking CLI call off the EDT and renders the resulting state. */
        private void run(java.util.concurrent.Callable<CliAuthState> action, String pendingText) {
            statusLabel.setText(pendingText);
            setActionsEnabled(false);
            new SwingWorker<CliAuthState, Void>() {
                @Override
                protected CliAuthState doInBackground() throws Exception {
                    return action.call();
                }

                @Override
                protected void done() {
                    try {
                        render(get());
                    } catch (java.util.concurrent.ExecutionException | InterruptedException e) {
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        statusLabel.setText("Unable to determine status");
                        setActionsEnabled(true);
                    }
                }
            }.execute();
        }

        private void render(CliAuthState state) {
            statusLabel.setText(state.label());
            setActionsEnabled(true);
            boolean installed = state.isInstalled();
            signInButton.setEnabled(installed && !state.canRunPrompts());
            signOutButton.setEnabled(installed && state.canRunPrompts());
            refreshButton.setEnabled(true);
            statusLabel.setToolTipText(installed ? null : provider.installHint());
        }

        private void setActionsEnabled(boolean enabled) {
            signInButton.setEnabled(enabled);
            signOutButton.setEnabled(enabled);
            refreshButton.setEnabled(enabled);
        }
    }
}
