package org.qainsights.jmeter.ai.apple;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qainsights.jmeter.ai.cli.CliProcessResult;
import org.qainsights.jmeter.ai.cli.CliProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AppleFoundationHelperTest {
    @TempDir
    Path tempDir;

    @Test
    void unsupportedPlatformDoesNotExtractHelper() {
        assertTrue(AppleFoundationHelper.resolveExecutable("Linux", "x86_64", "", tempDir).isEmpty());
        assertFalse(Files.exists(tempDir.resolve(".jmeter-ai")));
    }

    @Test
    void configuredHelperPathOverridesPlatformDetection() {
        Path configured = tempDir.resolve("custom-helper");
        assertEquals(Optional.of(configured),
                AppleFoundationHelper.resolveExecutable("Linux", "x86_64", configured.toString(), tempDir));
    }

    @Test
    void statusParserHandlesAvailableAndKnownUnavailableReasons() {
        assertTrue(AppleFoundationHelper.parseStatus("{\"available\":true}\n", true).isAvailable());
        for (String reason : List.of("deviceNotEligible", "appleIntelligenceNotEnabled", "modelNotReady")) {
            AppleFoundationHelper.Status status = AppleFoundationHelper.parseStatus(
                    "{\"available\":false,\"reason\":\"" + reason + "\"}\n", true);
            assertFalse(status.isAvailable());
            assertEquals(reason, status.getReason());
        }
    }

    @Test
    void statusParserTreatsGarbageAndFailedCommandsAsUnknown() {
        assertEquals("unknown", AppleFoundationHelper.parseStatus("not json\n", true).getReason());
        assertEquals("unknown", AppleFoundationHelper.parseStatus(
                "{\"available\":true}\n", false).getReason());
        assertEquals("unknown", AppleFoundationHelper.parseStatus(
                "{\"available\":false,\"reason\":\"unexpected\"}\n", true).getReason());
    }

    @Test
    void unsupportedStatusDoesNotSpawnHelper() {
        AtomicInteger runs = new AtomicInteger();
        CliProcessRunner runner = new CliProcessRunner() {
            @Override
            public CliProcessResult run(List<String> command, String stdin, Duration timeout) {
                runs.incrementAndGet();
                return CliProcessResult.of(0, "{\"available\":true}");
            }
        };

        AppleFoundationHelper.Status status = AppleFoundationHelper.probeStatus(
                runner, Optional.of(tempDir.resolve("helper")), false);

        assertFalse(status.isAvailable());
        assertEquals("unsupportedPlatform", status.getReason());
        assertEquals(0, runs.get());
    }
}
