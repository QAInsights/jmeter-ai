package org.qainsights.jmeter.ai.fix;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.services.FileServer;
import org.apache.jorphan.collections.HashTree;
import org.qainsights.jmeter.ai.agent.jmeter.EdtExecutor;
import org.qainsights.jmeter.ai.agent.jmeter.ElementLoader;
import org.qainsights.jmeter.ai.agent.jmeter.ElementSaver;

/** Saves and restores plan snapshots without changing the GUI's associated file on backup. */
public class PlanBackup implements FixLoop.BackupStore {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int RETENTION = 10;
    private static final Set<String> PINNED_BACKUPS = ConcurrentHashMap.newKeySet();

    private final SnapshotSupplier snapshotSupplier;
    private final ElementLoader loader;
    private final TreeWriter treeWriter;
    private final Path directory;
    private final Map<String, PlanState> states = new HashMap<>();

    public PlanBackup() {
        this(PlanBackup::liveSnapshot, ElementLoader.live(),
                Path.of(System.getProperty("user.home"), ".jmeter-ai", "fix-backups"),
                ElementSaver::writeTree);
    }

    public PlanBackup(Supplier<HashTree> treeSupplier, ElementLoader loader, Path directory) {
        this(treeSupplier, loader, directory, ElementSaver::writeTree);
    }

    PlanBackup(Supplier<HashTree> treeSupplier, ElementLoader loader, Path directory, TreeWriter treeWriter) {
        this(() -> new Snapshot(treeSupplier.get(), captureState(null)),
                loader, directory, treeWriter);
    }

    private PlanBackup(SnapshotSupplier snapshotSupplier, ElementLoader loader, Path directory, TreeWriter treeWriter) {
        this.snapshotSupplier = snapshotSupplier;
        this.loader = loader;
        this.treeWriter = treeWriter;
        this.directory = directory;
    }

    @Override
    public synchronized File backup() {
        return saveSnapshot(directory, "fix-", true);
    }

    @Override
    public synchronized File roundBackup() {
        return saveSnapshot(directory.resolve("rounds"), "round-", false);
    }

    private File saveSnapshot(Path targetDirectory, String prefix, boolean pin) {
        Snapshot snapshot = snapshotSupplier.get();
        if (snapshot == null || snapshot.tree == null || snapshot.tree.size() == 0) {
            throw new IllegalStateException("No test plan is currently open.");
        }
        File file = null;
        try {
            Files.createDirectories(targetDirectory);
            file = nextFile(targetDirectory, prefix);
            treeWriter.write(snapshot.tree, file);
        } catch (IOException e) {
            deletePartial(file, e);
            throw new IllegalStateException("Could not back up the test plan.", e);
        } catch (RuntimeException e) {
            deletePartial(file, e);
            throw e;
        }
        String backupKey = key(file);
        states.put(backupKey, snapshot.state);
        if (pin) {
            PINNED_BACKUPS.add(backupKey);
            try {
                pruneOldBackups();
            } catch (IOException e) {
                PINNED_BACKUPS.remove(backupKey);
                states.remove(backupKey);
                deletePartial(file, e);
                throw new IllegalStateException("Could not prune old test plan backups.", e);
            }
        }
        return file;
    }

    @Override
    public synchronized void restore(File file) {
        if (file == null) {
            throw new IllegalArgumentException("backup file must not be null");
        }
        try {
            if (!loader.load(file)) {
                throw new IllegalStateException("No live JMeter GUI available to restore the plan.");
            }
        } catch (IOException | IllegalUserActionException e) {
            throw new IllegalStateException("Could not restore the test plan backup.", e);
        }
        PlanState state = states.get(key(file));
        if (state != null) {
            EdtExecutor.swing().run(() -> restoreState(state));
        }
    }

    @Override
    public synchronized void discard(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
            states.remove(key(file));
        } catch (IOException e) {
            throw new IllegalStateException("Could not discard the round backup.", e);
        }
    }

    /** Releases a result-card backup so normal retention can remove it. */
    public synchronized void unpin(File file) {
        if (file == null) {
            return;
        }
        PINNED_BACKUPS.remove(key(file));
        try {
            pruneOldBackups();
        } catch (IOException e) {
            throw new IllegalStateException("Could not prune old test plan backups.", e);
        }
    }

    private File nextFile(Path targetDirectory, String prefix) {
        String stem = prefix + LocalDateTime.now().format(STAMP);
        Path path = targetDirectory.resolve(stem + ".jmx");
        int suffix = 2;
        while (Files.exists(path)) {
            path = targetDirectory.resolve(stem + "-" + suffix++ + ".jmx");
        }
        return path.toFile();
    }

    private void pruneOldBackups() throws IOException {
        File[] files = directory.toFile().listFiles((dir, name) ->
                name.startsWith("fix-") && name.endsWith(".jmx"));
        if (files == null) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long pinnedCount = Arrays.stream(files).filter(file -> PINNED_BACKUPS.contains(key(file))).count();
        int unpinnedLimit = (int) Math.max(0, RETENTION - pinnedCount);
        int retainedUnpinned = 0;
        for (File file : files) {
            if (PINNED_BACKUPS.contains(key(file))) {
                continue;
            }
            if (retainedUnpinned++ < unpinnedLimit) {
                continue;
            }
            Files.deleteIfExists(file.toPath());
            states.remove(key(file));
        }
    }

    private static Snapshot liveSnapshot() {
        AtomicReference<Snapshot> snapshot = new AtomicReference<>();
        EdtExecutor.swing().run(() -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null || gui.getTreeModel() == null) {
                snapshot.set(new Snapshot(null, captureState(gui)));
                return;
            }
            gui.updateCurrentNode();
            JMeterTreeModel model = gui.getTreeModel();
            HashTree tree = model == null ? null : model.getTestPlan();
            HashTree clone = tree == null ? null : (HashTree) tree.clone();
            snapshot.set(new Snapshot(clone, captureState(gui)));
        });
        return snapshot.get();
    }

    private static PlanState captureState(GuiPackage gui) {
        return new PlanState(gui == null ? null : gui.getTestPlanFile(),
                gui != null && gui.isDirty(), FileServer.getFileServer().getBaseDir(), gui != null);
    }

    private static void restoreState(PlanState state) {
        FileServer fileServer = FileServer.getFileServer();
        if (state.filePath != null && !state.filePath.isBlank()) {
            fileServer.setBaseForScript(new File(state.filePath));
        } else if (state.baseDirectory != null) {
            fileServer.setBasedir(state.baseDirectory);
        }
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null && state.guiCaptured) {
            gui.setTestPlanFile(state.filePath);
            gui.setDirty(state.dirty);
        }
    }

    private static String key(File file) {
        return file.getAbsoluteFile().toPath().normalize().toString();
    }

    private static void deletePartial(File file, Throwable failure) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }

    @FunctionalInterface
    private interface SnapshotSupplier {
        Snapshot get();
    }

    @FunctionalInterface
    interface TreeWriter {
        void write(HashTree tree, File file) throws IOException;
    }

    private static final class Snapshot {
        private final HashTree tree;
        private final PlanState state;

        private Snapshot(HashTree tree, PlanState state) {
            this.tree = tree;
            this.state = state;
        }
    }

    private static final class PlanState {
        private final String filePath;
        private final boolean dirty;
        private final String baseDirectory;
        private final boolean guiCaptured;

        private PlanState(String filePath, boolean dirty, String baseDirectory, boolean guiCaptured) {
            this.filePath = filePath;
            this.dirty = dirty;
            this.baseDirectory = baseDirectory;
            this.guiCaptured = guiCaptured;
        }
    }
}
