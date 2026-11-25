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
import org.qainsights.jmeter.ai.agent.jmeter.ElementLoader;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportPlanContentHandlerTest {

    private static final String VALID_JMX = "<jmeterTestPlan><hashTree/></jmeterTestPlan>";

    private boolean dirty;
    private FakeController controller;
    private FakeLoader loader;
    private Tool tool;

    @BeforeEach
    void setUp() {
        dirty = false;
        controller = new FakeController();
        loader = new FakeLoader();
        tool = new ImportPlanContentHandler(() -> dirty, controller, loader).tool();
    }

    @Test
    void blankContentReturnsMissingContentWithoutLoading() {
        ToolResult result = execute("  \n\t ");

        assertEquals(ImportPlanContentHandler.ERR_MISSING_CONTENT, result.getErrorCode());
        assertEquals(0, loader.untitledCalls);
    }

    @Test
    void contentOverOneMibReturnsContentTooLarge() {
        ToolResult result = execute("x".repeat(ImportPlanContentHandler.MAX_CONTENT_BYTES + 1));

        assertEquals(ImportPlanContentHandler.ERR_CONTENT_TOO_LARGE, result.getErrorCode());
        assertTrue(result.getMessage().contains("1 MiB"));
        assertEquals(0, loader.untitledCalls);
    }

    @Test
    void malformedXmlReturnsInvalidJmxWithoutLoading() {
        ToolResult result = execute("<jmeterTestPlan>");

        assertEquals(ImportPlanContentHandler.ERR_INVALID_JMX, result.getErrorCode());
        assertTrue(result.getMessage().contains("Invalid JMX document:"));
        assertEquals(0, loader.untitledCalls);
    }

    @Test
    void doctypeAndExternalEntitiesAreRejectedWithoutLoading() {
        String jmx = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<jmeterTestPlan>&e;</jmeterTestPlan>";

        ToolResult result = execute(jmx);

        assertEquals(ImportPlanContentHandler.ERR_INVALID_JMX, result.getErrorCode());
        assertEquals(0, loader.untitledCalls);
    }

    @Test
    void wrongRootElementReturnsInvalidJmxWithoutLoading() {
        ToolResult result = execute("<foo/>");

        assertEquals(ImportPlanContentHandler.ERR_INVALID_JMX, result.getErrorCode());
        assertTrue(result.getMessage().contains("<foo>"));
        assertEquals(0, loader.untitledCalls);
    }

    @Test
    void runningTestReturnsTestRunningWithoutLoading() {
        controller.running = true;

        ToolResult result = execute(VALID_JMX);

        assertEquals(ImportPlanContentHandler.ERR_TEST_RUNNING, result.getErrorCode());
        assertEquals(0, loader.untitledCalls);
    }

    @Test
    void dirtyPlanRequiresForce() {
        dirty = true;

        ToolResult result = execute(VALID_JMX);

        assertEquals(ImportPlanContentHandler.ERR_CONFIRM_REQUIRED, result.getErrorCode());
        assertEquals(0, loader.untitledCalls);
    }

    @Test
    void forceLoadsOverDirtyPlan() {
        dirty = true;

        ToolResult result = tool.execute(arguments(VALID_JMX, true));

        assertTrue(result.isSuccess());
        assertEquals(1, loader.untitledCalls);
        assertEquals(VALID_JMX, loader.content);
    }

    @Test
    void successLoadsTemporaryJmxAndDeletesIt() {
        ToolResult result = execute(VALID_JMX);

        assertTrue(result.isSuccess());
        assertEquals(1, loader.untitledCalls);
        assertNotNull(loader.path);
        assertTrue(loader.path.getFileName().toString().endsWith(".jmx"));
        assertEquals(VALID_JMX, loader.content);
        assertFalse(Files.exists(loader.path));
        assertTrue(result.getData().contains("save_plan"));
        assertTrue(result.getData().contains("get_tree_state"));
    }

    @Test
    void markdownFenceIsRemovedBeforeLoading() {
        ToolResult result = execute("```xml\n" + VALID_JMX + "\n```");

        assertTrue(result.isSuccess());
        assertEquals(VALID_JMX, loader.content);
    }

    @Test
    void loaderIOExceptionReturnsImportFailedAndDeletesTemporaryFile() {
        loader.ioFailure = new IOException("load failed");

        ToolResult result = execute(VALID_JMX);

        assertEquals(ImportPlanContentHandler.ERR_IMPORT_FAILED, result.getErrorCode());
        assertTrue(result.getMessage().contains("load failed"));
        assertNotNull(loader.path);
        assertFalse(Files.exists(loader.path));
    }

    @Test
    void loaderIllegalUserActionReturnsImportFailedAndDeletesTemporaryFile() {
        loader.actionFailure = new IllegalUserActionException("invalid plan");

        ToolResult result = execute(VALID_JMX);

        assertEquals(ImportPlanContentHandler.ERR_IMPORT_FAILED, result.getErrorCode());
        assertTrue(result.getMessage().contains("invalid plan"));
        assertNotNull(loader.path);
        assertFalse(Files.exists(loader.path));
    }

    @Test
    void unavailableGuiReturnsImportFailedAndDeletesTemporaryFile() {
        loader.result = false;

        ToolResult result = execute(VALID_JMX);

        assertEquals(ImportPlanContentHandler.ERR_IMPORT_FAILED, result.getErrorCode());
        assertTrue(result.getMessage().contains("no live JMeter GUI available"));
        assertNotNull(loader.path);
        assertFalse(Files.exists(loader.path));
    }

    @Test
    void toolHasRequiredJmxContentParameter() {
        ToolSpec spec = tool.getSpec();

        assertEquals(ImportPlanContentHandler.IMPORT_PLAN_CONTENT, spec.getName());
        assertEquals(1, spec.getRequiredParameters().size());
        ToolParameter parameter = spec.getRequiredParameters().get(0);
        assertEquals("jmx_content", parameter.getName());
        assertEquals(ParamType.STRING, parameter.getType());
        assertFalse(spec.getParameters().get(1).isRequired());
    }

    private ToolResult execute(String content) {
        return tool.execute(arguments(content, false));
    }

    private static Map<String, Object> arguments(String content, boolean force) {
        Map<String, Object> args = new HashMap<>();
        args.put("jmx_content", content);
        args.put("force", force);
        return args;
    }

    private static final class FakeController implements TestRunController {
        private boolean running;

        @Override
        public boolean dispatch(String actionName) {
            return false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    private static final class FakeLoader implements ElementLoader {
        private int untitledCalls;
        private boolean result = true;
        private Path path;
        private String content;
        private IOException ioFailure;
        private IllegalUserActionException actionFailure;

        @Override
        public boolean load(File file) throws IOException, IllegalUserActionException {
            path = file.toPath();
            if (ioFailure != null) {
                throw ioFailure;
            }
            if (actionFailure != null) {
                throw actionFailure;
            }
            content = Files.readString(path);
            return result;
        }

        @Override
        public boolean loadUntitled(File file) throws IOException, IllegalUserActionException {
            untitledCalls++;
            return load(file);
        }
    }
}
