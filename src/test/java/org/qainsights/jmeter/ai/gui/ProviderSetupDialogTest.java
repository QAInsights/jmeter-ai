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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.accessibility.AccessibleRole;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.AgentJudgmentConfig;
import org.qainsights.jmeter.ai.agent.AgentRoutingConfig;
import org.qainsights.jmeter.ai.agent.AgentTriageConfig;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.service.OllamaEmbeddingClient;
import org.qainsights.jmeter.ai.service.ProviderConnectionTester;
import org.qainsights.jmeter.ai.service.ProviderSetupOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        var disabledJev = ProviderSetupDialog.smartRoutingSettings("typesafe", false, false, "");
        assertEquals("false", disabledJev.get("jmeter.ai.typesafe.agent.routing.enabled"));
        assertEquals("false", disabledJev.get("jmeter.ai.typesafe.agent.triage.enabled"));
        assertFalse(disabledJev.containsKey("typesafe.api.key"));
        assertTrue(ProviderSetupDialog.smartRoutingFeaturesDisabled(false, false));
        assertFalse(ProviderSetupDialog.smartRoutingFeaturesDisabled(true, false));
        assertFalse(ProviderSetupDialog.smartRoutingFeaturesDisabled(false, true));
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
    void smartRoutingToggleDefaultsAndBackendTransitionsUseTheStoredState() {
        assertFalse(ProviderSetupDialog.initialRoutingSelection("typesafe", false));
        assertTrue(ProviderSetupDialog.initialRoutingSelection("typesafe", true));
        assertFalse(ProviderSetupDialog.initialRoutingSelection("off", true));
        assertTrue(ProviderSetupDialog.initialTriageChoice("off", false));
        assertFalse(ProviderSetupDialog.initialTriageChoice("typesafe", false));
        assertTrue(ProviderSetupDialog.initialTriageChoice("typesafe", true));
        assertTrue(ProviderSetupDialog.routingSelectionAfterBackendChange("off", "typesafe", false));
        assertFalse(ProviderSetupDialog.routingSelectionAfterBackendChange("typesafe", "off", true));
        assertFalse(ProviderSetupDialog.routingSelectionAfterBackendChange("typesafe", "openai", false));
        assertTrue(ProviderSetupDialog.routingSelectionAfterBackendChange("typesafe", "openai", true));
        assertFalse(ProviderSetupDialog.triageSelectedForBackend("local", true));
        assertTrue(ProviderSetupDialog.triageSelectedForBackend("typesafe", true));
    }

    @Test
    void smartRoutingInitialFlagsAndBackendSwitchesPreserveTheExpectedChoices() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        String[] keys = {
                AgentRoutingConfig.TYPESAFE_ENABLED_KEY,
                AgentRoutingConfig.ROUTING_ENABLED_KEY,
                AgentTriageConfig.TRIAGE_ENABLED_KEY,
                AgentJudgmentConfig.PROVIDER_KEY,
                AgentRoutingConfig.API_KEY
        };
        Map<String, String> original = snapshotProperties(keys);
        ProviderSetupDialog[] dialogs = new ProviderSetupDialog[3];
        try {
            JMeterUtils.setProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "true");
            JMeterUtils.setProperty(AgentRoutingConfig.ROUTING_ENABLED_KEY, "true");
            JMeterUtils.setProperty(AgentTriageConfig.TRIAGE_ENABLED_KEY, "false");
            JMeterUtils.setProperty(AgentJudgmentConfig.PROVIDER_KEY, AgentJudgmentConfig.TYPESAFE);
            JMeterUtils.setProperty(AgentRoutingConfig.API_KEY, "saved-typesafe-test-key");

            SwingUtilities.invokeAndWait(() -> {
                dialogs[0] = newDialog(tempDir.resolve("routing-state.properties"));
                dialogs[0].selectOption(ProviderSetupOption.SMART_ROUTING);
                JCheckBox routing = checkbox(dialogs[0], "routingEnabled");
                JCheckBox triage = checkbox(dialogs[0], "triageEnabled");
                assertTrue(routing.isSelected());
                assertFalse(triage.isSelected());

                clickBackend(dialogs[0], "OpenRouter Decisions");
                assertTrue(routing.isSelected());
                assertFalse(triage.isSelected());
                clickBackend(dialogs[0], "EmbeddingGemma");
                assertFalse(triage.isSelected());
                assertFalse(triage.isEnabled());
                clickBackend(dialogs[0], "Jev by TypeSafe");
                assertFalse(triage.isSelected());
                assertTrue(triage.isEnabled());

                triage.doClick();
                assertTrue(triage.isSelected());
                clickBackend(dialogs[0], "Ollama embeddings");
                assertFalse(triage.isSelected());
                assertFalse(triage.isEnabled());
                clickBackend(dialogs[0], "OpenAI Decisions");
                assertTrue(triage.isSelected());
                assertTrue(triage.isEnabled());
                clickBackend(dialogs[0], "Off");
                assertFalse(routing.isSelected());
                assertFalse(triage.isSelected());
                assertFalse(triage.isEnabled());
                clickBackend(dialogs[0], "OpenRouter Decisions");
                assertTrue(routing.isSelected());
                assertTrue(triage.isSelected());
            });

            JMeterUtils.setProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "true");
            JMeterUtils.getJMeterProperties().remove(AgentRoutingConfig.ROUTING_ENABLED_KEY);
            JMeterUtils.setProperty(AgentTriageConfig.TRIAGE_ENABLED_KEY, "true");
            SwingUtilities.invokeAndWait(() -> {
                dialogs[1] = newDialog(tempDir.resolve("routing-triage-only.properties"));
                dialogs[1].selectOption(ProviderSetupOption.SMART_ROUTING);
                JCheckBox routing = checkbox(dialogs[1], "routingEnabled");
                JCheckBox triage = checkbox(dialogs[1], "triageEnabled");
                assertFalse(routing.isSelected());
                assertTrue(triage.isSelected());
                clickBackend(dialogs[1], "OpenAI Decisions");
                assertFalse(routing.isSelected());
                assertTrue(triage.isSelected());
            });

            JMeterUtils.setProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "false");
            JMeterUtils.setProperty(AgentRoutingConfig.ROUTING_ENABLED_KEY, "false");
            JMeterUtils.setProperty(AgentTriageConfig.TRIAGE_ENABLED_KEY, "false");
            SwingUtilities.invokeAndWait(() -> {
                dialogs[2] = newDialog(tempDir.resolve("routing-off.properties"));
                dialogs[2].selectOption(ProviderSetupOption.SMART_ROUTING);
                JCheckBox routing = checkbox(dialogs[2], "routingEnabled");
                JCheckBox triage = checkbox(dialogs[2], "triageEnabled");
                assertFalse(routing.isSelected());
                assertFalse(triage.isSelected());
                clickBackend(dialogs[2], "OpenAI Decisions");
                assertTrue(routing.isSelected());
                assertTrue(triage.isSelected());
            });
        } finally {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    for (ProviderSetupDialog dialog : dialogs) {
                        if (dialog != null) {
                            dialog.dispose();
                        }
                    }
                });
            } finally {
                restoreProperties(original);
            }
        }
    }

    @Test
    void smartRoutingNavigationReflectsSavedSettingsUntilSave() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        String[] keys = {
                AgentRoutingConfig.TYPESAFE_ENABLED_KEY,
                AgentRoutingConfig.ROUTING_ENABLED_KEY,
                AgentTriageConfig.TRIAGE_ENABLED_KEY,
                AgentJudgmentConfig.PROVIDER_KEY,
                AgentRoutingConfig.API_KEY,
                AgentJudgmentConfig.OPENAI_API_KEY
        };
        Map<String, String> original = snapshotProperties(keys);
        AtomicReference<ProviderSetupDialog> dialogRef = new AtomicReference<>();
        try {
            JMeterUtils.setProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "true");
            JMeterUtils.setProperty(AgentRoutingConfig.ROUTING_ENABLED_KEY, "true");
            JMeterUtils.setProperty(AgentTriageConfig.TRIAGE_ENABLED_KEY, "false");
            JMeterUtils.setProperty(AgentJudgmentConfig.PROVIDER_KEY, AgentJudgmentConfig.TYPESAFE);
            JMeterUtils.setProperty(AgentRoutingConfig.API_KEY, "saved-typesafe-test-key");
            JMeterUtils.getJMeterProperties().remove(AgentJudgmentConfig.OPENAI_API_KEY);

            SwingUtilities.invokeAndWait(() -> {
                ProviderSetupDialog dialog = newDialog(tempDir.resolve("routing-nav.properties"));
                dialogRef.set(dialog);
                dialog.selectOption(ProviderSetupOption.SMART_ROUTING);
                ProviderSetupDialog.ProviderRow row = smartRoutingRow(dialog);
                assertTrue(row.getAccessibleContext().getAccessibleName().contains("Jev"));
                assertTrue(row.hasConfiguredBadge());

                clickBackend(dialog, "OpenAI Decisions");
                assertTrue(row.getAccessibleContext().getAccessibleName().contains("Jev"));
                assertTrue(row.hasConfiguredBadge());
                try {
                    Field fields = ProviderSetupDialog.class.getDeclaredField("smartKeyFields");
                    fields.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<String, javax.swing.JTextField> smartKeys =
                            (Map<String, javax.swing.JTextField>) fields.get(dialog);
                    smartKeys.get(AgentJudgmentConfig.OPENAI).setText("entered-openai-test-key");
                    Method saveSettings = ProviderSetupDialog.class.getDeclaredMethod("saveSettings");
                    saveSettings.setAccessible(true);
                    saveSettings.invoke(dialog);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }

                assertTrue(row.getAccessibleContext().getAccessibleName().contains("OpenAI Decisions"));
                assertTrue(row.hasConfiguredBadge());
            });
        } finally {
            try {
                ProviderSetupDialog dialog = dialogRef.get();
                if (dialog != null) {
                    SwingUtilities.invokeAndWait(dialog::dispose);
                }
            } finally {
                restoreProperties(original);
            }
        }
    }

    @Test
    void ollamaMissingModelRecognitionAndUnexpectedHttpErrorCopyAreSpecific() {
        String model = "embeddinggemma";
        String missingModelMessage = OllamaEmbeddingClient.modelUnavailableMessage(model);
        assertTrue(ProviderSetupDialog.isOllamaModelUnavailable(400, missingModelMessage, model));
        assertTrue(ProviderSetupDialog.isOllamaModelUnavailable(404, "unrelated 404 response", model));
        assertTrue(ProviderSetupDialog.isOllamaModelUnavailable(
                400, OllamaEmbeddingClient.modelUnavailableMessage(OllamaEmbeddingClient.DEFAULT_MODEL), ""));
        assertFalse(ProviderSetupDialog.isOllamaModelUnavailable(
                500, OllamaEmbeddingClient.modelUnavailableMessage("other-model"), model));
        assertEquals("Run ollama pull embeddinggemma.", ProviderSetupDialog.ollamaMissingModelCopy(""));
        assertEquals("Run ollama pull embeddinggemma.", ProviderSetupDialog.ollamaMissingModelCopy("  "));

        ProviderConnectionTester.Result malformedResponse = new ProviderConnectionTester.Result(
                ProviderConnectionTester.Outcome.HTTP_ERROR, 0, List.of(), 0, "invalid decision payload");
        String copy = ProviderSetupDialog.smartRoutingHttpErrorCopy(malformedResponse);
        assertEquals("Unexpected response from the provider: invalid decision payload You can save anyway.", copy);
        assertFalse(copy.contains("HTTP 0"));
    }

    @Test
    void appleSelectionKeepsTheCheckingStatusVisibleUntilTheProbeCompletes() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<ProviderSetupDialog> dialogRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ProviderSetupDialog dialog = new ProviderSetupDialog(
                    null, option -> { }, false, new ProviderConnectionTester(),
                    tempDir.resolve("apple-checking.properties"), () -> { },
                    () -> true, org.qainsights.jmeter.ai.apple.AppleFoundationHelper.Status::available);
            dialogRef.set(dialog);
            dialog.selectOption(ProviderSetupOption.APPLE_ON_DEVICE);
            try {
                Field statusText = ProviderSetupDialog.class.getDeclaredField("statusText");
                statusText.setAccessible(true);
                assertEquals("Checking…", ((javax.swing.JTextArea) statusText.get(dialog)).getText());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        SwingUtilities.invokeAndWait(() -> dialogRef.get().dispose());
    }

    private ProviderSetupDialog newDialog(Path propertiesPath) {
        return new ProviderSetupDialog(
                null, option -> { }, false, new ProviderConnectionTester(), propertiesPath,
                () -> { }, () -> false, null);
    }

    private static JCheckBox checkbox(ProviderSetupDialog dialog, String fieldName) {
        try {
            Field field = ProviderSetupDialog.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (JCheckBox) field.get(dialog);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void clickBackend(ProviderSetupDialog dialog, String label) {
        JRadioButton button = findRadioButton(dialog.getContentPane(), label);
        assertTrue(button != null, "Missing backend choice: " + label);
        button.doClick();
    }

    private static JRadioButton findRadioButton(Component component, String label) {
        if (component instanceof JRadioButton button && button.getText().contains(label)) {
            return button;
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                JRadioButton button = findRadioButton(child, label);
                if (button != null) {
                    return button;
                }
            }
        }
        return null;
    }

    private static ProviderSetupDialog.ProviderRow smartRoutingRow(ProviderSetupDialog dialog) {
        try {
            Field rowsField = ProviderSetupDialog.class.getDeclaredField("rows");
            rowsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ProviderSetupDialog.ProviderRow> rows =
                    (List<ProviderSetupDialog.ProviderRow>) rowsField.get(dialog);
            return rows.stream()
                    .filter(row -> row.option() == ProviderSetupOption.SMART_ROUTING)
                    .findFirst()
                    .orElseThrow();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, String> snapshotProperties(String... keys) {
        Map<String, String> original = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            original.put(key, JMeterUtils.getJMeterProperties().getProperty(key));
        }
        return original;
    }

    private static void restoreProperties(Map<String, String> original) {
        var properties = JMeterUtils.getJMeterProperties();
        for (Map.Entry<String, String> entry : original.entrySet()) {
            if (entry.getValue() == null) {
                properties.remove(entry.getKey());
            } else {
                JMeterUtils.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test
    void smartRoutingSignalsChatReloadOnlyWhenOpenAiKeyIsWritten() {
        assertEquals(ProviderSetupOption.SMART_ROUTING,
                ProviderSetupDialog.callbackOptionAfterSave(
                        ProviderSetupOption.SMART_ROUTING, java.util.Map.of("openai.api.key", "entered")));
        assertNull(ProviderSetupDialog.callbackOptionAfterSave(
                ProviderSetupOption.SMART_ROUTING, java.util.Map.of("typesafe.api.key", "entered")));
        assertNull(ProviderSetupDialog.callbackOptionAfterSave(
                ProviderSetupOption.SMART_ROUTING, java.util.Map.of()));
        assertEquals(ProviderSetupOption.OPENAI,
                ProviderSetupDialog.callbackOptionAfterSave(
                        ProviderSetupOption.OPENAI, java.util.Map.of("openai.api.key", "entered")));
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
    void statusTextUsesTheBodyFontInsteadOfMonospaced() {
        assertFalse(Font.MONOSPACED.equals(ProviderSetupDialog.createStatusText().getFont().getFamily()));
    }

    @Test
    void testsWithSavedKeyWhenChatKeyFieldIsEmptyAndTypingInvalidatesTheResult() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        String propertyKey = "openai.api.key";
        String savedKey = "saved-openai-test-secret";
        String original = setJMeterProperty(propertyKey, savedKey);
        CountDownLatch testCompleted = new CountDownLatch(1);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<ProviderSetupDialog> dialogRef = new AtomicReference<>();
        ProviderConnectionTester tester = new ProviderConnectionTester(request -> {
            authorization.set(request.headers().firstValue("Authorization").orElse(""));
            throw new java.io.IOException("Connection failed using " + savedKey);
        });

        try {
            SwingUtilities.invokeAndWait(() -> {
                ProviderSetupDialog dialog = new ProviderSetupDialog(
                        null, option -> { }, false, tester,
                        tempDir.resolve("saved-key-test.properties"), testCompleted::countDown);
                dialogRef.set(dialog);
                dialog.selectOption(ProviderSetupOption.OPENAI);
                try {
                    Method startTest = ProviderSetupDialog.class.getDeclaredMethod("startTest", boolean.class);
                    startTest.setAccessible(true);
                    startTest.invoke(dialog, false);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });

            assertTrue(testCompleted.await(5, TimeUnit.SECONDS));
            assertEquals("Bearer " + savedKey, authorization.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Field keyField = ProviderSetupDialog.class.getDeclaredField("keyField");
                    keyField.setAccessible(true);
                    javax.swing.JTextField input = (javax.swing.JTextField) keyField.get(dialogRef.get());
                    assertEquals("", input.getText());

                    Field statusText = ProviderSetupDialog.class.getDeclaredField("statusText");
                    statusText.setAccessible(true);
                    String status = ((javax.swing.JTextArea) statusText.get(dialogRef.get())).getText();
                    assertTrue(status.contains("Could not reach the provider"));
                    assertFalse(status.contains(savedKey));

                    Field resultCurrent = ProviderSetupDialog.class.getDeclaredField("resultCurrent");
                    resultCurrent.setAccessible(true);
                    assertTrue(resultCurrent.getBoolean(dialogRef.get()));
                    input.setText("replacement-openai-key");
                    assertFalse(resultCurrent.getBoolean(dialogRef.get()));
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (dialogRef.get() != null) {
                    dialogRef.get().dispose();
                }
            });
            restoreJMeterProperty(propertyKey, original);
        }
    }

    @Test
    void saveWithSavedKeyTestsAndWritesOnlyNonKeySettings() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        String propertyKey = "openai.api.key";
        String savedKey = "saved-openai-test-secret";
        String original = setJMeterProperty(propertyKey, savedKey);
        CountDownLatch testCompleted = new CountDownLatch(1);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<ProviderSetupOption> savedOption = new AtomicReference<>();
        AtomicReference<ProviderSetupDialog> dialogRef = new AtomicReference<>();
        Path propertiesPath = tempDir.resolve("saved-key-settings.properties");
        ProviderConnectionTester tester = new ProviderConnectionTester(request -> {
            authorization.set(request.headers().firstValue("Authorization").orElse(""));
            return new ProviderConnectionTester.Response(200, "{\"data\":[]}");
        });

        try {
            SwingUtilities.invokeAndWait(() -> {
                ProviderSetupDialog dialog = new ProviderSetupDialog(
                        null, savedOption::set, false, tester, propertiesPath, testCompleted::countDown);
                dialogRef.set(dialog);
                dialog.selectOption(ProviderSetupOption.OPENAI);
                try {
                    Method saveOrTest = ProviderSetupDialog.class.getDeclaredMethod("saveOrTest");
                    saveOrTest.setAccessible(true);
                    saveOrTest.invoke(dialog);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });

            assertTrue(testCompleted.await(5, TimeUnit.SECONDS));
            assertEquals("Bearer " + savedKey, authorization.get());
            assertEquals(ProviderSetupOption.OPENAI, savedOption.get());
            String persisted = java.nio.file.Files.readString(propertiesPath);
            assertTrue(persisted.contains(
                    "jmeter.ai.service.type=" + ProviderSetupOption.OPENAI.serviceType()));
            assertFalse(persisted.contains("openai.api.key="));
            assertFalse(persisted.contains(savedKey));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (dialogRef.get() != null) {
                    dialogRef.get().dispose();
                }
            });
            restoreJMeterProperty(propertyKey, original);
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
        ProviderConnectionTester tester = new ProviderConnectionTester(request -> null, (backend, key, triage) -> {
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

    private static String setJMeterProperty(String key, String value) {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        String original = JMeterUtils.getJMeterProperties().getProperty(key);
        JMeterUtils.setProperty(key, value);
        return original;
    }

    private static void restoreJMeterProperty(String key, String original) {
        if (original == null) {
            JMeterUtils.getJMeterProperties().remove(key);
        } else {
            JMeterUtils.setProperty(key, original);
        }
    }
}
