package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qainsights.jmeter.ai.agent.jmeter.ElementLoader;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link OpenPlanHandler} using fake run-controller/loader/dirty seams. */
class OpenPlanHandlerTest {

    /** Records the last loaded file and toggles success/failure. */
    private static final class FakeLoader implements ElementLoader {
        File lastFile;
        boolean succeed = true;
        IOException ioToThrow;
        IllegalUserActionException actionToThrow;

        @Override
        public boolean load(File file) throws IOException, IllegalUserActionException {
            this.lastFile = file;
            if (ioToThrow != null) {
                throw ioToThrow;
            }
            if (actionToThrow != null) {
                throw actionToThrow;
            }
            return succeed;
        }
    }

    /** Fake run controller reporting a fixed running state; dispatch is unused here. */
    private static final class FakeController implements TestRunController {
        boolean running = false;

        @Override
        public boolean dispatch(String actionName) {
            return true;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    @TempDir
    Path tempDir;

    private FakeLoader loader;
    private FakeController controller;
    private boolean dirty;
    private Tool tool;
    private File planFile;

    @BeforeEach
    void setUp() throws IOException {
        loader = new FakeLoader();
        controller = new FakeController();
        dirty = false;
        tool = new OpenPlanHandler(() -> dirty, controller, loader).tool();

        planFile = tempDir.resolve("plan.jmx").toFile();
        Files.writeString(planFile.toPath(), "<jmeterTestPlan/>");
    }

    private static Map<String, Object> args(Object filePath, Object force) {
        Map<String, Object> map = new HashMap<>();
        map.put("file_path", filePath);
        if (force != null) {
            map.put("force", force);
        }
        return map;
    }

    @Test
    void spec_declaresNameAndOneRequiredParameter() {
        assertEquals(OpenPlanHandler.OPEN_PLAN, tool.getSpec().getName());
        assertEquals(1, tool.getSpec().getRequiredParameters().size());
    }

    @Test
    void open_missingFilePath_returnsError() {
        ToolResult r = tool.execute(args(null, null));

        assertFalse(r.isSuccess());
        assertEquals(OpenPlanHandler.ERR_MISSING_FILE_PATH, r.getErrorCode());
        assertNull(loader.lastFile);
    }

    @Test
    void open_fileNotFound_returnsError() {
        String missing = tempDir.resolve("nope.jmx").toString();
        ToolResult r = tool.execute(args(missing, null));

        assertFalse(r.isSuccess());
        assertEquals(OpenPlanHandler.ERR_FILE_NOT_FOUND, r.getErrorCode());
        assertNull(loader.lastFile);
    }

    @Test
    void open_whileTestRunning_returnsErrorWithoutLoading() {
        controller.running = true;
        ToolResult r = tool.execute(args(planFile.getPath(), null));

        assertFalse(r.isSuccess());
        assertEquals(OpenPlanHandler.ERR_TEST_RUNNING, r.getErrorCode());
        assertNull(loader.lastFile);
    }

    @Test
    void open_dirtyWithoutForce_returnsConfirmRequiredWithoutLoading() {
        dirty = true;
        ToolResult r = tool.execute(args(planFile.getPath(), null));

        assertFalse(r.isSuccess());
        assertEquals(OpenPlanHandler.ERR_CONFIRM_REQUIRED, r.getErrorCode());
        assertNull(loader.lastFile);
    }

    @Test
    void open_dirtyWithForce_succeedsAndLoads() {
        dirty = true;
        ToolResult r = tool.execute(args(planFile.getPath(), true));

        assertTrue(r.isSuccess());
        assertEquals(planFile, loader.lastFile);
    }

    @Test
    void open_notDirty_succeedsWithoutForce() {
        ToolResult r = tool.execute(args(planFile.getPath(), null));

        assertTrue(r.isSuccess());
        assertEquals(planFile, loader.lastFile);
    }

    @Test
    void open_whenLoaderReturnsFalse_returnsOpenFailedError() {
        loader.succeed = false;
        ToolResult r = tool.execute(args(planFile.getPath(), null));

        assertFalse(r.isSuccess());
        assertEquals(OpenPlanHandler.ERR_OPEN_FAILED, r.getErrorCode());
    }

    @Test
    void open_whenLoaderThrowsIOException_returnsOpenFailedErrorWithMessage() {
        loader.ioToThrow = new IOException("bad xml");
        ToolResult r = tool.execute(args(planFile.getPath(), null));

        assertFalse(r.isSuccess());
        assertEquals(OpenPlanHandler.ERR_OPEN_FAILED, r.getErrorCode());
        assertTrue(r.getMessage().contains("bad xml"));
    }

    @Test
    void open_whenLoaderThrowsIllegalUserActionException_returnsOpenFailedErrorWithMessage() {
        loader.actionToThrow = new IllegalUserActionException("empty plan");
        ToolResult r = tool.execute(args(planFile.getPath(), null));

        assertFalse(r.isSuccess());
        assertEquals(OpenPlanHandler.ERR_OPEN_FAILED, r.getErrorCode());
        assertTrue(r.getMessage().contains("empty plan"));
    }
}
