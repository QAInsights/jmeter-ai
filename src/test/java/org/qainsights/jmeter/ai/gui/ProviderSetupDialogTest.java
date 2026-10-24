package org.qainsights.jmeter.ai.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.accessibility.AccessibleRole;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.service.ProviderConnectionTester;
import org.qainsights.jmeter.ai.service.ProviderSetupOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ProviderSetupDialogTest {
    @TempDir
    Path tempDir;

    @Test
    void keepsAnEditedCustomModelWhenTheModelListRefreshes() {
        assertEquals("custom-model",
                ProviderSetupDialog.modelAfterRefresh(
                        "custom-model", "llama3.1", List.of("llama3.1", "qwen2.5")));
    }

    @Test
    void keepsTheConfiguredModelOrUsesTheFirstAvailableModel() {
        assertEquals("llama3.1",
                ProviderSetupDialog.modelAfterRefresh(
                        "llama3.1", "llama3.1", List.of("llama3.1", "qwen2.5")));
        assertEquals("qwen2.5",
                ProviderSetupDialog.modelAfterRefresh(
                        "llama3.1", "llama3.1", List.of("qwen2.5")));
        assertEquals("llama3.1",
                ProviderSetupDialog.modelAfterRefresh("llama3.1", "llama3.1", List.of()));
    }

    @Test
    void keepsUntaggedConfiguredModelWhenLatestTagIsListed() {
        assertEquals("llama3.2", ProviderSetupDialog.modelAfterRefresh(
                "llama3.2", "llama3.2", List.of("llama3.2:latest", "qwen2.5:latest")));
    }

    @Test
    void smartRoutingSavesOnlyTheSelectedBackendSettings() {
        assertEquals(java.util.Map.of(
                        "jmeter.ai.typesafe.agent.routing.enabled", "false",
                        "jmeter.ai.typesafe.agent.triage.enabled", "false"),
                ProviderSetupDialog.smartRoutingSettings("off", true, true, "unused"));
        assertEquals(java.util.Map.of(
                        "jmeter.ai.typesafe.enabled", "true",
                        "jmeter.ai.typesafe.agent.routing.enabled", "true",
                        "jmeter.ai.typesafe.agent.triage.enabled", "true",
                        "jmeter.ai.judgment.provider", "typesafe",
                        "typesafe.api.key", "entered-typesafe-key"),
                ProviderSetupDialog.smartRoutingSettings("typesafe", true, true, "entered-typesafe-key"));
        assertEquals(java.util.Map.of(
                        "jmeter.ai.typesafe.enabled", "true",
                        "jmeter.ai.typesafe.agent.routing.enabled", "true",
                        "jmeter.ai.typesafe.agent.triage.enabled", "true",
                        "jmeter.ai.judgment.provider", "openai"),
                ProviderSetupDialog.smartRoutingSettings("openai", true, true, null));
        assertEquals("entered-openai-key", ProviderSetupDialog.smartRoutingSettings(
                "openai", true, true, "entered-openai-key").get("openai.api.key"));
        assertEquals(java.util.Map.of(
                        "jmeter.ai.typesafe.enabled", "true",
                        "jmeter.ai.typesafe.agent.routing.enabled", "true",
                        "jmeter.ai.typesafe.agent.triage.enabled", "true",
                        "jmeter.ai.judgment.provider", "openrouter",
                        "openrouter.api.key", "entered-openrouter-key"),
                ProviderSetupDialog.smartRoutingSettings(
                        "openrouter", true, true, "entered-openrouter-key"));
        for (String backend : List.of("local", "ollama")) {
            assertEquals(java.util.Map.of(
                            "jmeter.ai.typesafe.enabled", "true",
                            "jmeter.ai.typesafe.agent.routing.enabled", "true",
                            "jmeter.ai.typesafe.agent.triage.enabled", "false",
                            "jmeter.ai.judgment.provider", backend),
                    ProviderSetupDialog.smartRoutingSettings(backend, true, true, null));
        }
        for (String backend : List.of("off", "local", "ollama")) {
            assertFalse(ProviderSetupDialog.triageSelectedForBackend(backend, true));
        }
        assertTrue(ProviderSetupDialog.triageSelectedForBackend("typesafe", true));
        assertEquals("true", ProviderSetupDialog.appleSettings().get("apple.enabled"));
        assertEquals("Run ollama pull embeddinggemma.",
                ProviderSetupDialog.ollamaMissingModelCopy("embeddinggemma"));
    }

    @Test
    void appleNavigationAndReasonCopyUseInjectedPlatformAndStatus() {
        assertFalse(ProviderSetupDialog.navigationProviderOptions(() -> false)
                .contains(ProviderSetupOption.APPLE_ON_DEVICE));
        assertTrue(ProviderSetupDialog.navigationProviderOptions(() -> true)
                .contains(ProviderSetupOption.APPLE_ON_DEVICE));
        assertEquals("This Mac doesn't support Apple Intelligence",
                ProviderSetupDialog.appleReasonCopy("deviceNotEligible"));
        assertEquals("Turn on Apple Intelligence in System Settings",
                ProviderSetupDialog.appleReasonCopy("appleIntelligenceNotEnabled"));
        assertEquals("The on-device model is still downloading. Try again in a few minutes",
                ProviderSetupDialog.appleReasonCopy("modelNotReady"));
        assertEquals("Not available right now", ProviderSetupDialog.appleReasonCopy("unsupportedPlatform"));
        java.util.function.Supplier<org.qainsights.jmeter.ai.apple.AppleFoundationHelper.Status> status =
                org.qainsights.jmeter.ai.apple.AppleFoundationHelper.Status::available;
        assertEquals("Available on this Mac", ProviderSetupDialog.appleStatusCopy(status.get()));
        assertEquals("This Mac doesn't support Apple Intelligence", ProviderSetupDialog.appleStatusCopy(
                org.qainsights.jmeter.ai.apple.AppleFoundationHelper.Status.unavailable("deviceNotEligible")));
        assertTrue(status.get().isAvailable());
    }

    @Test
    void providerRowsAndWelcomeButtonGrowToFitLargeFonts() {
        Font originalLabelFont = UIManager.getFont("Label.font");
        Font originalButtonFont = UIManager.getFont("Button.font");
        Font largeLabelFont = originalLabelFont.deriveFont(originalLabelFont.getSize2D() * 3f);
        Font largeButtonFont = originalButtonFont.deriveFont(originalButtonFont.getSize2D() * 3f);
        UIManager.put("Label.font", largeLabelFont);
        UIManager.put("Button.font", largeButtonFont);
        try {
            ProviderSetupDialog.ProviderRow row =
                    new ProviderSetupDialog.ProviderRow(ProviderSetupOption.OPENAI);
            List<JLabel> labels = new ArrayList<>();
            collectLabels(row, labels);
            FontMetrics titleMetrics = labels.get(0).getFontMetrics(labels.get(0).getFont());
            FontMetrics hintMetrics = labels.get(1).getFontMetrics(labels.get(1).getFont());
            Insets insets = row.getInsets();
            int minimumTextHeight = titleMetrics.getHeight() + hintMetrics.getHeight()
                    + insets.top + insets.bottom;
            assertTrue(row.getPreferredSize().height >= minimumTextHeight);
            assertEquals(row.getPreferredSize().height, row.getMaximumSize().height);

            JPanel list = new JPanel();
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            list.add(row);
            list.setSize(600, row.getPreferredSize().height);
            list.doLayout();
            assertTrue(row.getHeight() >= minimumTextHeight);

            WelcomePanel welcome = new WelcomePanel("Setup", largeLabelFont, () -> { });
            QuietButton connect = welcome.connectButton();
            FontMetrics buttonMetrics = connect.getFontMetrics(connect.getFont());
            assertTrue(connect.getPreferredSize().height >= buttonMetrics.getHeight());
        } finally {
            UIManager.put("Label.font", originalLabelFont);
            UIManager.put("Button.font", originalButtonFont);
        }
    }

    @Test
    void configuredKeyUsesTheSamePropertyForRowBadgeAndKeyPanePlaceholder() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        var properties = JMeterUtils.getJMeterProperties();
        String original = properties.getProperty("openai.api.key");
        try {
            JMeterUtils.setProperty("openai.api.key", "configured-test-value");
            ProviderSetupDialog.ProviderRow row =
                    new ProviderSetupDialog.ProviderRow(ProviderSetupOption.OPENAI);

            assertTrue(row.hasConfiguredBadge());
            assertEquals("Saved key. Paste a replacement.",
                    ProviderSetupDialog.keyPlaceholder(ProviderSetupOption.OPENAI));
        } finally {
            if (original == null) {
                properties.remove("openai.api.key");
            } else {
                JMeterUtils.setProperty("openai.api.key", original);
            }
        }
    }

    @Test
    void providerHintsAndDisabledPrimaryButtonsMeetContrastRequirementsInDarkAndLightPalettes() {
        String[] keys = {
                "Panel.background", "TextPane.background",
                "Label.disabledForeground", "Label.foreground"
        };
        Object[] original = new Object[keys.length];
        for (int i = 0; i < keys.length; i++) {
            original[i] = UIManager.get(keys[i]);
        }
        try {
            assertHintContrastForPalette(new Color(0x292C31), new Color(0x70747A), Color.WHITE);
            assertHintContrastForPalette(new Color(0xF5F5F5), new Color(0xAAAAAA), Color.BLACK);
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (original[i] == null) {
                    UIManager.getDefaults().remove(keys[i]);
                } else {
                    UIManager.put(keys[i], original[i]);
                }
            }
        }
    }

    private static void assertHintContrastForPalette(Color background, Color secondary, Color foreground) {
        UIManager.put("Panel.background", background);
        UIManager.put("TextPane.background", background);
        UIManager.put("Label.disabledForeground", secondary);
        UIManager.put("Label.foreground", foreground);
        ProviderSetupDialog.ProviderRow row =
                new ProviderSetupDialog.ProviderRow(ProviderSetupOption.OPENAI);

        assertRowHintContrast(row);
        row.setRollover(true);
        assertRowHintContrast(row);
        row.setRollover(false);
        row.setSelected(true);
        assertRowHintContrast(row);

        QuietButton disabledPrimary = new QuietButton("Use Apple on-device", QuietButton.Kind.PRIMARY);
        disabledPrimary.setEnabled(false);
        Color disabledBackground = ThemeColors.blend(ThemeColors.accent(), ThemeColors.canvas(), 0.42f);
        double buttonContrast = ThemeColors.contrastRatio(disabledPrimary.getForeground(), disabledBackground);
        assertTrue(buttonContrast >= 3.0, "Disabled primary text contrast ratio was " + buttonContrast);
    }

    private static void assertRowHintContrast(ProviderSetupDialog.ProviderRow row) {
        double ratio = ThemeColors.contrastRatio(row.hintColor(), row.rowBackground());
        assertTrue(ratio >= 4.5, "Hint contrast ratio was " + ratio);
    }

    @Test
    void providerRowSupportsKeyboardSelectionAndAccessiblePushButtonSemantics() {
        ProviderSetupDialog.ProviderRow row =
                new ProviderSetupDialog.ProviderRow(ProviderSetupOption.OPENAI);
        AtomicBoolean selected = new AtomicBoolean();
        AtomicBoolean previous = new AtomicBoolean();
        AtomicBoolean next = new AtomicBoolean();
        row.setActionHandlers(
                () -> selected.set(true),
                () -> previous.set(true),
                () -> next.set(true));

        row.getActionMap().get("activate-provider").actionPerformed(
                new ActionEvent(row, ActionEvent.ACTION_PERFORMED, "activate"));
        row.getActionMap().get("previous-provider").actionPerformed(
                new ActionEvent(row, ActionEvent.ACTION_PERFORMED, "previous"));
        row.getActionMap().get("next-provider").actionPerformed(
                new ActionEvent(row, ActionEvent.ACTION_PERFORMED, "next"));

        assertTrue(selected.get());
        assertTrue(previous.get());
        assertTrue(next.get());
        assertTrue(row.isFocusable());
        assertEquals(AccessibleRole.PUSH_BUTTON, row.getAccessibleContext().getAccessibleRole());
        assertEquals("OpenAI, Chat and Agent Mode",
                row.getAccessibleContext().getAccessibleName());
    }

    private static void collectLabels(Component component, List<JLabel> labels) {
        if (component instanceof JLabel label) {
            labels.add(label);
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                collectLabels(child, labels);
            }
        }
    }

    @Test
    void ignoresTestResultWhenKeyChangesWhileRequestIsBlocked() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        CountDownLatch testCompleted = new CountDownLatch(1);
        AtomicBoolean saved = new AtomicBoolean();
        AtomicReference<ProviderSetupDialog> dialogRef = new AtomicReference<>();
        ProviderConnectionTester tester = new ProviderConnectionTester(request -> {
            requestStarted.countDown();
            releaseRequest.await();
            return new ProviderConnectionTester.Response(200, "{\"data\":[]}");
        });

        SwingUtilities.invokeAndWait(() -> {
            ProviderSetupDialog dialog = new ProviderSetupDialog(null, option -> saved.set(true),
                    false, tester, tempDir.resolve("user.properties"), testCompleted::countDown);
            dialogRef.set(dialog);
            try {
                Field keyField = ProviderSetupDialog.class.getDeclaredField("keyField");
                keyField.setAccessible(true);
                ((javax.swing.JTextField) keyField.get(dialog)).setText("old-test-key");
                Method startTest = ProviderSetupDialog.class.getDeclaredMethod("startTest", boolean.class);
                startTest.setAccessible(true);
                startTest.invoke(dialog, true);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        try {
            org.junit.jupiter.api.Assertions.assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Field keyField = ProviderSetupDialog.class.getDeclaredField("keyField");
                    keyField.setAccessible(true);
                    ((javax.swing.JTextField) keyField.get(dialogRef.get())).setText("new-test-key");
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
        } finally {
            releaseRequest.countDown();
        }

        org.junit.jupiter.api.Assertions.assertTrue(testCompleted.await(5, TimeUnit.SECONDS));
        assertFalse(saved.get());
        SwingUtilities.invokeAndWait(() -> {
            try {
                Field statusText = ProviderSetupDialog.class.getDeclaredField("statusText");
                statusText.setAccessible(true);
                assertEquals("Inputs changed during the test. Test again before saving.",
                        ((javax.swing.JTextArea) statusText.get(dialogRef.get())).getText());
                Field resultCurrent = ProviderSetupDialog.class.getDeclaredField("resultCurrent");
                resultCurrent.setAccessible(true);
                assertFalse(resultCurrent.getBoolean(dialogRef.get()));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        SwingUtilities.invokeAndWait(() -> dialogRef.get().dispose());
    }

    @Test
    void ignoresStaleSmartRoutingTestAfterKeyChanges() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        CountDownLatch testCompleted = new CountDownLatch(1);
        AtomicBoolean saved = new AtomicBoolean();
        AtomicReference<ProviderSetupDialog> dialogRef = new AtomicReference<>();
        ProviderConnectionTester tester = new ProviderConnectionTester(request -> null, (backend, key) -> {
                requestStarted.countDown();
                try {
                    releaseRequest.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ProviderConnectionTester.Result(
                        ProviderConnectionTester.Outcome.OK, 0, List.of(), 200, null);
            });

        SwingUtilities.invokeAndWait(() -> {
            ProviderSetupDialog dialog = new ProviderSetupDialog(null, option -> saved.set(true),
                    false, tester, tempDir.resolve("routing.properties"), testCompleted::countDown);
            dialogRef.set(dialog);
            try {
                Field backend = ProviderSetupDialog.class.getDeclaredField("smartBackend");
                backend.setAccessible(true);
                backend.set(dialog, "typesafe");
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            dialog.selectOption(ProviderSetupOption.SMART_ROUTING);
            try {
                Field fields = ProviderSetupDialog.class.getDeclaredField("smartKeyFields");
                fields.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<String, javax.swing.JTextField> smartKeys =
                        (java.util.Map<String, javax.swing.JTextField>) fields.get(dialog);
                smartKeys.get("typesafe").setText("old-routing-key");
                Method startTest = ProviderSetupDialog.class.getDeclaredMethod("startTest", boolean.class);
                startTest.setAccessible(true);
                startTest.invoke(dialog, true);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        try {
            org.junit.jupiter.api.Assertions.assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Field fields = ProviderSetupDialog.class.getDeclaredField("smartKeyFields");
                    fields.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, javax.swing.JTextField> smartKeys =
                            (java.util.Map<String, javax.swing.JTextField>) fields.get(dialogRef.get());
                    smartKeys.get("typesafe").setText("new-routing-key");
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
        } finally {
            releaseRequest.countDown();
        }

        org.junit.jupiter.api.Assertions.assertTrue(testCompleted.await(5, TimeUnit.SECONDS));
        assertFalse(saved.get());
        SwingUtilities.invokeAndWait(() -> dialogRef.get().dispose());
    }
}
