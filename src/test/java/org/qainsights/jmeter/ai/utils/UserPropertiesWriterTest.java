package org.qainsights.jmeter.ai.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserPropertiesWriterTest {
    @TempDir
    Path tempDir;
    private final Map<String, String> previousProperties = new LinkedHashMap<>();

    @org.junit.jupiter.api.BeforeEach
    void initializeJMeterProperties() {
        if (org.apache.jmeter.util.JMeterUtils.getJMeterProperties() == null) {
            org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(
                    "src/test/resources/org/apache/jmeter/jmeter.properties");
        }
    }

    @AfterEach
    void restoreRuntimeProperties() {
        var properties = org.apache.jmeter.util.JMeterUtils.getJMeterProperties();
        if (properties == null) {
            return;
        }
        previousProperties.forEach((key, value) -> {
            if (value == null) {
                properties.remove(key);
            } else {
                org.apache.jmeter.util.JMeterUtils.setProperty(key, value);
            }
        });
    }

    private void apply(Path file, LinkedHashMap<String, String> values) throws java.io.IOException {
        values.keySet().forEach(key -> previousProperties.putIfAbsent(
                key, org.apache.jmeter.util.JMeterUtils.getProperty(key)));
        UserPropertiesWriter.apply(file, values);
    }

    @Test
    void replacesTheLastMatchingKeyAndPreservesCommentsAndOrder() throws Exception {
        Path file = tempDir.resolve("user.properties");
        Files.writeString(file,
                "# first comment\nanthropic.api.key=old\n# keep this\nother=1\nanthropic.api.key:last",
                StandardCharsets.UTF_8);

        apply(file, values("anthropic.api.key", "new"));

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

        apply(file, values);

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

        apply(file, values("openai.api.key", "secret"));

        assertTrue(Files.readString(file).startsWith(
                "existing=true" + System.lineSeparator()
                        + "# Feather Wand (added by Connect a provider)"));
    }

    @Test
    void createsMissingFile() throws Exception {
        Path file = tempDir.resolve("new").resolve("user.properties");

        apply(file, values("openai.api.key", "secret"));

        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file).contains("openai.api.key=secret"));
    }

    @Test
    void preservesExistingPosixPermissions() throws Exception {
        assumePosix();
        Path file = tempDir.resolve("user.properties");
        Files.writeString(file, "openai.api.key=old\n", StandardCharsets.UTF_8);
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(file, permissions);

        apply(file, values("openai.api.key", "new"));

        assertEquals(permissions, Files.getPosixFilePermissions(file));
    }

    @Test
    void createsNewPosixFilesWithOwnerOnlyPermissions() throws Exception {
        assumePosix();
        Path file = tempDir.resolve("private").resolve("user.properties");

        apply(file, values("openai.api.key", "secret"));

        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(file));
    }

    @Test
    void preservesCrlfSeparators() throws Exception {
        Path file = tempDir.resolve("user.properties");
        Files.writeString(file, "first=1\r\nopenai.api.key=old\r\nlast=3\r\n", StandardCharsets.UTF_8);

        apply(file, values("openai.api.key", "new"));

        String output = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("first=1\r\nopenai.api.key=new\r\nlast=3\r\n", output);
        assertFalse(output.replace("\r\n", "").contains("\n"));
    }

    @Test
    void rejectsCarriageReturnsAndLineFeedsInValues() {
        Path file = tempDir.resolve("user.properties");

        assertThrows(IllegalArgumentException.class,
                () -> apply(file, values("key", "bad\nvalue")));
        assertThrows(IllegalArgumentException.class,
                () -> apply(file, values("key", "bad\rvalue")));
        assertFalse(Files.exists(file));
    }

    @Test
    void escapesBackslashesInPropertiesFileAndSetsRawRuntimeValue() throws Exception {
        Path file = tempDir.resolve("user.properties");
        String rawValue = "C:\\models\\ollama";

        apply(file, values("ollama.host", rawValue));

        assertTrue(Files.readString(file).contains("ollama.host=C:\\\\models\\\\ollama"));
        assertEquals(rawValue, org.apache.jmeter.util.JMeterUtils.getProperty("ollama.host"));
    }

    @Test
    void replacesWhitespaceDelimitedPropertiesWithoutMatchingLongerKeys() throws Exception {
        Path file = tempDir.resolve("user.properties");
        Files.writeString(file, "openai.api.key old\nopenai.api.keyX=keep\n", StandardCharsets.UTF_8);

        apply(file, values("openai.api.key", "new"));

        assertEquals("openai.api.key new\nopenai.api.keyX=keep\n",
                Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void updatesReadOnlyPosixTargetAndPreservesItsPermissions() throws Exception {
        assumePosix();
        Path file = tempDir.resolve("user.properties");
        Files.writeString(file, "openai.api.key=old\n", StandardCharsets.UTF_8);
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("r--r--r--");
        Files.setPosixFilePermissions(file, permissions);

        apply(file, values("openai.api.key", "new"));

        assertTrue(Files.readString(file).contains("openai.api.key=new"));
        assertEquals(permissions, Files.getPosixFilePermissions(file));
    }

    @Test
    void writesThroughSymbolicLinkToRealFile() throws Exception {
        assumePosix();
        Path target = tempDir.resolve("real.properties");
        Path link = tempDir.resolve("linked.properties");
        Files.writeString(target, "openai.api.key=old\n", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "Symbolic links are unavailable");
        }

        apply(link, values("openai.api.key", "new"));

        assertTrue(Files.isSymbolicLink(link));
        assertTrue(Files.readString(target).contains("openai.api.key=new"));
    }

    private static LinkedHashMap<String, String> values(String key, String value) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(key, value);
        return values;
    }

    private void assumePosix() {
        assumeTrue(Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null);
    }
}
