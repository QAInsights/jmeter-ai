package org.qainsights.jmeter.ai.fix;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.qainsights.jmeter.ai.agent.jmeter.ElementLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PlanBackupTest {
    @TempDir
    Path directory;

    private String originalBaseDirectory;
    private String priorBaseDirectory;

    @AfterEach
    void restoreFileServerBaseDirectory() {
        if (priorBaseDirectory != null) {
            FileServer.getFileServer().setBasedir(priorBaseDirectory);
        }
    }

    @Test
    void restoreResetsFileServerBaseDirectoryCapturedAtBackup() throws Exception {
        Path originalBase = Files.createDirectories(directory.resolve("original-base"));
        originalBaseDirectory = originalBase.toString();
        FileServer fileServer = FileServer.getFileServer();
        priorBaseDirectory = fileServer.getBaseDir();
        fileServer.setBasedir(originalBaseDirectory);
        PlanBackup backups = new PlanBackup(PlanBackupTest::plan, file -> {
            fileServer.setBaseForScript(file);
            return true;
        }, directory.resolve("fix-backups"),
                (tree, file) -> Files.writeString(file.toPath(), "snapshot"));

        File backup = backups.backup();
        try {
            fileServer.setBasedir(Files.createDirectories(directory.resolve("other-base")).toString());
            backups.restore(backup);
            assertEquals(originalBaseDirectory, fileServer.getBaseDir());
        } finally {
            backups.unpin(backup);
        }
    }

    @Test
    void keepsPinnedOverallBackupsAndDiscardsRoundFilesSeparately() throws Exception {
        Path backupDirectory = directory.resolve("backups");
        PlanBackup backups = new PlanBackup(PlanBackupTest::plan, file -> true, backupDirectory,
                (tree, file) -> Files.writeString(file.toPath(), "snapshot"));
        List<File> overall = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            overall.add(backups.backup());
        }
        File round = null;
        try {
            Path unrelated = Files.writeString(backupDirectory.resolve("other.jmx"), "keep");
            round = backups.roundBackup();

            assertTrue(overall.get(0).isFile());
            assertTrue(round.toPath().startsWith(backupDirectory.resolve("rounds")));
            backups.unpin(overall.get(0));

            assertFalse(overall.get(0).exists());
            assertTrue(round.exists());
            assertTrue(Files.exists(unrelated));
            backups.discard(round);
            assertFalse(round.exists());
        } finally {
            if (round != null) {
                backups.discard(round);
            }
            overall.forEach(backups::unpin);
        }
    }

    @Test
    void restoresSafetySnapshotWhenLoaderClearsPlanThenThrows() throws Exception {
        Path backupDirectory = directory.resolve("fix-backups");
        File requestedBackup = Files.writeString(directory.resolve("requested.jmx"), "requested").toFile();
        java.util.concurrent.atomic.AtomicReference<String> livePlan =
                new java.util.concurrent.atomic.AtomicReference<>("original plan");
        List<File> loadedFiles = new ArrayList<>();
        ElementLoader loader = new ElementLoader() {
            @Override
            public boolean load(File file) throws IllegalUserActionException {
                loadedFiles.add(file);
                if (file.equals(requestedBackup)) {
                    livePlan.set("");
                    throw new IllegalStateException("insertion failed");
                }
                livePlan.set(read(file));
                return true;
            }

            @Override
            public void validate(File file) {
            }

            private String read(File file) {
                try {
                    return Files.readString(file.toPath());
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        PlanBackup backups = new PlanBackup(PlanBackupTest::plan, loader, backupDirectory,
                (tree, file) -> Files.writeString(file.toPath(), "original plan"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> backups.restore(requestedBackup));

        assertTrue(failure.getMessage().contains("Could not restore"));
        assertEquals("original plan", livePlan.get());
        assertEquals(2, loadedFiles.size());
        assertEquals(requestedBackup, loadedFiles.get(0));
        assertTrue(loadedFiles.get(1).toPath().startsWith(backupDirectory.resolve("rounds")));
        try (var files = Files.list(backupDirectory.resolve("rounds"))) {
            assertEquals(0, files.count());
        }
    }

    private static HashTree plan() {
        HashTree tree = new ListedHashTree();
        tree.add(new TestPlan("Test Plan")).add(new DebugSampler());
        return tree;
    }
}
