package org.qainsights.jmeter.ai.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserPropertiesWriterTest {
    @TempDir
    Path tempDir;

    @org.junit.jupiter.api.BeforeEach
    void initializeJMeterProperties() {
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(
                "src/test/resources/org/apache/jmeter/jmeter.properties");
    }

    @Test
    void replacesTheLastMatchingKeyAndPreservesCommentsAndOrder() throws Exception {
        Path file = tempDir.resolve("user.properties");
        Files.writeString(file,
                "# first comment\nanthropic.api.key=old\n# keep this\nother=1\nanthropic.api.key:last",
                StandardCharsets.UTF_8);

        UserPropertiesWriter.apply(file, values("anthropic.api.key", "new"));

        assertEquals("# first comment\nanthropic.api.key=old\n# keep this\nother=1\nanthropic.api.key:new",
                Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void leavesCommentedKeysAndAppendsMissingValuesUnderOneComment() throws Exception {
        Path file = tempDir.resolve("user.properties");
        Files.writeString(file, "#anthropic.api.key=old\nother=1\n", StandardCharsets.UTF_8);
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("anthropic.api.key", "new");
        values.put("jmeter.ai.service.type", "anthropic");

        UserPropertiesWriter.apply(file, values);

        String output = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(output.startsWith("#anthropic.api.key=old\nother=1\n"
                + "# Feather Wand (added by Connect a provider)\n"));
        assertTrue(output.endsWith("anthropic.api.key=new\njmeter.ai.service.type=anthropic"));
        assertEquals(1, output.split("# Feather Wand \\(added by Connect a provider\\)", -1).length - 1);
    }

    @Test
    void addsANewlineBeforeAppendedCommentWhenExistingFileHasNoTrailingNewline() throws Exception {
        Path file = tempDir.resolve("user.properties");
        Files.writeString(file, "existing=true", StandardCharsets.UTF_8);

        UserPropertiesWriter.apply(file, values("openai.api.key", "secret"));

        assertTrue(Files.readString(file).startsWith(
                "existing=true" + System.lineSeparator()
                        + "# Feather Wand (added by Connect a provider)"));
    }

    @Test
    void createsMissingFile() throws Exception {
        Path file = tempDir.resolve("new").resolve("user.properties");

        UserPropertiesWriter.apply(file, values("openai.api.key", "secret"));

        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file).contains("openai.api.key=secret"));
    }

    @Test
    void preservesCrlfSeparators() throws Exception {
        Path file = tempDir.resolve("user.properties");
        Files.writeString(file, "first=1\r\nopenai.api.key=old\r\nlast=3\r\n", StandardCharsets.UTF_8);

        UserPropertiesWriter.apply(file, values("openai.api.key", "new"));

        String output = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("first=1\r\nopenai.api.key=new\r\nlast=3\r\n", output);
        assertFalse(output.replace("\r\n", "").contains("\n"));
    }

    @Test
    void rejectsCarriageReturnsAndLineFeedsInValues() {
        Path file = tempDir.resolve("user.properties");

        assertThrows(IllegalArgumentException.class,
                () -> UserPropertiesWriter.apply(file, values("key", "bad\nvalue")));
        assertThrows(IllegalArgumentException.class,
                () -> UserPropertiesWriter.apply(file, values("key", "bad\rvalue")));
        assertFalse(Files.exists(file));
    }

    @Test
    void escapesBackslashesInPropertiesFileAndSetsRawRuntimeValue() throws Exception {
        Path file = tempDir.resolve("user.properties");
        String rawValue = "C:\\models\\ollama";

        UserPropertiesWriter.apply(file, values("ollama.host", rawValue));

        assertTrue(Files.readString(file).contains("ollama.host=C:\\\\models\\\\ollama"));
        assertEquals(rawValue, org.apache.jmeter.util.JMeterUtils.getProperty("ollama.host"));
    }

    private static LinkedHashMap<String, String> values(String key, String value) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(key, value);
        return values;
    }
}
