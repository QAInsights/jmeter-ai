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
