package org.qainsights.jmeter.ai.utils;

import java.io.File;
import java.io.IOException;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementLoader;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PlanReplacementGuard}.
 */
class PlanReplacementGuardTest {

    private static class FakeController implements TestRunController {
        boolean running = false;
        @Override
        public boolean dispatch(String actionName) { return true; }
        @Override
        public boolean isRunning() { return running; }
    }

    private static class FakeLoader implements ElementLoader {
        File lastLoadedFile;
        boolean succeed = true;
        @Override
        public boolean load(File file) throws IOException, IllegalUserActionException {
            this.lastLoadedFile = file;
            return succeed;
        }
    }

    @Test
    void should_throwException_when_testIsRunning() {
        FakeController controller = new FakeController();
        controller.running = true;
        PlanReplacementGuard guard = new PlanReplacementGuard(() -> false, controller, new FakeLoader());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard.checkCanReplace(false));
        assertTrue(ex.getMessage().contains("test is currently running"));
    }

    @Test
    void should_throwException_when_planIsDirtyAndNotForced() {
        FakeController controller = new FakeController();
        PlanReplacementGuard guard = new PlanReplacementGuard(() -> true, controller, new FakeLoader());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard.checkCanReplace(false));
        assertTrue(ex.getMessage().contains("unsaved changes"));
    }

    @Test
    void should_notThrowException_when_planIsDirtyAndForced() {
        FakeController controller = new FakeController();
        PlanReplacementGuard guard = new PlanReplacementGuard(() -> true, controller, new FakeLoader());

        assertDoesNotThrow(() -> guard.checkCanReplace(true));
    }

    @Test
    void should_delegateLoad_when_loadingPlan() throws Exception {
        FakeController controller = new FakeController();
        FakeLoader loader = new FakeLoader();
        PlanReplacementGuard guard = new PlanReplacementGuard(() -> false, controller, loader);

        File file = new File("dummy.jmx");
        assertTrue(guard.loadPlan(file));
        assertEquals(file, loader.lastLoadedFile);
    }
}
