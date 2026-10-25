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
import java.util.function.Supplier;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jorphan.collections.HashTree;
import org.qainsights.jmeter.ai.agent.jmeter.ElementLoader;
import org.qainsights.jmeter.ai.agent.jmeter.ElementSaver;

/** Saves and restores plan snapshots without changing the GUI's associated file on backup. */
public class PlanBackup implements FixLoop.BackupStore {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final Supplier<HashTree> treeSupplier;
    private final ElementLoader loader;
    private final Path directory;
    private final Map<String, PlanState> states = new HashMap<>();

    public PlanBackup() {
        this(PlanBackup::liveTree, ElementLoader.live(),
                Path.of(System.getProperty("user.home"), ".jmeter-ai", "fix-backups"));
    }

    public PlanBackup(Supplier<HashTree> treeSupplier, ElementLoader loader, Path directory) {
        this.treeSupplier = treeSupplier;
        this.loader = loader;
        this.directory = directory;
    }

    @Override
    public synchronized File backup() {
        HashTree tree = treeSupplier.get();
        if (tree == null || tree.size() == 0) {
            throw new IllegalStateException("No test plan is currently open.");
        }
        try {
            Files.createDirectories(directory);
            File file = nextFile();
            ElementSaver.writeTree(tree, file);
            states.put(file.getAbsolutePath(), captureState());
            pruneOldBackups();
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Could not back up the test plan.", e);
        }
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
        PlanState state = states.get(file.getAbsolutePath());
        if (state != null) {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui != null) {
                gui.setTestPlanFile(state.filePath);
                gui.setDirty(state.dirty);
            }
        }
    }

    private File nextFile() {
        String stem = "fix-" + LocalDateTime.now().format(STAMP);
        Path path = directory.resolve(stem + ".jmx");
        int suffix = 2;
        while (Files.exists(path)) {
            path = directory.resolve(stem + "-" + suffix++ + ".jmx");
        }
        return path.toFile();
    }

    private void pruneOldBackups() throws IOException {
        File[] files = directory.toFile().listFiles((dir, name) -> name.startsWith("fix-") && name.endsWith(".jmx"));
        if (files == null || files.length <= 10) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = 10; i < files.length; i++) {
            Files.deleteIfExists(files[i].toPath());
            states.remove(files[i].getAbsolutePath());
        }
    }

    private static PlanState captureState() {
        GuiPackage gui = GuiPackage.getInstance();
        return gui == null ? new PlanState(null, false)
                : new PlanState(gui.getTestPlanFile(), gui.isDirty());
    }

    private static HashTree liveTree() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getTreeModel() == null) {
            return null;
        }
        return gui.getTreeModel().getTestPlan();
    }

    private static final class PlanState {
        private final String filePath;
        private final boolean dirty;

        private PlanState(String filePath, boolean dirty) {
            this.filePath = filePath;
            this.dirty = dirty;
        }
    }
}
