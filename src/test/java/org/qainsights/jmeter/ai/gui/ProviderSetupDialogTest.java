package org.qainsights.jmeter.ai.gui;

import java.util.List;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.ProviderConnectionTester;
import org.qainsights.jmeter.ai.service.ProviderSetupOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
