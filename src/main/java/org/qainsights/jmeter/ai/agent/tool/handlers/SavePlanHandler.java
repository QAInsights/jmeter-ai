package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.ElementSaver;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The {@code save_plan} agent tool. Writes the current test plan to a
 * {@code .jmx} file - the same persistence JMeter's own Save/Save As
 * toolbar actions use, minus the interactive file-chooser dialog.
 */
public final class SavePlanHandler {

    public static final String SAVE_PLAN = "save_plan";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_MISSING_FILE_PATH = "missing_file_path";
    public static final String ERR_SAVE_FAILED = "save_failed";

    private static final String JMX_EXTENSION = ".jmx";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final Supplier<String> currentFilePathSupplier;
    private final ElementSaver saver;

    /** Production constructor wiring the live JMeter tree and file saver. */
    public SavePlanHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, SavePlanHandler::liveTestPlanFile, ElementSaver.live());
    }

    public SavePlanHandler(Supplier<JMeterTreeNode> rootSupplier, Supplier<String> currentFilePathSupplier,
                           ElementSaver saver) {
        this.rootSupplier = rootSupplier;
        this.currentFilePathSupplier = currentFilePathSupplier;
        this.saver = saver;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(SAVE_PLAN)
                .description("Saves the current test plan to a .jmx file - the same action as JMeter's own "
                        + "'Save'/'Save As' toolbar buttons. If file_path is omitted, saves back to the plan's "
                        + "already-associated file (fails if it has never been saved before). Adds a .jmx "
                        + "extension automatically if missing.")
                .addParameter(ToolParameter.builder("file_path", ParamType.STRING)
                        .description("Absolute or relative path to save the .jmx file to. Optional if the plan "
                                + "already has an associated file (e.g. it was opened via open_plan or saved "
                                + "before).")
                        .build())
                .addPrecondition("A test plan must be open (see get_tree_state)")
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
        if (rootSupplier.get() == null) {
            return ToolResult.error(ERR_NO_TEST_PLAN, "No test plan is currently open.");
        }

        String filePath = string(args.get("file_path"));
        if (filePath.isEmpty()) {
            String current = currentFilePathSupplier.get();
            filePath = current == null ? "" : current;
        }
        if (filePath.isEmpty()) {
            return ToolResult.error(ERR_MISSING_FILE_PATH,
                    "No file_path given and the plan has no associated file yet. Provide file_path to save it.");
        }
        if (!filePath.toLowerCase().endsWith(JMX_EXTENSION)) {
            filePath = filePath + JMX_EXTENSION;
        }

        File file = new File(filePath);
        try {
            if (!saver.save(file)) {
                return ToolResult.error(ERR_SAVE_FAILED,
                        "Could not save the test plan - no live JMeter GUI, or the plan is empty.");
            }
        } catch (IOException e) {
            return ToolResult.error(ERR_SAVE_FAILED,
                    "Failed to save '" + file.getAbsolutePath() + "': " + e.getMessage());
        }
        return ToolResult.ok("Saved test plan to '" + file.getAbsolutePath() + "'.");
    }

    /** Live current-file supplier, backed by {@link GuiPackage#getTestPlanFile()}. */
    private static String liveTestPlanFile() {
        GuiPackage gui = GuiPackage.getInstance();
        return gui == null ? null : gui.getTestPlanFile();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
