package org.qainsights.jmeter.ai.param;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.java.sampler.JavaSampler;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qainsights.jmeter.ai.extract.ExtractPlacement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

class ParameterizeTest {
    @TempDir Path directory;

    @BeforeAll
    static void init() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    private JMeterTreeNode node(TestElement element) {
        return new JMeterTreeNode(element, null);
    }

    private Set<String> values(JMeterTreeNode scope) {
        return ParameterScanner.scan(scope).stream().map(c -> c.value).collect(Collectors.toSet());
    }

    @Test
    void argumentsDedupeAndSkips() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("Login");
        sampler.addArgument("username", "alice");
        sampler.addArgument("again", "alice");
        sampler.addArgument("short", "abc");
        sampler.addArgument("dynamic", "prefix${token}");
        List<ParameterScanner.Candidate> candidates = ParameterScanner.scan(node(sampler));
        assertEquals(1, candidates.size());
        assertEquals(2, candidates.get(0).occurrences);
        assertEquals("username", candidates.get(0).variable);
        assertEquals("Login > arguments > username", candidates.get(0).path);
    }

    @Test
    void jsonLeavesAndInvalidBody() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("Login");
        sampler.setPostBodyRaw(true);
        sampler.addNonEncodedArgument(
                "",
                "{\"username\":\"alice\",\"ids\":[12345,67890],\"none\":null,\"skip\":\"${foo}\"}",
                "");
        assertEquals(Set.of("alice", "12345", "67890"), values(node(sampler)));
        sampler.getArguments().removeAllArguments();
        sampler.addNonEncodedArgument("", "not json", "");
        assertTrue(values(node(sampler)).isEmpty());
    }

    @Test
    void paths() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setPath(
                "/orders/12345/550e8400-e29b-41d4-a716-446655440000/abcdef1234567890/static");
        assertEquals(
                Set.of("12345", "550e8400-e29b-41d4-a716-446655440000", "abcdef1234567890"),
                values(node(sampler)));
    }

    @Test
    void headersAndVariables() {
        JMeterTreeNode scope = node(new ThreadGroup());
        HeaderManager headers = new HeaderManager();
        headers.add(new Header("Authorization", "Bearer abcdef"));
        headers.add(new Header("Content-Type", "application/json"));
        headers.add(new Header("HOST", "example.org"));
        headers.add(new Header("User-Agent", "browser"));
        scope.add(node(headers));
        Arguments vars = new Arguments();
        vars.addArgument("username", "alice");
        vars.addArgument("skip", "${token}");
        scope.add(node(vars));
        assertEquals(Set.of("Bearer abcdef"), values(scope));
    }

    @Test
    void genericSamplerKeyValues() {
        JavaSampler sampler = new JavaSampler();
        sampler.setProperty(
                "params", "username=alice&password=secret;token=${existing}\nshort=abc");
        assertEquals(Set.of("alice", "secret"), values(node(sampler)));
    }

    @Test
    void emptyScopeAndNames() {
        assertTrue(ParameterScanner.scan(null).isEmpty());
        assertTrue(values(node(new ThreadGroup())).isEmpty());
        Set<String> used = new HashSet<>();
        assertEquals("user_name", ParameterScanner.uniqueName("user-name", used));
        assertEquals("user_name_2", ParameterScanner.uniqueName("user name", used));
        assertEquals("value_123", ParameterScanner.uniqueName("123", used));
        assertEquals("value", ParameterScanner.uniqueName("", used));
    }

    @Test
    void csvQuotingAppendingAndRealReader() throws Exception {
        Path file = directory.resolve("data.csv");
        String value = "a,\"quoted\"\nvalue";
        ParameterCsv.write(file, ',', List.of("username"), List.of(value), List.of());
        ParameterCsv.write(file, ',', List.of("password"), List.of("secret"), List.of("username"));
        assertTrue(Files.readString(file).contains("\"username\",\"password\""));
        CSVDataSet csv = new CSVDataSet();
        csv.setFilename(file.toString());
        csv.setVariableNames("username,password");
        csv.setDelimiter(",");
        csv.setQuotedData(true);
        csv.setIgnoreFirstLine(true);
        csv.setFileEncoding("UTF-8");
        csv.setRecycle(true);
        csv.setShareMode("shareMode.all");
        JMeterVariables variables = new JMeterVariables();
        JMeterContextService.getContext().setVariables(variables);
        try {
            csv.iterationStart(new LoopIterationEvent(csv, 1));
            assertEquals(value, variables.get("username"));
            assertEquals("secret", variables.get("password"));
        } finally {
            FileServer.getFileServer().closeFiles();
            JMeterContextService.getContext().clear();
        }
    }

    @Test
    void csvAppendErrorsDoNotOverwrite() throws Exception {
        Path file = directory.resolve("data.csv");
        assertThrows(
                IllegalArgumentException.class,
                () -> ParameterCsv.write(file, ',', List.of(), List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ParameterCsv.write(file, '"', List.of("new"), List.of("value"), List.of()));
        assertThrows(
                Exception.class,
                () ->
                        ParameterCsv.write(
                                file, ',', List.of("new"), List.of("value"), List.of("old")));
        Files.writeString(file, "wrong\nvalue\n");
        assertThrows(
                Exception.class,
                () ->
                        ParameterCsv.write(
                                file, ',', List.of("new"), List.of("value"), List.of("old")));
        assertEquals("wrong\nvalue\n", Files.readString(file));
    }

    @Test
    void csvPreservesRowsWithAlternateDelimiter() throws Exception {
        Path file = directory.resolve("data.csv");
        Files.writeString(file, "old\nfirst\nsecond\n");
        ParameterCsv.write(file, ';', List.of("new"), List.of("a;b"), List.of("old"));
        assertEquals(
                "old;\"new\"\nfirst;\"a;b\"\nsecond;\"a;b\"\n", Files.readString(file));
    }

    @Test
    void csvAddsHeaderToExistingDataAndReadsInferredNames() throws Exception {
        Path file = directory.resolve("data.csv");
        Files.writeString(file, "first\nsecond\n");
        ParameterCsv.write(file, ',', List.of("new"), List.of("value"), List.of("old"), false);
        assertEquals(List.of("old", "new"), ParameterCsv.header(file, ','));
        assertEquals(
                "\"old\",\"new\"\nfirst,\"value\"\nsecond,\"value\"\n",
                Files.readString(file));
    }

    @Test
    void applyAndReuse() throws Exception {
        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
        ThreadGroup tg = new ThreadGroup();
        tg.setName("Users");
        tg.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui");
        JMeterTreeNode group = model.addComponent(tg, plan);
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.setName("Login");
        http.addArgument("user", "alice");
        http.setProperty(
                TestElement.GUI_CLASS,
                "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        JMeterTreeNode sampler = model.addComponent(http, group);
        List<ParameterScanner.Candidate> candidates = ParameterScanner.scan(sampler);
        assertEquals(
                1,
                ParameterPlacement.apply(
                        model,
                        sampler,
                        null,
                        "data/users.csv",
                        ",",
                        true,
                        false,
                        "shareMode.all",
                        candidates));
        assertEquals("${user}", http.getArguments().getArgument(0).getValue());
        CSVDataSet csv = (CSVDataSet) ((JMeterTreeNode) group.getChildAt(0)).getTestElement();
        assertTrue(csv.getPropertyAsBoolean("ignoreFirstLine"));
        assertTrue(csv.getPropertyAsBoolean("quotedData"));
        assertEquals("user", csv.getPropertyAsString("variableNames"));
        assertSame(csv, ParameterPlacement.reusable(group, directory, "data/../data/users.csv"));
        assertNull(ParameterPlacement.reusable(group, directory, "other.csv"));
        http.addArgument("password", "secret");
        ParameterPlacement.apply(
                model,
                sampler,
                csv,
                "data/users.csv",
                ",",
                true,
                false,
                "shareMode.all",
                ParameterScanner.scan(sampler));
        assertEquals("user,password", csv.getPropertyAsString("variableNames"));
        assertEquals(2, group.getChildCount());
        csv.setEnabled(false);
        assertNull(ParameterPlacement.reusable(group, directory, "data/users.csv"));
    }

    @Test
    void replacementPreservesExistingExpressionsAndShortValues() {
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.setPath("/${alice}/alice/abc");
        assertEquals(1, ExtractPlacement.replace(List.of(node(http)), "alice", "username"));
        assertEquals("/${alice}/${username}/abc", http.getPath());
        assertEquals(0, ExtractPlacement.replace(List.of(node(http)), "abc", "short"));
    }

    @Test
    void applyEscapedJsonAndNumericTypes() throws Exception {
        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
        ThreadGroup tg = new ThreadGroup();
        tg.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui");
        JMeterTreeNode group = model.addComponent(tg, plan);
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.setPostBodyRaw(true);
        http.setProperty(
                TestElement.GUI_CLASS,
                "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        http.addNonEncodedArgument(
                "", "{\"username\":\"a\\\"bcd\",\"id\":12345,\"active\":true}", "");
        JMeterTreeNode sampler = model.addComponent(http, group);
        assertEquals(
                2,
                ParameterPlacement.apply(
                        model,
                        sampler,
                        null,
                        "data/users.csv",
                        ",",
                        true,
                        false,
                        "shareMode.all",
                        ParameterScanner.scan(sampler)));
        assertEquals(
                "{\"username\":\"a\\\"bcd\",\"id\":${id},\"active\":${active}}",
                http.getArguments().getArgument(0).getValue());
    }
}
