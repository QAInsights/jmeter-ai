package org.qainsights.jmeter.ai.agent.tool.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.agent.jmeter.EdtExecutor;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.ToolConfirmationGate;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParameterizeValuesHandlerTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private JMeterTreeModel model;
    private JMeterTreeNode group;
    private JMeterTreeNode scope;
    private HTTPSamplerProxy http;
    private ToolRegistry registry;
    private String scopeId;

    @BeforeAll
    static void init() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    @BeforeEach
    void setup() throws Exception {
        model = spy(new JMeterTreeModel());
        JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
        ThreadGroup tg = new ThreadGroup();
        tg.setName("Users");
        tg.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui");
        group = model.addComponent(tg, plan);
        http = new HTTPSamplerProxy();
        http.setName("Login");
        http.addArgument("user", "alice");
        http.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        scope = model.addComponent(http, group);
        scopeId = new ElementIdResolver().idOf(scope);
        registry = new ToolRegistry();
        registry.register(new ParameterizeValuesHandler(() -> model,
                () -> directory.resolve("plan.jmx").toString(), EdtExecutor.direct()).tool());
    }

    private ToolExecutor executor(ToolConfirmationGate gate) {
        return new ToolExecutor(registry, JMeterAgent.destructiveTools(), gate);
    }

    private Map<String, Object> applyArgs(String value, String name) {
        return new HashMap<>(Map.of("element_id", scopeId, "filename", "data/users.csv",
                "values", List.of(Map.of("value", value, "variableName", name))));
    }

    private ToolResult execute(Map<String, Object> args) {
        return executor((name, values) -> true).execute(ParameterizeValuesHandler.PARAMETERIZE_VALUES, args);
    }

    @Test
    void successfulApplySelectsFeatherWandUndoWithoutChangingDryRunState() {
        try (var controls = mockStatic(org.qainsights.jmeter.ai.gui.ParameterDialogControls.class)) {
            assertTrue(execute(Map.of("element_id", scopeId)).isSuccess());
            controls.verifyNoInteractions();
            assertTrue(execute(applyArgs("alice", "user")).isSuccess());
            controls.verify(org.qainsights.jmeter.ai.gui.ParameterDialogControls::parameterizationApplied);
        }
    }

    @Test
    void csvPathCannotOverwriteSavedPlan() throws Exception {
        Path plan = directory.resolve("plan.jmx");
        Files.writeString(plan, "saved plan");
        Map<String, Object> args = applyArgs("alice", "user");
        args.put("filename", plan.toString());
        ToolResult result = execute(args);
        assertFalse(result.isSuccess());
        assertEquals("saved plan", Files.readString(plan));
        assertEquals("alice", http.getArguments().getArgument(0).getValue());
    }

    @Test
    void dryRunReturnsCandidatesWithoutPromptingOrWriting() throws Exception {
        AtomicInteger prompts = new AtomicInteger();
        ToolResult result = executor((name, args) -> {
            prompts.incrementAndGet();
            return false;
        }).execute("parameterize_values", Map.of("element_id", scopeId));
        assertTrue(result.isSuccess(), result.toString());
        JsonNode data = JSON.readTree(result.getData());
        assertTrue(data.path("dryRun").asBoolean());
        assertEquals("alice", data.path("candidates").get(0).path("value").asText());
        assertEquals("user", data.path("candidates").get(0).path("variable").asText());
        assertEquals(1, data.path("candidates").get(0).path("occurrences").asInt());
        assertEquals(0, prompts.get());
        assertEquals("alice", http.getArguments().getArgument(0).getValue());
        assertEquals(1, group.getChildCount());
        assertFalse(Files.exists(directory.resolve("data")));
    }

    @Test
    void emptySelectionsAndEmptyScansAreReadOnly() throws Exception {
        http.getArguments().removeAllArguments();
        ToolResult result = executor((name, args) -> {
            fail("An empty selection must not require confirmation");
            return false;
        }).execute("parameterize_values", Map.of("element_id", scopeId, "values", List.of()));
        assertTrue(result.isSuccess());
        assertTrue(JSON.readTree(result.getData()).path("candidates").isEmpty());
        assertEquals(1, group.getChildCount());
    }

    @Test
    void dryRunDoesNotCommitPendingGuiEdits() {
        org.apache.jmeter.gui.GuiPackage gui = mock(org.apache.jmeter.gui.GuiPackage.class);
        try (org.mockito.MockedStatic<org.apache.jmeter.gui.GuiPackage> globals =
                mockStatic(org.apache.jmeter.gui.GuiPackage.class)) {
            globals.when(org.apache.jmeter.gui.GuiPackage::getInstance).thenReturn(gui);
            assertTrue(execute(Map.of("element_id", scopeId)).isSuccess());
            verify(gui, never()).updateCurrentNode();
            verify(gui, never()).refreshCurrentGui();
        }
    }

    @Test
    void applyWritesCsvAndPersistsConfigProperties() throws Exception {
        Map<String, Object> args = applyArgs("alice", "username");
        args.putAll(Map.of("delimiter", ";", "recycle", false, "stopThread", true,
                "shareMode", "shareMode.thread"));
        AtomicInteger prompts = new AtomicInteger();
        ToolResult result = executor((name, values) -> {
            prompts.incrementAndGet();
            assertEquals("data/users.csv", values.get("filename"));
            return true;
        }).execute("parameterize_values", args);
        assertTrue(result.isSuccess(), result.toString());
        JsonNode data = JSON.readTree(result.getData());
        assertEquals("alice", data.path("variables").path("username").asText());
        assertEquals(1, data.path("replacedCount").asInt());
        assertEquals(directory.resolve("data/users.csv").toString(), data.path("filePath").asText());
        assertEquals("\"username\"\n\"alice\"\n", Files.readString(directory.resolve("data/users.csv")));
        assertEquals("${username}", http.getArguments().getArgument(0).getValue());
        CSVDataSet csv = (CSVDataSet) ((JMeterTreeNode) group.getChildAt(0)).getTestElement();
        assertEquals("data/users.csv", csv.getPropertyAsString("filename"));
        assertEquals("username", csv.getPropertyAsString("variableNames"));
        assertEquals(";", csv.getPropertyAsString("delimiter"));
        assertEquals("UTF-8", csv.getPropertyAsString("fileEncoding"));
        assertTrue(csv.getPropertyAsBoolean("ignoreFirstLine"));
        assertTrue(csv.getPropertyAsBoolean("quotedData"));
        assertFalse(csv.getPropertyAsBoolean("recycle"));
        assertTrue(csv.getPropertyAsBoolean("stopThread"));
        assertEquals("shareMode.thread", csv.getPropertyAsString("shareMode"));
        assertEquals(1, prompts.get());
    }

    @Test
    void applyUsesBoundaryAwareReplacementAndOriginalJsonTokens() {
        http.addArgument("boundary", "1234");
        http.setPath("/1234/12345/abc1234x");
        ToolResult result = execute(applyArgs("1234", "id"));
        assertTrue(result.isSuccess(), result.toString());
        assertEquals("/${id}/12345/abc1234x", http.getPath());
        http.getArguments().removeAllArguments();
        http.setPostBodyRaw(true);
        http.addNonEncodedArgument("", "{\n  \"price\" : 12.00, \"user\": \"alice\"\n}", "");
        Map<String, Object> args = applyArgs("12.00", "price");
        args.put("values", List.of(Map.of("value", "12.00", "variableName", "price"),
                Map.of("value", "alice", "variableName", "username")));
        result = execute(args);
        assertTrue(result.isSuccess(), result.toString());
        assertEquals("{\n  \"price\" : ${price}, \"user\": \"${username}\"\n}",
                http.getArguments().getArgument(0).getValue());
    }

    @Test
    void decliningNewFileOverwriteAndAppendNeverMutatesAnything() throws Exception {
        AtomicInteger prompts = new AtomicInteger();
        ToolExecutor declined = executor((name, args) -> {
            prompts.incrementAndGet();
            return false;
        });
        Map<String, Object> args = applyArgs("alice", "username");
        assertEquals("declined_by_user", declined.execute("parameterize_values", args).getErrorCode());
        assertFalse(Files.exists(directory.resolve("data/users.csv")));
        Files.createDirectories(directory.resolve("data"));
        Path file = directory.resolve("data/users.csv");
        Files.writeString(file, "old\noriginal\n");
        assertEquals("declined_by_user", declined.execute("parameterize_values", args).getErrorCode());
        addCsv("old");
        assertEquals("declined_by_user", declined.execute("parameterize_values", args).getErrorCode());
        assertEquals("old\noriginal\n", Files.readString(file));
        assertEquals("alice", http.getArguments().getArgument(0).getValue());
        assertEquals(3, prompts.get());
    }

    private CSVDataSet addCsv(String names) {
        CSVDataSet csv = new CSVDataSet();
        csv.setProperty("filename", "data/users.csv");
        csv.setProperty("variableNames", names);
        csv.setProperty("delimiter", ",");
        csv.setProperty("ignoreFirstLine", true);
        csv.setProperty("recycle", false);
        csv.setProperty("stopThread", true);
        csv.setProperty("shareMode", "shareMode.thread");
        csv.setEnabled(true);
        group.insert(new JMeterTreeNode(csv, model), 0);
        return csv;
    }

    @Test
    void appendsToMatchingCsvAndPreservesExistingConsumers() throws Exception {
        Files.createDirectories(directory.resolve("data"));
        Files.writeString(directory.resolve("data/users.csv"), "old\nfirst\nsecond\n");
        CSVDataSet csv = addCsv("old");
        ToolResult result = execute(applyArgs("alice", "username"));
        assertTrue(result.isSuccess(), result.toString());
        assertEquals("old,username", csv.getPropertyAsString("variableNames"));
        assertEquals("old,username\nfirst,alice\nsecond,alice\n",
                Files.readString(directory.resolve("data/users.csv")));
        assertFalse(csv.getPropertyAsBoolean("recycle"));
        assertTrue(csv.getPropertyAsBoolean("stopThread"));
        assertEquals("shareMode.thread", csv.getPropertyAsString("shareMode"));
        assertTrue(JSON.readTree(result.getData()).path("reusedConfigSettingsPreserved").asBoolean());
        assertEquals(2, group.getChildCount());
    }

    @Test
    void confirmsAndOverwritesUnreferencedExistingFile() throws Exception {
        Path file = directory.resolve("data/users.csv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "previous\ncontent\n");
        AtomicInteger prompts = new AtomicInteger();
        ToolResult result = executor((name, args) -> {
            prompts.incrementAndGet();
            return true;
        }).execute("parameterize_values", applyArgs("alice", "username"));
        assertTrue(result.isSuccess(), result.toString());
        assertEquals("\"username\"\n\"alice\"\n", Files.readString(file));
        assertEquals(1, prompts.get());
    }

    @Test
    void invalidInputsAreStructuredAndNeverWrite() {
        assertEquals("missing_parameter", execute(Map.of()).getErrorCode());
        assertEquals("element_not_found", execute(Map.of("element_id", "missing")).getErrorCode());
        for (Map<String, Object> changes : List.<Map<String, Object>>of(
                Map.of("values", "wrong"), Map.of("values", List.of("wrong")),
                Map.of("values", List.of(Map.of("value", "alice"))),
                Map.of("values", List.of(Map.of("value", 42, "variableName", "user"))),
                Map.of("values", List.of(Map.of("value", "alice", "variableName", "bad-name"))),
                Map.of("values", List.of(Map.of("value", "missing", "variableName", "user"))),
                Map.of("values", List.of(Map.of("value", "alice", "variableName", "user"),
                        Map.of("value", "alice", "variableName", "user"))),
                Map.of("delimiter", ""), Map.of("delimiter", "xx"), Map.of("delimiter", "\n"),
                Map.of("delimiter", "\""), Map.of("shareMode", "invalid"), Map.of("recycle", "true"),
                Map.of("stopThread", 1), Map.of("filename", ""), Map.of("filename", "${file}"),
                Map.of("filename", "bad\u0000path"), Map.of("filename", 5))) {
            Map<String, Object> args = applyArgs("alice", "username");
            args.putAll(changes);
            ToolResult result = execute(args);
            assertEquals("invalid_input", result.getErrorCode(), args + " => " + result);
        }
        assertFalse(Files.exists(directory.resolve("data")));
        assertEquals("alice", http.getArguments().getArgument(0).getValue());
    }

    @Test
    void rejectsNamesAlreadyDefinedByUdvOrAnotherCsv() {
        Arguments udv = new Arguments();
        udv.addArgument("username", "other");
        group.add(new JMeterTreeNode(udv, model));
        assertEquals("invalid_input", execute(applyArgs("alice", "username")).getErrorCode());
        addCsv("other_name");
        assertEquals("invalid_input", execute(applyArgs("alice", "other_name")).getErrorCode());
    }

    @Test
    void unsavedPlanRequiresAbsolutePathOnlyForApply() {
        registry = new ToolRegistry();
        registry.register(new ParameterizeValuesHandler(() -> model, () -> null, EdtExecutor.direct()).tool());
        assertTrue(execute(Map.of("element_id", scopeId)).isSuccess());
        assertEquals("invalid_input", execute(applyArgs("alice", "username")).getErrorCode());
        Map<String, Object> absolute = applyArgs("alice", "username");
        absolute.put("filename", directory.resolve("absolute.csv").toString());
        assertTrue(execute(absolute).isSuccess());
    }

    @Test
    void planMutationFailureRestoresNewAndExistingFiles() throws Exception {
        doThrow(new IllegalStateException("insertion failed")).when(model).insertNodeInto(any(), eq(group), eq(0));
        Path file = directory.resolve("data/users.csv");
        assertEquals("parameterize_failed", execute(applyArgs("alice", "username")).getErrorCode());
        assertFalse(Files.exists(file));
        assertEquals("alice", http.getArguments().getArgument(0).getValue());
        assertEquals(1, group.getChildCount());
        Files.writeString(file, "previous bytes\n");
        assertEquals("parameterize_failed", execute(applyArgs("alice", "username")).getErrorCode());
        assertEquals("previous bytes\n", Files.readString(file));
        assertEquals("alice", http.getArguments().getArgument(0).getValue());
        assertEquals(1, group.getChildCount());
    }

    @Test
    void malformedExistingCsvNeverChangesFileOrPlan() throws Exception {
        addCsv("old");
        Files.createDirectories(directory.resolve("data"));
        Path file = directory.resolve("data/users.csv");
        Files.writeString(file, "wrong\nvalue\n");
        assertEquals("parameterize_failed", execute(applyArgs("alice", "username")).getErrorCode());
        assertEquals("wrong\nvalue\n", Files.readString(file));
        assertEquals("alice", http.getArguments().getArgument(0).getValue());
    }

    @Test
    void incompatibleExistingCsvSettingsAreRejectedWithoutOverwritingThem() {
        CSVDataSet csv = addCsv("old");
        csv.setProperty("delimiter", ";");
        assertEquals("invalid_input", execute(applyArgs("alice", "username")).getErrorCode());
        csv.setProperty("delimiter", ",");
        csv.setProperty("fileEncoding", "UTF-16");
        assertEquals("invalid_input", execute(applyArgs("alice", "username")).getErrorCode());
        assertFalse(Files.exists(directory.resolve("data")));
    }
}
