package org.qainsights.jmeter.ai.param;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ParameterTransaction {
    public interface Operation {
        int run() throws Exception;
    }

    private ParameterTransaction() {}

    public static int apply(Path file, Operation writeCsv, Operation applyPlan) throws Exception {
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
                Files.copy(target, previous, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (Exception failure) {
                Files.deleteIfExists(previous);
                throw failure;
            }
        }
        boolean cleanup = true;
        try {
            writeCsv.run();
            return applyPlan.run();
        } catch (Throwable failure) {
            try {
                if (previous == null) {
                    Files.deleteIfExists(target);
                } else {
                    ParameterCsv.replaceFile(previous, target);
                }
            } catch (Exception rollback) {
                cleanup = false;
                java.io.IOException recovery = new java.io.IOException(
                        "CSV rollback failed: " + rollback.getMessage() + ". Original backup: " + previous, failure);
                recovery.addSuppressed(rollback);
                if (failure instanceof Error) {
                    failure.addSuppressed(recovery);
                } else {
                    throw recovery;
                }
            }
            throw failure;
        } finally {
            if (cleanup && previous != null) {
                try {
                    Files.deleteIfExists(previous);
                } catch (java.io.IOException | SecurityException cleanupFailure) {
                    org.slf4j.LoggerFactory.getLogger(ParameterTransaction.class)
                            .warn("Could not remove Parameterize backup {}", previous, cleanupFailure);
                }
            }
        }
    }
}
