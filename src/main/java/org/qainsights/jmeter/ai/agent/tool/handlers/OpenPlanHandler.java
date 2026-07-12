package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.qainsights.jmeter.ai.agent.jmeter.ElementLoader;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The {@code open_plan} agent tool. Loads a {@code .jmx} file, replacing the
 * current test plan - the same action as JMeter's own "Open" toolbar
 * button, minus the interactive file-chooser dialog.
 * <p>
 * Safety guards: refuses to run while a test is active (mirroring JMeter's
 * own Open action), and refuses to discard unsaved changes in the current
 * plan unless {@code force=true} is passed (a two-step confirmation, same
 * pattern as {@code delete_element}'s child-subtree guard). This tool is
 * also registered as destructive in {@code JMeterAgent}, so it is further
 * gated behind a user confirmation dialog when run through the full agent.
 */
public final class OpenPlanHandler {

    public static final String OPEN_PLAN = "open_plan";

    public static final String ERR_MISSING_FILE_PATH = "missing_file_path";
    public static final String ERR_FILE_NOT_FOUND = "file_not_found";
    public static final String ERR_TEST_RUNNING = "test_running";
    public static final String ERR_CONFIRM_REQUIRED = "confirm_required";
    public static final String ERR_OPEN_FAILED = "open_failed";

    private final BooleanSupplier dirtySupplier;
    private final TestRunController controller;
    private final ElementLoader loader;

    /** Production constructor wiring the live JMeter GUI, run controller and file loader. */
    public OpenPlanHandler() {
        this(OpenPlanHandler::liveIsDirty, TestRunController.live(), ElementLoader.live());
    }

    public OpenPlanHandler(BooleanSupplier dirtySupplier, TestRunController controller, ElementLoader loader) {
        this.dirtySupplier = dirtySupplier;
        this.controller = controller;
        this.loader = loader;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(OPEN_PLAN)
                .description("Opens a .jmx file, replacing the entire current test plan - the same action as "
                        + "JMeter's own 'Open' toolbar button. This is destructive to whatever is currently in "
                        + "the tree: if it has unsaved changes, call save_plan first, or re-call this with "
                        + "force=true to discard them. Fails while a test is running - call stop_test first.")
                .addParameter(ToolParameter.builder("file_path", ParamType.STRING)
                        .description("Path to the .jmx file to open.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("force", ParamType.BOOLEAN)
                        .description("Set true to discard unsaved changes in the current test plan and open "
                                + "anyway.")
                        .build())
                .addPrecondition("no test may currently be running (see stop_test)")
                .build();

        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return handle(arguments);
            }
        };
    }

    private ToolResult handle(Map<String, Object> args) {
        String filePath = string(args.get("file_path"));
        if (filePath.isEmpty()) {
            return ToolResult.error(ERR_MISSING_FILE_PATH, "file_path is required.");
        }

        File file = new File(filePath);
        if (!file.isFile()) {
            return ToolResult.error(ERR_FILE_NOT_FOUND, "No file found at '" + file.getPath() + "'.");
        }

        if (controller.isRunning()) {
            return ToolResult.error(ERR_TEST_RUNNING,
                    "A test is currently running. Use stop_test before opening another plan.");
        }

        boolean force = bool(args.get("force"));
        if (dirtySupplier.getAsBoolean() && !force) {
            return ToolResult.error(ERR_CONFIRM_REQUIRED,
                    "The current test plan has unsaved changes. Call save_plan first, or re-call open_plan "
                            + "with force=true to discard them.");
        }

        try {
            if (!loader.load(file)) {
                return ToolResult.error(ERR_OPEN_FAILED, "Could not open the plan - no live JMeter GUI available.");
            }
        } catch (IOException | IllegalUserActionException e) {
            return ToolResult.error(ERR_OPEN_FAILED,
                    "Failed to open '" + file.getAbsolutePath() + "': " + e.getMessage());
        }
        return ToolResult.ok("Opened test plan from '" + file.getAbsolutePath()
                + "'. Call get_tree_state to see the new tree.");
    }

    /** Live dirty-check, backed by {@link GuiPackage#isDirty()}. */
    private static boolean liveIsDirty() {
        GuiPackage gui = GuiPackage.getInstance();
        return gui != null && gui.isDirty();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
