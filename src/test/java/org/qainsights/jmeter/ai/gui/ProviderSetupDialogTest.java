package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
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
    void providerRowsAndWelcomeButtonGrowToFitLargeFonts() {
        Font originalLabelFont = UIManager.getFont("Label.font");
        Font originalButtonFont = UIManager.getFont("Button.font");
        Font largeLabelFont = originalLabelFont.deriveFont(originalLabelFont.getSize2D() * 3f);
        Font largeButtonFont = originalButtonFont.deriveFont(originalButtonFont.getSize2D() * 3f);
        UIManager.put("Label.font", largeLabelFont);
        UIManager.put("Button.font", largeButtonFont);
        try {
            ProviderSetupDialog.ProviderRow row =
                    new ProviderSetupDialog.ProviderRow(ProviderSetupOption.OPENAI, true);
            List<JLabel> labels = new ArrayList<>();
            collectLabels(row, labels);
            FontMetrics titleMetrics = labels.get(0).getFontMetrics(labels.get(0).getFont());
            FontMetrics hintMetrics = labels.get(1).getFontMetrics(labels.get(1).getFont());
            Insets insets = row.getInsets();
            int minimumTextHeight = titleMetrics.getHeight() + hintMetrics.getHeight()
                    + insets.top + insets.bottom;
            assertTrue(row.getPreferredSize().height >= minimumTextHeight);

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
        SwingUtilities.invokeAndWait(() -> dialogRef.get().dispose());
    }
}
