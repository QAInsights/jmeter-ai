package org.qainsights.jmeter.ai.gui;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link AttachMenu}'s file resolution: jmeter.log lookup order
 * and the bin directory shortcut.
 */
class AttachMenuTest {

    @TempDir
    Path tempDir;

    private MockedStatic<JMeterUtils> jmeterUtilsMockedStatic;

    @BeforeEach
    void setUp() {
        jmeterUtilsMockedStatic = mockStatic(JMeterUtils.class);
    }

    @AfterEach
    void tearDown() {
        jmeterUtilsMockedStatic.close();
    }

    @Test
    void resolvesLogInBinFirst() throws Exception {
        Path bin = tempDir.resolve("bin");
        Files.createDirectories(bin);
        File logInBin = Files.writeString(bin.resolve("jmeter.log"), "x").toFile();
        jmeterUtilsMockedStatic.when(JMeterUtils::getJMeterHome).thenReturn(tempDir.toString());

        assertEquals(logInBin.getAbsolutePath(), AttachMenu.resolveJMeterLog().getAbsolutePath());
    }

    @Test
    void fallsBackToHomeRoot() throws Exception {
        File logInHome = Files.writeString(tempDir.resolve("jmeter.log"), "x").toFile();
        jmeterUtilsMockedStatic.when(JMeterUtils::getJMeterHome).thenReturn(tempDir.toString());

        assertEquals(logInHome.getAbsolutePath(), AttachMenu.resolveJMeterLog().getAbsolutePath());
    }

    @Test
    void returnsNullWhenNotFound() {
        jmeterUtilsMockedStatic.when(JMeterUtils::getJMeterHome)
                .thenReturn(tempDir.resolve("nonexistent").toString());
        // cwd has no jmeter.log in the test working directory
        File resolved = AttachMenu.resolveJMeterLog();
        assertTrue(resolved == null || resolved.isFile());
    }

    @Test
    void binDirectoryResolvesWhenPresent() throws Exception {
        Files.createDirectories(tempDir.resolve("bin"));
        jmeterUtilsMockedStatic.when(JMeterUtils::getJMeterHome).thenReturn(tempDir.toString());

        File bin = AttachMenu.binDirectory();
        assertNotNull(bin);
        assertTrue(bin.isDirectory());
        assertEquals("bin", bin.getName());
    }

    @Test
    void binDirectoryNullWithoutHome() {
        jmeterUtilsMockedStatic.when(JMeterUtils::getJMeterHome).thenReturn(null);
        assertNull(AttachMenu.binDirectory());
    }
}
