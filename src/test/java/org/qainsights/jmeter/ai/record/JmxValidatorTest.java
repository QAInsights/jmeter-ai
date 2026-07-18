package org.qainsights.jmeter.ai.record;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JmxValidator}.
 */
class JmxValidatorTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    @Test
    void should_throwException_when_fileDoesNotExist() {
        JmxValidator validator = new JmxValidator();
        File missing = tempDir.resolve("missing.jmx").toFile();

        assertThrows(RecordingException.class, () -> validator.validate(missing));
    }

    @Test
    void should_throwException_when_fileIsEmpty() throws Exception {
        JmxValidator validator = new JmxValidator();
        File emptyFile = tempDir.resolve("empty.jmx").toFile();
        Files.writeString(emptyFile.toPath(), "");

        assertThrows(RecordingException.class, () -> validator.validate(emptyFile));
    }

    @Test
    void should_throwException_when_invalidJmxXml() throws Exception {
        JmxValidator validator = new JmxValidator();
        File badXml = tempDir.resolve("bad.jmx").toFile();
        Files.writeString(badXml.toPath(), "<jmeterTestPlan><invalid></jmeterTestPlan>");

        assertThrows(RecordingException.class, () -> validator.validate(badXml));
    }
}
