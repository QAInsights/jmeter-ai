package org.qainsights.jmeter.ai.param;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Set;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import static org.mockito.Mockito.mock;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.testelement.TestElement;
import static org.mockito.Mockito.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParameterReviewTest {
    @Test
    void appendingColumnsPreservesExistingQuotesLineEndingsAndFinalNewlineState() throws Exception {
        Path csv = directory.resolve("preserved.csv");
        Files.writeString(csv, "id,\"text\"\r\n001,\"a, b\"\r\n\"002\",\"two\r\nlines\"\n003,last");
        ParameterCsv.write(csv, ',', List.of("new"), List.of("quoted\"value"),
                List.of("id", "text"), true);
        assertEquals("id,\"text\",\"new\"\r\n001,\"a, b\",\"quoted\"\"value\"\r\n"
                + "\"002\",\"two\r\nlines\",\"quoted\"\"value\"\n003,last,\"quoted\"\"value\"",
                Files.readString(csv));
    }

    @Test
    void httpUploadPathsMimeTypesAndParameterNamesAreNeverRewritten() {
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.setHTTPFiles(new org.apache.jmeter.protocol.http.util.HTTPFileArg[] {
            new org.apache.jmeter.protocol.http.util.HTTPFileArg(
                    "uploads/1234.csv", "upload_1234", "application/x-1234")
        });
        JMeterTreeNode scope = new JMeterTreeNode(http, null);
        assertTrue(ParameterScanner.scan(scope).isEmpty());
        http.addArgument("id", "1234");
        List<ParameterScanner.Candidate> candidates = ParameterScanner.scan(scope);
        assertEquals(1, candidates.size());
        assertEquals(1, candidates.get(0).occurrences);
        assertEquals(1, ParameterReplacement.replace(scope, candidates, true));
        assertEquals("uploads/1234.csv", http.getHTTPFiles()[0].getPath());
        assertEquals("upload_1234", http.getHTTPFiles()[0].getParamName());
        assertEquals("application/x-1234", http.getHTTPFiles()[0].getMimeType());
    }

    @Test
    void userDefinedValuesAreNotOfferedOrReplacedButTheirNamesRemainReserved() {
        TestPlan plan = new TestPlan();
        Arguments planVariables = new Arguments();
        planVariables.addArgument("user", "alice");
        plan.setUserDefinedVariables(planVariables);
        JMeterTreeNode root = new JMeterTreeNode(plan, null);
        JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), null);
        root.add(group);
        Arguments variables = new Arguments();
        variables.addArgument("token", "secret-value");
        variables.addArgument("same", "alice");
        group.add(new JMeterTreeNode(variables, null));
        assertTrue(ParameterScanner.scan(group).isEmpty());
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.addArgument("user", "alice");
        group.add(new JMeterTreeNode(http, null));
        List<ParameterScanner.Candidate> candidates = ParameterScanner.scan(root);
        assertEquals(1, candidates.size());
        assertNotEquals("user", candidates.get(0).variable);
        assertEquals(1, candidates.get(0).occurrences);
        assertEquals(1, ParameterReplacement.replace(root, candidates, true));
        assertEquals("alice", plan.getUserDefinedVariables().get("user"));
        assertEquals("alice", variables.getArgument(1).getValue());
        assertTrue(ParameterNames.used(group).containsAll(Set.of("user", "token", "same")));
    }

    @BeforeAll
    static void init() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    @TempDir Path directory;

    @Test
    void newCsvSettingsAreStoredInPersistentJMeterProperties() throws Exception {
        JMeterTreeModel model = mock(JMeterTreeModel.class);
        JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), null);
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.addArgument("user", "alice");
        JMeterTreeNode scope = new JMeterTreeNode(sampler, null);
        group.add(scope);
        when(model.addComponent(any(), eq(group))).thenAnswer(call -> {
            JMeterTreeNode node = new JMeterTreeNode(call.getArgument(0), null);
            group.add(node);
            return node;
        });
        ParameterPlacement.apply(model, scope, null, "data.csv", ";", false, true,
                "shareMode.thread", ParameterScanner.scan(scope));
        CSVDataSet csv = (CSVDataSet) ((JMeterTreeNode) group.getChildAt(1)).getTestElement();
        assertEquals("data.csv", csv.getPropertyAsString("filename"));
        assertEquals("user", csv.getPropertyAsString("variableNames"));
        assertEquals(";", csv.getPropertyAsString("delimiter"));
        assertEquals("UTF-8", csv.getPropertyAsString("fileEncoding"));
        assertTrue(csv.getPropertyAsBoolean("ignoreFirstLine"));
        assertTrue(csv.getPropertyAsBoolean("quotedData"));
        assertFalse(csv.getPropertyAsBoolean("recycle"));
        assertTrue(csv.getPropertyAsBoolean("stopThread"));
        assertEquals("shareMode.thread", csv.getPropertyAsString("shareMode"));
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            org.apache.jmeter.testbeans.gui.TestBeanGUI editor =
                    new org.apache.jmeter.testbeans.gui.TestBeanGUI(CSVDataSet.class);
            editor.configure(csv);
            TestElement saved = (TestElement) csv.clone();
            editor.modifyTestElement(saved);
            assertEquals("data.csv", saved.getPropertyAsString("filename"));
            assertEquals("user", saved.getPropertyAsString("variableNames"));
            assertTrue(saved.getPropertyAsBoolean("ignoreFirstLine"));
            assertTrue(saved.getPropertyAsBoolean("quotedData"));
        });
    }

    @Test
    void streamsLargeCsvAndRestoresDiskBackupOnPlanFailure() throws Exception {
        Path file = directory.resolve("large.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            writer.write("old\n");
            for (int i = 0; i < 100000; i++) {
                writer.write("row" + i + "\n");
            }
        }
        long originalSize = Files.size(file);
        assertThrows(IOException.class, () -> ParameterTransaction.apply(file, () -> {
            ParameterCsv.write(file, ',', List.of("new"), List.of("value"), List.of("old"));
            return 0;
        }, () -> {
            throw new IOException("plan failed");
        }));
        assertEquals(originalSize, Files.size(file));
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            assertEquals("old", reader.readLine());
            for (int i = 0; i < 100000; i++) {
                assertEquals("row" + i, reader.readLine());
            }
            assertNull(reader.readLine());
        }
        ParameterCsv.write(file, ',', List.of("new"), List.of("value"), List.of("old"));
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            assertEquals("\"old\",\"new\"", reader.readLine());
            assertEquals("\"row0\",\"value\"", reader.readLine());
        }
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertEquals(List.of(file), files.toList());
        }
    }

    @Test
    void jsonNumbersKeepTheirOriginalLexicalValue() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setPostBodyRaw(true);
        sampler.addNonEncodedArgument("", "{\n  \"price\": 12.00, \"exponent\": [1e+03]\n}", "");
        JMeterTreeNode node = new JMeterTreeNode(sampler, null);
        List<ParameterScanner.Candidate> candidates = ParameterScanner.scan(node);
        assertEquals(List.of("12.00", "1e+03"), candidates.stream().map(c -> c.value).toList());
        assertEquals(2, ParameterReplacement.replace(node, candidates, true));
        assertEquals("{\n  \"price\": ${price}, \"exponent\": [${exponent}]\n}",
                sampler.getArguments().getArgument(0).getValue());
    }

    @Test
    void reservesCsvAndUdvNamesInGroupAndAncestorScopes() {
        TestPlan plan = new TestPlan();
        Arguments variables = new Arguments();
        variables.addArgument("user", "shared");
        plan.setUserDefinedVariables(variables);
        JMeterTreeNode root = new JMeterTreeNode(plan, null);
        CSVDataSet planCsv = new CSVDataSet();
        planCsv.setProperty("variableNames", "user_2");
        root.add(new JMeterTreeNode(planCsv, null));
        JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), null);
        root.add(group);
        Arguments udv = new Arguments();
        udv.addArgument("user_3", "other");
        group.add(new JMeterTreeNode(udv, null));
        CSVDataSet csv = new CSVDataSet();
        csv.setProperty("variableNames", "user_4");
        group.add(new JMeterTreeNode(csv, null));
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.addArgument("user", "alice");
        JMeterTreeNode scope = new JMeterTreeNode(sampler, null);
        group.add(scope);
        assertEquals("user_5", ParameterScanner.scan(scope).get(0).variable);
        Set<String> used = ParameterNames.used(scope);
        for (String name : List.of("user", "user_2", "user_3", "user_4")) {
            assertFalse(ParameterNames.valid(name, used));
        }
        assertTrue(ParameterNames.valid("user_5", used));
    }

    @Test
    void reusingCsvPreservesItsConsumerSettings() throws Exception {
        JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), null);
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.addArgument("user", "alice");
        JMeterTreeNode scope = new JMeterTreeNode(http, null);
        group.add(scope);
        CSVDataSet csv = new CSVDataSet();
        csv.setProperty("variableNames", "old");
        csv.setProperty("delimiter", ";");
        csv.setProperty("fileEncoding", "");
        csv.setProperty("recycle", false);
        csv.setProperty("stopThread", true);
        csv.setProperty("shareMode", "shareMode.thread");
        group.add(new JMeterTreeNode(csv, null));
        ParameterPlacement.apply(mock(JMeterTreeModel.class), scope, csv, "data.csv", ",",
                true, false, "shareMode.all", ParameterScanner.scan(scope));
        assertEquals(";", csv.getPropertyAsString("delimiter"));
        assertEquals("", csv.getPropertyAsString("fileEncoding"));
        assertFalse(csv.getPropertyAsBoolean("recycle"));
        assertTrue(csv.getPropertyAsBoolean("stopThread"));
        assertEquals("shareMode.thread", csv.getPropertyAsString("shareMode"));
        assertEquals("old,user", csv.getPropertyAsString("variableNames"));
    }

    @Test
    void reuseOnlySelectsEnabledDirectChildren() {
        JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode controller = new JMeterTreeNode(new GenericController(), null);
        group.add(controller);
        CSVDataSet nested = new CSVDataSet();
        nested.setProperty("filename", "data.csv");
        nested.setEnabled(true);
        controller.add(new JMeterTreeNode(nested, null));
        assertNull(ParameterPlacement.reusable(group, directory, "data.csv"));
        CSVDataSet direct = new CSVDataSet();
        direct.setProperty("filename", "data.csv");
        direct.setEnabled(false);
        group.add(new JMeterTreeNode(direct, null));
        assertNull(ParameterPlacement.reusable(group, directory, "data.csv"));
        direct.setEnabled(true);
        assertSame(direct, ParameterPlacement.reusable(group, directory, "data.csv"));
    }
    @Test
    void csvAndOtherConfigFilePropertiesAreNeverCandidatesOrReplacementTargets() {
        Arguments config = new Arguments();
        config.addArgument("file", "data/1234.csv");
        config.setProperty("other.filename", "1234");
        JMeterTreeNode root = new JMeterTreeNode(config, null);
        CSVDataSet csv = new CSVDataSet();
        csv.setProperty("filename", "data/1234.csv");
        csv.setProperty("variableNames", "1234");
        root.add(new JMeterTreeNode(csv, null));
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.addArgument("id", "1234");
        root.add(new JMeterTreeNode(sampler, null));
        List<ParameterScanner.Candidate> candidates = ParameterScanner.scan(root);
        assertEquals(List.of("1234"), candidates.stream().map(c -> c.value).toList());
        assertEquals(1, candidates.get(0).occurrences);
        assertEquals(1, ParameterReplacement.replace(root, candidates, true));
        assertEquals("data/1234.csv", csv.getPropertyAsString("filename"));
        assertEquals("1234", csv.getPropertyAsString("variableNames"));
        assertEquals("data/1234.csv", config.getArgument(0).getValue());
        assertEquals("1234", config.getPropertyAsString("other.filename"));
    }
    @Test
    void unsafeJsonStringsAreNeitherOfferedNorReplacedFromOtherSources() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (String value : List.of("a\"quote", "a\\slash", "a\nline", "a\ttab", "a\u0001control")) {
            HTTPSamplerProxy sampler = new HTTPSamplerProxy();
            sampler.setPostBodyRaw(true);
            String body = "{\"value\":" + json.writeValueAsString(value) + "}";
            sampler.addNonEncodedArgument("", body, "");
            JMeterTreeNode node = new JMeterTreeNode(sampler, null);
            assertTrue(ParameterScanner.scan(node).isEmpty());
            assertEquals(0, ParameterReplacement.replace(node,
                    List.of(new ParameterScanner.Candidate("other", value, "value")), true));
            assertEquals(body, sampler.getArguments().getArgument(0).getValue());
        }
    }
}
