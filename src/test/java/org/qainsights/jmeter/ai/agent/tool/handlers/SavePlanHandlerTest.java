package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementSaver;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link SavePlanHandler} using an in-memory tree and fake saver. */
class SavePlanHandlerTest {

    /** Records the last saved file and toggles success/failure. */
    private static final class FakeSaver implements ElementSaver {
        File lastFile;
        boolean succeed = true;
        IOException toThrow;

        @Override
        public boolean save(File file) throws IOException {
            this.lastFile = file;
            if (toThrow != null) {
                throw toThrow;
            }
            return succeed;
        }
    }

    private JMeterTreeNode root;
    private FakeSaver saver;
    private String currentFilePath;
    private Tool tool;

    @BeforeEach
    void setUp() {
        ConfigTestElement rootElement = new ConfigTestElement();
        rootElement.setName("Test Plan");
        root = new JMeterTreeNode(rootElement, null);

        saver = new FakeSaver();
        currentFilePath = null;
        tool = new SavePlanHandler(() -> root, () -> currentFilePath, saver).tool();
    }

    private static Map<String, Object> args(Object filePath) {
        Map<String, Object> map = new HashMap<>();
        map.put("file_path", filePath);
        return map;
    }

    @Test
    void spec_hasNoRequiredParameters() {
        assertEquals(SavePlanHandler.SAVE_PLAN, tool.getSpec().getName());
        assertTrue(tool.getSpec().getRequiredParameters().isEmpty());
    }

    @Test
    void save_withFilePath_delegatesAndAppendsJmxExtension() {
        ToolResult r = tool.execute(args("C:/plans/my-plan"));

        assertTrue(r.isSuccess());
        assertNotNull(saver.lastFile);
        assertTrue(saver.lastFile.getPath().endsWith("my-plan.jmx"));
    }

    @Test
    void save_withFilePathAlreadyHavingJmxExtension_doesNotDoubleIt() {
        ToolResult r = tool.execute(args("C:/plans/my-plan.jmx"));

        assertTrue(r.isSuccess());
        assertTrue(saver.lastFile.getPath().endsWith("my-plan.jmx"));
        assertFalse(saver.lastFile.getPath().endsWith(".jmx.jmx"));
    }

    @Test
    void save_noFilePath_usesCurrentAssociatedFile() {
        currentFilePath = "C:/plans/already-open.jmx";
        ToolResult r = tool.execute(Map.of());

        assertTrue(r.isSuccess());
        assertEquals(new File("C:/plans/already-open.jmx"), saver.lastFile);
    }

    @Test
    void save_noFilePathAndNoAssociatedFile_returnsMissingFilePathError() {
        ToolResult r = tool.execute(Map.of());

        assertFalse(r.isSuccess());
        assertEquals(SavePlanHandler.ERR_MISSING_FILE_PATH, r.getErrorCode());
        assertNull(saver.lastFile);
    }

    @Test
    void save_noTestPlan_returnsError() {
        Tool noPlan = new SavePlanHandler(() -> null, () -> currentFilePath, saver).tool();
        ToolResult r = noPlan.execute(args("C:/plans/my-plan.jmx"));

        assertFalse(r.isSuccess());
        assertEquals(SavePlanHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
        assertNull(saver.lastFile);
    }

    @Test
    void save_whenSaverReturnsFalse_returnsSaveFailedError() {
        saver.succeed = false;
        ToolResult r = tool.execute(args("C:/plans/my-plan.jmx"));

        assertFalse(r.isSuccess());
        assertEquals(SavePlanHandler.ERR_SAVE_FAILED, r.getErrorCode());
    }

    @Test
    void save_whenSaverThrowsIOException_returnsSaveFailedErrorWithMessage() {
        saver.toThrow = new IOException("disk full");
        ToolResult r = tool.execute(args("C:/plans/my-plan.jmx"));

        assertFalse(r.isSuccess());
        assertEquals(SavePlanHandler.ERR_SAVE_FAILED, r.getErrorCode());
        assertTrue(r.getMessage().contains("disk full"));
    }
}
