package org.qainsights.jmeter.ai.param;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class ParameterTransaction {
    interface Operation {
        int run() throws Exception;
    }

    private ParameterTransaction() {}

    static int apply(Path file, Operation writeCsv, Operation applyPlan) throws Exception {
        Path target = file.toAbsolutePath();
        org.apache.jmeter.gui.GuiPackage gui = org.apache.jmeter.gui.GuiPackage.getInstance();
        ParameterPlacement.validateTarget(target, gui == null ? null : gui.getTestPlanFile());
        Files.createDirectories(target.getParent());
        Path previous = null;
        if (Files.exists(target)) {
            if (!Files.isRegularFile(target)) {
                throw new java.io.IOException("CSV target must be a regular file: " + target);
            }
            previous = Files.createTempFile(target.getParent(), ".parameterize-backup-", ".csv");
            try {
                Files.copy(target, previous, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception failure) {
                Files.deleteIfExists(previous);
                throw failure;
            }
        }
        boolean cleanup = true;
        try {
            writeCsv.run();
            return applyPlan.run();
        } catch (Exception failure) {
            try {
                if (previous == null) {
                    Files.deleteIfExists(target);
                } else {
                    ParameterCsv.replaceFile(previous, target);
                }
            } catch (Exception rollback) {
                cleanup = false;
                failure.addSuppressed(rollback);
            }
            throw failure;
        } finally {
            if (cleanup && previous != null) {
                Files.deleteIfExists(previous);
            }
        }
    }
}
