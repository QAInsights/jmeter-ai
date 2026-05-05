package org.qainsights.jmeter.ai.claudecode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JMeterActionBridge}.
 *
 * Private method {@code checkForActionFile()} is invoked via reflection to
 * exercise the file-reading and command-dispatching logic without waiting for
 * the internal Swing {@link javax.swing.Timer} to fire.
 *
 * Tests for the "reload" command flush the Swing EDT with
 * {@link SwingUtilities#invokeAndWait} so the {@code invokeLater} callback is
 * guaranteed to have run before the assertion.
 */
class JMeterActionBridgeTest {

    @TempDir
    Path tempDir;

    private JMeterActionBridge bridge;
    private Method checkForActionFile;

    @BeforeEach
    void setUp() throws Exception {
        bridge = new JMeterActionBridge(tempDir.toFile());
        checkForActionFile = JMeterActionBridge.class
                .getDeclaredMethod("checkForActionFile");
        checkForActionFile.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        bridge.stopWatching();
    }

    // ── isRecentlyStarted initial state ───────────────────────────────────────

    @Test
    void isRecentlyStarted_initiallyReturnsFalse() {
        assertFalse(bridge.isRecentlyStarted());
    }

    // ── startWatching / stopWatching ───────────────────────────────────────────

    @Test
    void startWatching_withNullDirectory_doesNotThrow() {
        JMeterActionBridge nullBridge = new JMeterActionBridge(null);
        assertDoesNotThrow(nullBridge::startWatching);
        nullBridge.stopWatching();
    }

    @Test
    void startWatching_withNonExistentDirectory_doesNotThrow() {
        File nonExistent = new File("/this/path/does/not/exist/xyz");
        JMeterActionBridge invalidBridge = new JMeterActionBridge(nonExistent);
        assertDoesNotThrow(invalidBridge::startWatching);
        invalidBridge.stopWatching();
    }

    @Test
    void stopWatching_whenNeverStarted_doesNotThrow() {
        JMeterActionBridge freshBridge = new JMeterActionBridge(tempDir.toFile());
        assertDoesNotThrow(freshBridge::stopWatching);
    }

    @Test
    void startThenStop_completesWithoutException() {
        assertDoesNotThrow(() -> {
            bridge.startWatching();
            bridge.stopWatching();
        });
    }

    // ── no action file present ─────────────────────────────────────────────────

    @Test
    void checkForActionFile_whenNoFilesPresent_doesNothing() throws Exception {
        assertDoesNotThrow(() -> checkForActionFile.invoke(bridge));
        assertFalse(bridge.isRecentlyStarted());
    }

    // ── reload command ─────────────────────────────────────────────────────────

    @Test
    void checkForActionFile_withReloadCommand_invokesRegisteredCallback()
            throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        bridge.setReloadCallback(() -> called.set(true));

        writeActionFile("reload");
        checkForActionFile.invoke(bridge);

        // Flush the EDT so the invokeLater runnable has executed.
        SwingUtilities.invokeAndWait(() -> {});

        assertTrue(called.get(), "Reload callback must be invoked for 'reload' command");
    }

    @Test
    void checkForActionFile_withReloadCommand_deletesActionFile() throws Exception {
        bridge.setReloadCallback(() -> {});

        File actionFile = writeActionFile("reload");
        checkForActionFile.invoke(bridge);

        assertFalse(actionFile.exists(), "Action file must be deleted after processing");
    }

    @Test
    void checkForActionFile_withReloadAndNoCallbackSet_doesNotThrow() throws Exception {
        // No callback registered – must not throw a NullPointerException.
        File actionFile = writeActionFile("reload");
        assertDoesNotThrow(() -> checkForActionFile.invoke(bridge));
        assertFalse(actionFile.exists());
    }

    // ── start / run commands ───────────────────────────────────────────────────

    @Test
    void checkForActionFile_withStartCommand_setsIsRecentlyStartedTrue()
            throws Exception {
        writeActionFile("start");
        checkForActionFile.invoke(bridge);

        // lastStartTriggeredAt is set synchronously before SwingUtilities.invokeLater.
        assertTrue(bridge.isRecentlyStarted(),
                "isRecentlyStarted must return true after 'start' command");
    }

    @Test
    void checkForActionFile_withStartCommand_deletesActionFile() throws Exception {
        File actionFile = writeActionFile("start");
        checkForActionFile.invoke(bridge);

        assertFalse(actionFile.exists(), "Action file must be deleted after processing");
    }

    @Test
    void checkForActionFile_withRunCommand_setsIsRecentlyStartedTrue()
            throws Exception {
        writeActionFile("run");
        checkForActionFile.invoke(bridge);

        assertTrue(bridge.isRecentlyStarted(),
                "'run' is an alias for 'start' and must set isRecentlyStarted");
    }

    // ── stop / shutdown commands ───────────────────────────────────────────────

    @Test
    void checkForActionFile_withStopCommand_deletesActionFileAndDoesNotThrow()
            throws Exception {
        File actionFile = writeActionFile("stop");
        assertDoesNotThrow(() -> checkForActionFile.invoke(bridge));
        assertFalse(actionFile.exists());
        assertFalse(bridge.isRecentlyStarted());
    }

    @Test
    void checkForActionFile_withShutdownCommand_deletesActionFileAndDoesNotThrow()
            throws Exception {
        File actionFile = writeActionFile("shutdown");
        assertDoesNotThrow(() -> checkForActionFile.invoke(bridge));
        assertFalse(actionFile.exists());
    }

    // ── unknown / empty commands ───────────────────────────────────────────────

    @Test
    void checkForActionFile_withUnknownCommand_deletesFileAndDoesNotThrow()
            throws Exception {
        File actionFile = writeActionFile("unknown_command_xyz");
        assertDoesNotThrow(() -> checkForActionFile.invoke(bridge));
        assertFalse(actionFile.exists(), "File must be deleted even for unknown commands");
        assertFalse(bridge.isRecentlyStarted());
    }

    @Test
    void checkForActionFile_withEmptyContent_deletesFileAndDoesNothing()
            throws Exception {
        File actionFile = writeActionFile("");
        assertDoesNotThrow(() -> checkForActionFile.invoke(bridge));
        assertFalse(actionFile.exists(), "Empty-content file must still be deleted");
        assertFalse(bridge.isRecentlyStarted());
    }

    // ── alternate action file (.jmeter_cmd) ───────────────────────────────────

    @Test
    void checkForActionFile_withAltActionFile_processesReloadCommand()
            throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        bridge.setReloadCallback(() -> called.set(true));

        File altFile = tempDir.resolve(".jmeter_cmd").toFile();
        Files.write(altFile.toPath(), "reload".getBytes(StandardCharsets.UTF_8));

        checkForActionFile.invoke(bridge);
        SwingUtilities.invokeAndWait(() -> {});

        assertTrue(called.get(), "Alternate action file '.jmeter_cmd' must also be processed");
        assertFalse(altFile.exists(), "Alternate action file must be deleted after processing");
    }

    // ── setReloadCallback ──────────────────────────────────────────────────────

    @Test
    void setReloadCallback_replacingCallback_usesLatestCallback() throws Exception {
        AtomicBoolean firstCalled  = new AtomicBoolean(false);
        AtomicBoolean secondCalled = new AtomicBoolean(false);

        bridge.setReloadCallback(() -> firstCalled.set(true));
        bridge.setReloadCallback(() -> secondCalled.set(true));

        writeActionFile("reload");
        checkForActionFile.invoke(bridge);
        SwingUtilities.invokeAndWait(() -> {});

        assertFalse(firstCalled.get(),  "First (replaced) callback must NOT be invoked");
        assertTrue(secondCalled.get(), "Second (current) callback must be invoked");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private File writeActionFile(String content) throws Exception {
        File f = tempDir.resolve(JMeterActionBridge.ACTION_FILE_NAME).toFile();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }
}
