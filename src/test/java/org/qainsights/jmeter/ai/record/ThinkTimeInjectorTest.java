package org.qainsights.jmeter.ai.record;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
