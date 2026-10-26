package org.qainsights.jmeter.ai.load;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.modifiers.JSR223PreProcessor;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.jdbc.config.DataSourceElement;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadAiAssistantTest {
    private static final String VALID = """
            {"target_type":"samples_per_second","target_value":200,"rate_cap_per_second":null,
            "response_ms":null,"think_ms":null,"ramp_seconds":null,"hold_seconds":null,
            "samples_per_vcpu":null,"summary":"A valid plan.","reasoning":["200 orders / second"],
            "risks":["Confirm the response time."]}
            """;

    @Test
    void promptContainsOnlyCountsForPlanStructure() throws Exception {
        JMeterTreeModel model = new JMeterTreeModel();
        ThreadGroup group = new ThreadGroup();
        group.setSamplerController(new LoopController());
        JMeterTreeNode groupNode = model.addComponent(group,
                (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0));
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.setName("DistinctiveElementName");
        http.setProtocol("https");
        http.setDomain("distinctive-host.invalid");
        http.setPath("/distinctive-private-path");
        model.addComponent(http, groupNode);
        DataSourceElement datasource = new DataSourceElement();
        datasource.setProperty("dataSource", "DISTINCTIVE_VARIABLE_NAME");
        datasource.setProperty("poolMax", 7);
        model.addComponent(datasource, groupNode);
        JSR223PreProcessor script = new JSR223PreProcessor();
        script.setName("DistinctiveScriptElement");
        script.setProperty("script", "DISTINCTIVE_SCRIPT_BODY");
        model.addComponent(script, groupNode);

        String prompt = WorkloadAiAssistant.buildPrompt("a safe goal",
                Map.of("target_type", "samples_per_second"),
                LoadCommandHandler.planSummary(groupNode));

        assertTrue(prompt.contains("http_uses_https: true"));
        assertTrue(prompt.contains("samplers_per_iteration: 1"));
        assertTrue(prompt.contains("jdbc_pool_max_values: [7]"));
        assertFalse(prompt.contains("https://distinctive-host.invalid/distinctive-private-path"));
        assertFalse(prompt.contains("distinctive-host.invalid"));
        assertFalse(prompt.contains("/distinctive-private-path"));
        assertFalse(prompt.contains("DistinctiveElementName"));
        assertFalse(prompt.contains("DistinctiveScriptElement"));
        assertFalse(prompt.contains("DISTINCTIVE_VARIABLE_NAME"));
        assertFalse(prompt.contains("DISTINCTIVE_SCRIPT_BODY"));
    }

    @Test
    void parsesPlainAndFencedSuggestions() {
        assertEquals(WorkloadInputs.TargetType.REQUEST_RATE, WorkloadAiAssistant.parse(VALID).targetType());
        assertEquals(200.0, WorkloadAiAssistant.parse(VALID).targetValue());
        assertEquals("A valid plan.", WorkloadAiAssistant.parse(VALID).summary());
        assertEquals(200.0, WorkloadAiAssistant.parse("```json\n" + VALID + "\n```").targetValue());
    }

    @Test
    void rejectsUnsupportedTargetsNegativeAndNonFiniteNumbersAndExtraProse() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkloadAiAssistant.parse(VALID.replace("samples_per_second", "unknown")));
        assertThrows(IllegalArgumentException.class, () ->
                WorkloadAiAssistant.parse(VALID.replace("\"target_value\":200", "\"target_value\":-1")));
        assertThrows(IllegalArgumentException.class, () ->
                WorkloadAiAssistant.parse(VALID.replace("\"target_value\":200", "\"target_value\":NaN")));
        assertThrows(IllegalArgumentException.class, () -> WorkloadAiAssistant.parse("Suggestion: " + VALID));
    }

    @Test
    void limitsRisksToFiveAndThreeHundredCharactersEach() {
        String risks = "[\"" + "r".repeat(350) + "\",\"risk2\",\"risk3\",\"risk4\",\"risk5\",\"risk6\"]";
        WorkloadAiAssistant.Suggestion suggestion = WorkloadAiAssistant.parse(
                VALID.replace("[\"Confirm the response time.\"]", risks));
        assertEquals(5, suggestion.risks().size());
        assertEquals(300, suggestion.risks().get(0).length());
    }
}
