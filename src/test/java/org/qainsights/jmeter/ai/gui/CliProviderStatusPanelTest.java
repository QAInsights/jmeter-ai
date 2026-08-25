package org.qainsights.jmeter.ai.gui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.cli.CliAuthState;
import org.qainsights.jmeter.ai.cli.SubscriptionCliProvider;

class CliProviderStatusPanelTest {

    @Test
    void refreshButtonRedetectsTheProvider() throws Exception {
        FakeProvider provider = new FakeProvider();
        AtomicReference<CliProviderStatusPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panel.set(new CliProviderStatusPanel(List.of(provider), ignored -> { })));
        JButton refresh = findButton(panel.get(), "Refresh");
        assertNotNull(refresh);
        waitUntilEnabled(refresh);

        SwingUtilities.invokeAndWait(refresh::doClick);

        assertTrue(provider.refreshed.await(2, TimeUnit.SECONDS));
    }

    private static JButton findButton(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton button = findButton(child, text);
                if (button != null) {
                    return button;
                }
            }
        }
        return null;
    }

    private static void waitUntilEnabled(JButton button) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> enabled = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> enabled.set(button.isEnabled()));
            if (enabled.get()) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue(button.isEnabled());
    }

    private static final class FakeProvider implements SubscriptionCliProvider {

        private final CountDownLatch refreshed = new CountDownLatch(1);

        @Override
        public void refresh() {
            refreshed.countDown();
        }

        @Override
        public List<String> listModels() {
            return List.of("default");
        }

        @Override
        public String displayName() {
            return "Fake CLI";
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean isInstalled() {
            return true;
        }

        @Override
        public CliAuthState getAuthStatus() {
            return new CliAuthState() {
                @Override
                public String label() {
                    return "Signed in";
                }

                @Override
                public boolean isInstalled() {
                    return true;
                }

                @Override
                public boolean canRunPrompts() {
                    return true;
                }
            };
        }

        @Override
        public CliAuthState login() {
            return getAuthStatus();
        }

        @Override
        public CliAuthState logout() {
            return getAuthStatus();
        }

        @Override
        public String execute(String prompt) {
            return "ok";
        }

        @Override
        public String installHint() {
            return "install it";
        }

        @Override
        public String signInActionLabel() {
            return "Sign in";
        }

        @Override
        public String modelPrefix() {
            return "fake:";
        }

        @Override
        public String getModel() {
            return "";
        }

        @Override
        public void setModel(String model) {
        }
    }
}
