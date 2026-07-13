package org.qainsights.jmeter.ai.agent.jmeter;

import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.Load;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.services.FileServer;
import org.apache.jorphan.collections.HashTree;

/**
 * Seam over "load a .jmx file, replacing the current test plan" - the same
 * {@link SaveService#loadTree} plus {@link Load#insertLoadedTree} that
 * JMeter's own Open action uses under the hood (minus the
 * {@code JFileChooser} dialog and the "save first?" prompt, since the agent
 * supplies the path directly and unsaved-changes handling is the
 * {@code open_plan} tool's own {@code force} guard). Kept behind an
 * interface so the {@code open_plan} tool logic can be unit-tested without a
 * running JMeter GUI.
 */
@FunctionalInterface
public interface ElementLoader {

    /**
     * Loads {@code file}, replacing the current test plan tree (mirrors
     * JMeter's own {@code ActionNames.OPEN}, not {@code MERGE}).
     *
     * @return {@code true} if a live JMeter GUI was available to load into;
     *         {@code false} otherwise (nothing was loaded)
     * @throws IOException                  if reading the file failed
     * @throws IllegalUserActionException if the file's tree could not be
     *                                       inserted (e.g. empty/unreadable plan)
     */
    boolean load(File file) throws IOException, IllegalUserActionException;

    /**
     * Live loader: reads the file via {@link SaveService#loadTree}, then
     * hands it to JMeter's own {@link Load#insertLoadedTree} (public, used
     * directly - not through the GUI action's file-chooser layer) to clear
     * and replace the current tree, exactly as JMeter's own Open menu item
     * does - performed on the EDT, since it mutates live Swing tree-model
     * state.
     */
    static ElementLoader live() {
        EdtExecutor edt = EdtExecutor.swing();
        return file -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return false;
            }
            AtomicReference<IOException> ioError = new AtomicReference<>();
            AtomicReference<IllegalUserActionException> actionError = new AtomicReference<>();
            AtomicBoolean loaded = new AtomicBoolean(false);
            edt.run(() -> {
                try {
                    FileServer.getFileServer().setBaseForScript(file);
                    HashTree tree = SaveService.loadTree(file);
                    boolean isTestPlan = Load.insertLoadedTree(ActionEvent.ACTION_PERFORMED, tree, false);
                    if (isTestPlan) {
                        gui.setTestPlanFile(file.getAbsolutePath());
                    }
                    gui.setDirty(false);
                    loaded.set(true);
                } catch (IOException e) {
                    ioError.set(e);
                } catch (IllegalUserActionException e) {
                    actionError.set(e);
                }
            });
            IOException ioFailure = ioError.get();
            if (ioFailure != null) {
                throw ioFailure;
            }
            IllegalUserActionException actionFailure = actionError.get();
            if (actionFailure != null) {
                throw actionFailure;
            }
            return loaded.get();
        };
    }
}
