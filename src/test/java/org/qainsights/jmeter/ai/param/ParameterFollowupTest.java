package org.qainsights.jmeter.ai.param;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ParameterFollowupTest {
    @TempDir Path directory;

    @Test
    void expressionsAndUnicodeNeighborsRemainProtectedAndHttpMetadataIsNotRewritten() {
        var http = new org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy();
        http.addArgument("id", "12345");
        http.addArgument("mixed", "é12345 12345９ ${__groovy('${inner}' + '12345')} 12345");
        http.setDomain("12345");
        var node = new org.apache.jmeter.gui.tree.JMeterTreeNode(http, null);
        var candidate = ParameterScanner.scan(node).stream().filter(c -> c.value.equals("12345")).findFirst().orElseThrow();
        assertEquals(2, ParameterReplacement.replace(node, List.of(candidate), true));
        assertEquals("12345", http.getDomain());
        assertEquals("é12345 12345９ ${__groovy('${inner}' + '12345')} ${id}",
                http.getArguments().getArgument(1).getValue());
        String json = "{\"value\":${__groovy('}')},\"id\":12345}";
        assertDoesNotThrow(() -> new com.fasterxml.jackson.databind.ObjectMapper().readTree(ParameterJson.mask(json).text()));
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                () -> ParameterJson.mask("${".repeat(10000)));
    }

    @Test
    void extractPreviewAgreesWithExpressionAwareReplacement() {
        var group = new org.apache.jmeter.gui.tree.JMeterTreeNode(new org.apache.jmeter.threads.ThreadGroup(), null);
        var source = new org.apache.jmeter.gui.tree.JMeterTreeNode(new org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy(), null);
        group.add(source);
        var http = new org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy();
        http.addArgument("id", "${__groovy('${inner}' + '12345')}");
        var target = new org.apache.jmeter.gui.tree.JMeterTreeNode(http, null);
        group.add(target);
        assertTrue(org.qainsights.jmeter.ai.extract.ExtractPlacement.laterUses(group, source, "12345").isEmpty());
        assertEquals(0, org.qainsights.jmeter.ai.extract.ExtractPlacement.replace(List.of(target), "12345", "id"));
        http.addArgument("literal", "12345");
        assertEquals(List.of(target), org.qainsights.jmeter.ai.extract.ExtractPlacement.laterUses(group, source, "12345"));
    }

    @Test
    void blankRecordsReceiveColumnsAndInsertedHeaderKeepsLineEndings() throws Exception {
        Path csv = directory.resolve("blank.csv");
        Files.writeString(csv, "old\r\n\r\n");
        ParameterCsv.write(csv, ',', List.of("new"), List.of("value"), List.of("old"));
        assertEquals("old,\"new\"\r\n,\"value\"\r\n", Files.readString(csv));
        Files.writeString(csv, "first\rsecond\r");
        ParameterCsv.write(csv, ',', List.of("new"), List.of("value"), List.of("old"), false);
        assertTrue(Files.readString(csv).startsWith("\"old\",\"new\"\rfirst,"));
        Files.writeString(csv, "old,other\n\n");
        assertThrows(java.io.IOException.class, () -> ParameterCsv.write(csv, ',',
                List.of("new"), List.of("value"), List.of("old", "other")));
        assertEquals("old,other\n\n", Files.readString(csv));
    }

    @Test
    void errorsRollBackCsvBytesAndPermissions() throws Exception {
        Path csv = directory.resolve("data.csv");
        Files.writeString(csv, "old\nvalue\n");
        var permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(csv, permissions);
        assertThrows(AssertionError.class, () -> ParameterTransaction.apply(csv, () -> {
            ParameterCsv.write(csv, ',', List.of("new"), List.of("value"), List.of("old"));
            assertEquals(permissions, Files.getPosixFilePermissions(csv));
            return 0;
        }, () -> { throw new AssertionError("plan error"); }));
        assertEquals("old\nvalue\n", Files.readString(csv));
        assertEquals(permissions, Files.getPosixFilePermissions(csv));
    }

    @Test
    void backupCleanupFailureDoesNotHideSuccessfulCommit() throws Exception {
        Path csv = directory.resolve("cleanup.csv");
        Files.writeString(csv, "old");
        try (org.mockito.MockedStatic<Files> files = org.mockito.Mockito.mockStatic(Files.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            files.when(() -> Files.deleteIfExists(org.mockito.ArgumentMatchers.argThat(
                    path -> path != null && path.getFileName().toString().startsWith(".parameterize-backup-"))))
                    .thenThrow(new java.io.IOException("cleanup blocked"));
            assertEquals(7, ParameterTransaction.apply(csv, () -> {
                Files.writeString(csv, "new");
                return 0;
            }, () -> 7));
        }
        assertEquals("new", Files.readString(csv));
    }

    @Test
    void csvCannotReplaceTheSavedPlanOrItsAliases() throws Exception {
        Path plan = directory.resolve("test.jmx");
        Files.writeString(plan, "saved plan");
        assertThrows(IllegalArgumentException.class,
                () -> ParameterPlacement.validateTarget(directory.resolve("sub/../test.jmx"), plan.toString()));
        Path alias = directory.resolve("alias.csv");
        Files.createLink(alias, plan);
        assertThrows(IllegalArgumentException.class, () -> ParameterPlacement.validateTarget(alias, plan.toString()));
        Files.delete(alias);
        Files.createSymbolicLink(alias, plan);
        assertThrows(IllegalArgumentException.class, () -> ParameterPlacement.validateTarget(alias, null));
        ParameterPlacement.validateTarget(directory.resolve("safe.csv"), plan.toString());
        assertEquals("saved plan", Files.readString(plan));
    }
}
