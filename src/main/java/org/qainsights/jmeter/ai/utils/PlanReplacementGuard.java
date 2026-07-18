package org.qainsights.jmeter.ai.utils;

import java.io.File;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.qainsights.jmeter.ai.agent.jmeter.ElementLoader;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;

/**
 * Shared guard to check if the current test plan can be replaced,
 * preventing double confirmations and handling running tests or dirty states.
 */
public final class PlanReplacementGuard {
    private final BooleanSupplier dirtySupplier;
    private final TestRunController controller;
    private final ElementLoader loader;

    public PlanReplacementGuard(BooleanSupplier dirtySupplier, TestRunController controller, ElementLoader loader) {
        this.dirtySupplier = dirtySupplier;
        this.controller = controller;
        this.loader = loader;
    }

    public static PlanReplacementGuard live() {
        return new PlanReplacementGuard(
            PlanReplacementGuard::liveIsDirty,
            TestRunController.live(),
            ElementLoader.live()
        );
    }

    private static boolean liveIsDirty() {
        org.apache.jmeter.gui.GuiPackage gui = org.apache.jmeter.gui.GuiPackage.getInstance();
        return gui != null && gui.isDirty();
    }

    public void checkCanReplace(boolean force) {
        if (controller.isRunning()) {
            throw new IllegalStateException("A test is currently running. Stop the test first.");
        }
        if (dirtySupplier.getAsBoolean() && !force) {
            throw new IllegalStateException("The current test plan has unsaved changes. Save or discard them first.");
        }
    }

    public boolean loadPlan(File file) throws IOException, IllegalUserActionException {
        return loader.load(file);
    }
}
