package org.qainsights.jmeter.ai.param;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParameterReviewTest {
    @TempDir Path directory;

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
