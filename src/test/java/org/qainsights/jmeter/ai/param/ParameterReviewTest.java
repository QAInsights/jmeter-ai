package org.qainsights.jmeter.ai.param;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Set;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import static org.mockito.Mockito.mock;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParameterReviewTest {
    @TempDir Path directory;

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
        planCsv.setVariableNames("user_2");
        root.add(new JMeterTreeNode(planCsv, null));
        JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), null);
        root.add(group);
        Arguments udv = new Arguments();
        udv.addArgument("user_3", "other");
        group.add(new JMeterTreeNode(udv, null));
        CSVDataSet csv = new CSVDataSet();
        csv.setVariableNames("user_4");
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
        csv.setVariableNames("old");
        csv.setDelimiter(";");
        csv.setFileEncoding("");
        csv.setRecycle(false);
        csv.setStopThread(true);
        csv.setShareMode("shareMode.thread");
        group.add(new JMeterTreeNode(csv, null));
        ParameterPlacement.apply(mock(JMeterTreeModel.class), scope, csv, "data.csv", ",",
                true, false, "shareMode.all", ParameterScanner.scan(scope));
        assertEquals(";", csv.getDelimiter());
        assertEquals("", csv.getFileEncoding());
        assertFalse(csv.getRecycle());
        assertTrue(csv.getStopThread());
        assertEquals("shareMode.thread", csv.getShareMode());
        assertEquals("old,user", csv.getVariableNames());
    }

    @Test
    void reuseOnlySelectsEnabledDirectChildren() {
        JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode controller = new JMeterTreeNode(new GenericController(), null);
        group.add(controller);
        CSVDataSet nested = new CSVDataSet();
        nested.setFilename("data.csv");
        nested.setEnabled(true);
        controller.add(new JMeterTreeNode(nested, null));
        assertNull(ParameterPlacement.reusable(group, directory, "data.csv"));
        CSVDataSet direct = new CSVDataSet();
        direct.setFilename("data.csv");
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
        csv.setFilename("data/1234.csv");
        csv.setVariableNames("1234");
        root.add(new JMeterTreeNode(csv, null));
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.addArgument("id", "1234");
        root.add(new JMeterTreeNode(sampler, null));
        List<ParameterScanner.Candidate> candidates = ParameterScanner.scan(root);
        assertEquals(List.of("1234"), candidates.stream().map(c -> c.value).toList());
        assertEquals(1, candidates.get(0).occurrences);
        assertEquals(1, ParameterReplacement.replace(root, candidates, true));
        assertEquals("data/1234.csv", csv.getFilename());
        assertEquals("1234", csv.getVariableNames());
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
