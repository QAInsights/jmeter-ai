package org.qainsights.jmeter.ai.agent.tool.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.services.FileServer;
import org.qainsights.jmeter.ai.agent.jmeter.EdtExecutor;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.param.ParameterCsv;
import org.qainsights.jmeter.ai.param.ParameterPlacement;
import org.qainsights.jmeter.ai.param.ParameterScanner;
import org.qainsights.jmeter.ai.param.ParameterNames;
import org.qainsights.jmeter.ai.param.ParameterTransaction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Local CSV parameterization, shared by Agent Mode and the built-in MCP registry. */
public final class ParameterizeValuesHandler {
    public static final String PARAMETERIZE_VALUES = "parameterize_values";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SHARE_MODES = Set.of("shareMode.all", "shareMode.group", "shareMode.thread");
    private final Supplier<JMeterTreeModel> modelSupplier;
    private final Supplier<String> planFileSupplier;
    private final EdtExecutor edt;
    private final ElementIdResolver resolver = new ElementIdResolver();

    public ParameterizeValuesHandler() {
        this(() -> GuiPackage.getInstance() == null ? null : GuiPackage.getInstance().getTreeModel(),
                () -> GuiPackage.getInstance() == null ? null : GuiPackage.getInstance().getTestPlanFile(),
                EdtExecutor.swing());
    }

    public ParameterizeValuesHandler(Supplier<JMeterTreeModel> modelSupplier,
            Supplier<String> planFileSupplier, EdtExecutor edt) {
        this.modelSupplier = modelSupplier;
        this.planFileSupplier = planFileSupplier;
        this.edt = edt;
    }

    /** Empty or absent selections are read-only; malformed selections remain confirmation-gated. */
    public static boolean writesFile(Map<String, Object> args) {
        Object values = args.get("values");
        return values != null && (!(values instanceof List) || !((List<?>) values).isEmpty());
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(PARAMETERIZE_VALUES)
                .description("Scans a selected element and subtree for hard-coded parameterization candidates. "
                        + "Without values (or with an empty array), returns candidates without changing the plan or files. "
                        + "To apply, provide values as [{value: string, variableName: string}] and filename. "
                        + "Writes a UTF-8 CSV header and data row, replaces bounded values with ${variableName}, "
                        + "and adds or reuses a core CSV Data Set Config. Existing CSV rows are preserved when appending columns. "
                        + "File changes require confirmation; failed applies restore the file and plan. Disk files are not undone by Ctrl+Z.")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("Tree-path id of the scope, as returned by get_tree_state.").required(true).build())
                .addParameter(ToolParameter.builder("values", ParamType.OBJECT_ARRAY)
                        .description("Optional selected candidates: [{value: string, variableName: string}]. Omit for a dry run.").build())
                .addParameter(ToolParameter.builder("filename", ParamType.STRING)
                        .description("Required for apply. Relative to the saved .jmx directory; unsaved plans require an absolute path.").build())
                .addParameter(ToolParameter.builder("delimiter", ParamType.STRING)
                        .description("One delimiter character, default comma.").build())
                .addParameter(ToolParameter.builder("recycle", ParamType.BOOLEAN)
                        .description("Recycle on EOF, default true.").build())
                .addParameter(ToolParameter.builder("stopThread", ParamType.BOOLEAN)
                        .description("Stop thread on EOF, default false.").build())
                .addParameter(ToolParameter.builder("shareMode", ParamType.STRING)
                        .description("Sharing mode, default shareMode.all. Reused configs preserve their consumer settings.")
                        .enumValues(List.of("shareMode.all", "shareMode.group", "shareMode.thread")).build())
                .addPrecondition("The scope must be inside a Thread Group. Scan first and select current candidate values.")
                .build();
        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                ToolResult[] result = new ToolResult[1];
                edt.run(() -> result[0] = handle(arguments));
                return result[0];
            }
        };
    }

    private ToolResult handle(Map<String, Object> args) {
        try {
            JMeterTreeModel model = modelSupplier.get();
            if (model == null) {
                return ToolResult.error("no_test_plan", "No test plan is currently open.");
            }
            GuiPackage gui = GuiPackage.getInstance();
            if (gui != null && writesFile(args)) {
                gui.updateCurrentNode();
            }
            JMeterTreeNode scope = resolver.resolve((JMeterTreeNode) model.getRoot(), text(args, "element_id", ""));
            if (scope == null) {
                return ToolResult.error("element_not_found", "Scope not found. Call get_tree_state for current ids.");
            }
            if (ParameterPlacement.threadGroupOf(scope) == null) {
                throw new IllegalArgumentException("Select a scope inside a Thread Group.");
            }
            String delimiter = text(args, "delimiter", ",");
            if (delimiter.length() != 1 || "\r\n\"".contains(delimiter)) {
                throw new IllegalArgumentException("Use one delimiter character, not a quote or newline.");
            }
            boolean recycle = bool(args, "recycle", true);
            boolean stop = bool(args, "stopThread", false);
            String sharing = text(args, "shareMode", "shareMode.all");
            if (!SHARE_MODES.contains(sharing)) {
                throw new IllegalArgumentException("Invalid shareMode.");
            }
            List<ParameterScanner.Candidate> candidates = ParameterScanner.scan(scope);
            if (!writesFile(args)) {
                return ToolResult.ok(JSON.writeValueAsString(Map.of("dryRun", true, "candidates", candidates)));
            }
            if (!(args.get("values") instanceof List)) {
                throw new IllegalArgumentException("values must be an array of {value, variableName} objects.");
            }
            String filename = text(args, "filename", "").trim();
            if (filename.isEmpty() || filename.contains("${")) {
                throw new IllegalArgumentException("Provide a literal CSV filename.");
            }
            Path requested = Path.of(filename);
            String plan = planFileSupplier.get();
            boolean saved = plan != null && !plan.isBlank();
            if (!saved && !requested.isAbsolute()) {
                throw new IllegalArgumentException("Save the plan first or provide an absolute CSV filename.");
            }
            Path base = saved ? Path.of(plan).toAbsolutePath().getParent()
                    : Path.of(FileServer.getFileServer().getBaseDir());
            Path target = base.resolve(requested).toAbsolutePath().normalize();
            ParameterPlacement.validateTarget(target, plan);
            CSVDataSet reuse = ParameterPlacement.reusable(ParameterPlacement.threadGroupOf(scope), base, filename);
            List<String> oldNames = reuse == null ? List.of() : reuse.getPropertyAsString("variableNames").isBlank()
                    ? ParameterCsv.header(target, delimiter.charAt(0), reuse.getPropertyAsBoolean("quotedData"))
                    : Arrays.asList(reuse.getPropertyAsString("variableNames").split(",", -1));
            if (reuse != null && (!delimiter.equals(reuse.getPropertyAsString("delimiter", ","))
                    || !ParameterPlacement.utf8Compatible(reuse))) {
                throw new IllegalArgumentException("Existing CSV config must use the same delimiter and UTF-8 encoding (including a UTF-8 platform default).");
            }
            List<ParameterScanner.Candidate> chosen = new ArrayList<>();
            Map<String, String> variables = new LinkedHashMap<>();
            Set<String> names = ParameterNames.used(scope);
            names.addAll(oldNames);
            Set<String> values = new HashSet<>();
            for (Object entry : (List<?>) args.get("values")) {
                if (!(entry instanceof Map)) {
                    throw new IllegalArgumentException("Each value must be an object with value and variableName strings.");
                }
                Map<?, ?> selection = (Map<?, ?>) entry;
                if (!(selection.get("value") instanceof String) || !(selection.get("variableName") instanceof String)) {
                    throw new IllegalArgumentException("value and variableName must be strings.");
                }
                String value = (String) selection.get("value");
                String name = ((String) selection.get("variableName")).trim();
                if (!ParameterNames.valid(name, names) || !values.add(value)) {
                    throw new IllegalArgumentException("Values and valid variable names must be unique, including existing CSV columns.");
                }
                ParameterScanner.Candidate candidate = candidates.stream().filter(c -> c.value.equals(value))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Value is not a current scan candidate: " + value));
                candidate.variable = name;
                chosen.add(candidate);
                variables.put(name, value);
            }
            int changed = ParameterTransaction.apply(target, () -> {
                ParameterCsv.write(target, delimiter.charAt(0), new ArrayList<>(variables.keySet()),
                        new ArrayList<>(variables.values()), oldNames,
                        reuse == null || reuse.getPropertyAsString("variableNames").isBlank()
                                || reuse.getPropertyAsBoolean("ignoreFirstLine"),
                        reuse == null || reuse.getPropertyAsBoolean("quotedData"));
                return 0;
            }, () -> ParameterPlacement.apply(model, scope, reuse, filename, delimiter, recycle, stop, sharing, chosen, oldNames));
            org.qainsights.jmeter.ai.gui.ParameterDialogControls.parameterizationApplied();
            return ToolResult.ok(JSON.writeValueAsString(Map.of("variables", variables, "replacedCount", changed,
                    "filePath", target.toString(), "reusedConfigSettingsPreserved", reuse != null)));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("invalid_input", e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("parameterize_failed", e.getMessage());
        }
    }

    private static String text(Map<String, Object> args, String key, String fallback) {
        if (!args.containsKey(key)) {
            return fallback;
        }
        if (!(args.get(key) instanceof String)) {
            throw new IllegalArgumentException(key + " must be a string.");
        }
        return (String) args.get(key);
    }

    private static boolean bool(Map<String, Object> args, String key, boolean fallback) {
        if (!args.containsKey(key)) {
            return fallback;
        }
        if (!(args.get(key) instanceof Boolean)) {
            throw new IllegalArgumentException(key + " must be a boolean.");
        }
        return (Boolean) args.get(key);
    }
}
