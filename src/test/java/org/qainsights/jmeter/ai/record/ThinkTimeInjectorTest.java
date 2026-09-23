package org.qainsights.jmeter.ai.record;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link ThinkTimeInjector}.
 */
class ThinkTimeInjectorTest {

    @TempDir
    Path tempDir;

    @Test
    void should_injectThinkTime_when_validJmxAndStepMarkersExist() throws Exception {
        String originalXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
              <hashTree>
                <ThreadGroup>
                  <hashTree>
                    <TransactionController testname="Step 1" enabled="true"/>
                    <hashTree>
                      <HTTPSamplerProxy testname="Request 1"/>
                      <hashTree/>
                    </hashTree>
                    <TransactionController testname="Step 2" enabled="true"/>
                    <hashTree/>
                  </hashTree>
                </ThreadGroup>
              </hashTree>
            </jmeterTestPlan>
            """;
        
        File jmxFile = tempDir.resolve("test.jmx").toFile();
        Files.writeString(jmxFile.toPath(), originalXml);

        List<StepMarker> markers = List.of(
            new StepMarker("Step 1", "start", 1000L),
            new StepMarker("Step 1", "end", 2000L),
            new StepMarker("Step 2", "start", 5000L),
            new StepMarker("Step 2", "end", 6000L)
        );

        ThinkTimeInjector injector = new ThinkTimeInjector();
        injector.injectThinkTimes(jmxFile, markers);

        String updatedXml = Files.readString(jmxFile.toPath());
        assertTrue(updatedXml.contains("TestAction"));
        assertTrue(updatedXml.contains("Flow Control Action"));
        assertTrue(updatedXml.contains("ActionProcessor.duration"));
        assertTrue(updatedXml.contains("3000")); // gap is 5000 - 2000 = 3000
    }

    @Test
    void should_fallBackToDefaults_when_thinkTimePropertiesAreMalformed() throws Exception {
        try (MockedStatic<AiConfig> aiConfig = mockStatic(AiConfig.class)) {
            aiConfig.when(() -> AiConfig.getProperty(anyString(), anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            aiConfig.when(() -> AiConfig.getProperty(eq("jmeter.ai.record.think_time.scale"), anyString()))
                    .thenReturn("abc");

            String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
                  <hashTree>
                    <ThreadGroup>
                      <hashTree>
                        <TransactionController testname="Step 1" enabled="true"/>
                        <hashTree>
                          <HTTPSamplerProxy testname="Request 1"/>
                          <hashTree/>
                        </hashTree>
                        <TransactionController testname="Step 2" enabled="true"/>
                        <hashTree/>
                      </hashTree>
                    </ThreadGroup>
                  </hashTree>
                </jmeterTestPlan>
                """;
            File jmxFile = tempDir.resolve("malformed-props.jmx").toFile();
            Files.writeString(jmxFile.toPath(), xml);

            List<StepMarker> markers = List.of(
                new StepMarker("Step 1", "end", 2000L),
                new StepMarker("Step 2", "start", 5000L)
            );

            // must not throw: "abc" falls back to scale 1.0, so the 3000ms gap is injected
            assertDoesNotThrow(() -> new ThinkTimeInjector().injectThinkTimes(jmxFile, markers));
            String updatedXml = Files.readString(jmxFile.toPath());
            assertTrue(updatedXml.contains("3000"));
        }
    }

    @Test
    void should_clampToDefaultMax_when_gapExceedsMax() throws Exception {
        try (MockedStatic<AiConfig> aiConfig = mockStatic(AiConfig.class)) {
            aiConfig.when(() -> AiConfig.getProperty(anyString(), anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(1));

            File jmxFile = writeTwoStepPlan("clamp-max.jmx");
            List<StepMarker> markers = List.of(
                new StepMarker("Step 1", "end", 2000L),
                new StepMarker("Step 2", "start", 602000L) // 600s gap, far above 10s default max
            );

            new ThinkTimeInjector().injectThinkTimes(jmxFile, markers);

            assertEquals("10000", extractDuration(jmxFile));
        }
    }

    @Test
    void should_clampToConfiguredMax_when_gapTimesScaleExceedsMax() throws Exception {
        try (MockedStatic<AiConfig> aiConfig = mockStatic(AiConfig.class)) {
            aiConfig.when(() -> AiConfig.getProperty(anyString(), anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            aiConfig.when(() -> AiConfig.getProperty(eq("jmeter.ai.record.think_time.scale"), anyString()))
                    .thenReturn("2.0");
            aiConfig.when(() -> AiConfig.getProperty(eq("jmeter.ai.record.think_time.max.ms"), anyString()))
                    .thenReturn("4000");

            File jmxFile = writeTwoStepPlan("clamp-configured-max.jmx");
            List<StepMarker> markers = List.of(
                new StepMarker("Step 1", "end", 2000L),
                new StepMarker("Step 2", "start", 5000L) // 3000 * 2.0 = 6000 > 4000
            );

            new ThinkTimeInjector().injectThinkTimes(jmxFile, markers);

            assertEquals("4000", extractDuration(jmxFile));
        }
    }

    @Test
    void should_clampToConfiguredMin_when_gapIsBelowMin() throws Exception {
        try (MockedStatic<AiConfig> aiConfig = mockStatic(AiConfig.class)) {
            aiConfig.when(() -> AiConfig.getProperty(anyString(), anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            aiConfig.when(() -> AiConfig.getProperty(eq("jmeter.ai.record.think_time.min.ms"), anyString()))
                    .thenReturn("1500");

            File jmxFile = writeTwoStepPlan("clamp-min.jmx");
            List<StepMarker> markers = List.of(
                new StepMarker("Step 1", "end", 2000L),
                new StepMarker("Step 2", "start", 2100L) // 100ms gap, below 1500 min
            );

            new ThinkTimeInjector().injectThinkTimes(jmxFile, markers);

            assertEquals("1500", extractDuration(jmxFile));
        }
    }

    @Test
    void should_skipInjection_when_gapIsZeroAndMinIsDefault() throws Exception {
        try (MockedStatic<AiConfig> aiConfig = mockStatic(AiConfig.class)) {
            aiConfig.when(() -> AiConfig.getProperty(anyString(), anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(1));

            File jmxFile = writeTwoStepPlan("zero-gap.jmx");
            List<StepMarker> markers = List.of(
                new StepMarker("Step 1", "end", 2000L),
                new StepMarker("Step 2", "start", 2000L)
            );

            new ThinkTimeInjector().injectThinkTimes(jmxFile, markers);

            assertFalse(Files.readString(jmxFile.toPath()).contains("TestAction"));
        }
    }

    @Test
    void should_fallBackToDefaultBounds_when_minAndMaxPropertiesAreMalformed() throws Exception {
        try (MockedStatic<AiConfig> aiConfig = mockStatic(AiConfig.class)) {
            aiConfig.when(() -> AiConfig.getProperty(anyString(), anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            aiConfig.when(() -> AiConfig.getProperty(eq("jmeter.ai.record.think_time.min.ms"), anyString()))
                    .thenReturn("not-a-number");
            aiConfig.when(() -> AiConfig.getProperty(eq("jmeter.ai.record.think_time.max.ms"), anyString()))
                    .thenReturn("12.5");

            File jmxFile = writeTwoStepPlan("malformed-bounds.jmx");
            List<StepMarker> markers = List.of(
                new StepMarker("Step 1", "end", 2000L),
                new StepMarker("Step 2", "start", 62000L) // 60s gap, clamped by default max 10000
            );

            assertDoesNotThrow(() -> new ThinkTimeInjector().injectThinkTimes(jmxFile, markers));

            assertEquals("10000", extractDuration(jmxFile));
        }
    }

    private File writeTwoStepPlan(String fileName) throws Exception {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
              <hashTree>
                <ThreadGroup>
                  <hashTree>
                    <TransactionController testname="Step 1" enabled="true"/>
                    <hashTree>
                      <HTTPSamplerProxy testname="Request 1"/>
                      <hashTree/>
                    </hashTree>
                    <TransactionController testname="Step 2" enabled="true"/>
                    <hashTree/>
                  </hashTree>
                </ThreadGroup>
              </hashTree>
            </jmeterTestPlan>
            """;
        File jmxFile = tempDir.resolve(fileName).toFile();
        Files.writeString(jmxFile.toPath(), xml);
        return jmxFile;
    }

    private static String extractDuration(File jmxFile) throws Exception {
        String xml = Files.readString(jmxFile.toPath());
        Matcher m = Pattern.compile("<stringProp name=\"ActionProcessor.duration\">(\\d+)</stringProp>").matcher(xml);
        assertTrue(m.find(), "expected an injected ActionProcessor.duration in: " + xml);
        String duration = m.group(1);
        assertFalse(m.find(), "expected exactly one injected think time");
        return duration;
    }
}
