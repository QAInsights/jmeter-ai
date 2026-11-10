package org.qainsights.jmeter.ai.param;

import java.nio.file.Files;
import java.nio.file.Path;

final class ParameterTransaction {
    interface Operation {
        int run() throws Exception;
    }

    private ParameterTransaction() {}

    static int apply(Path file, Operation writeCsv, Operation applyPlan) throws Exception {
        byte[] previous = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            writeCsv.run();
            return applyPlan.run();
        } catch (Exception failure) {
            try {
                if (previous == null) {
                    Files.deleteIfExists(file);
                } else {
                    Files.write(file, previous);
                }
            } catch (Exception rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }
}
