package org.qainsights.jmeter.ai.record;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PlaywrightBrowserInstaller}.
 */
class PlaywrightBrowserInstallerTest {

    @Test
    void should_triggerCallbacks_when_installationSucceeds() throws Exception {
        Process mockProcess = mock(Process.class);
        InputStream inputStream = new ByteArrayInputStream("Downloading browser...\nSuccess!\n".getBytes());
        when(mockProcess.getInputStream()).thenReturn(inputStream);
        when(mockProcess.waitFor()).thenReturn(0);

        PlaywrightBrowserInstaller installer = new PlaywrightBrowserInstaller() {
            @Override
            Process startInstallProcess() {
                return mockProcess;
            }
        };

        List<String> output = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        installer.install(
            output::add,
            () -> {
                success.set(true);
                latch.countDown();
            },
            err -> latch.countDown()
        );

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(success.get());
        assertEquals(2, output.size());
        assertEquals("Downloading browser...", output.get(0));
        assertEquals("Success!", output.get(1));
    }

    @Test
    void should_triggerErrorCallback_when_installationFails() throws Exception {
        Process mockProcess = mock(Process.class);
        InputStream inputStream = new ByteArrayInputStream("Error details\n".getBytes());
        when(mockProcess.getInputStream()).thenReturn(inputStream);
        when(mockProcess.waitFor()).thenReturn(1);

        PlaywrightBrowserInstaller installer = new PlaywrightBrowserInstaller() {
            @Override
            Process startInstallProcess() {
                return mockProcess;
            }
        };

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> caughtError = new AtomicReference<>();

        installer.install(
            null,
            () -> latch.countDown(),
            err -> {
                caughtError.set(err);
                latch.countDown();
            }
        );

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(caughtError.get());
        assertTrue(caughtError.get().getMessage().contains("failed with exit code 1"));
    }
}
