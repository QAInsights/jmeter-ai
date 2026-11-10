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
