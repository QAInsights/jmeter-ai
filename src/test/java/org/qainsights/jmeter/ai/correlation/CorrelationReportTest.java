package org.qainsights.jmeter.ai.correlation;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationReportTest {

    private static CorrelationCandidate candidate() {
        CorrelationCandidate c = new CorrelationCandidate();
        c.setParameterName("csrfToken");
        c.setVariableName("csrfToken");
        c.setSampleValue("abc123DEF456");
        c.setSourceSamplerIndex(0);
        c.setSourceSamplerName("GET /login");
        c.setSourceLocation("response body (JSON)");
        c.setExtractorType("json");
        c.setExtractionPattern("$..csrfToken");
        c.addTargetSamplerName("POST /submit");
        c.addTargetSamplerIndex(1);
        return c;
    }

    @Test
    void markdown_listsCandidateDetails() {
        String md = new CorrelationReport(Collections.singletonList(candidate())).render("md");
        assertTrue(md.contains("# Correlation Autopilot Report"), md);
        assertTrue(md.contains("Found **1**"), md);
        assertTrue(md.contains("${csrfToken}"), md);
        assertTrue(md.contains("$..csrfToken"), md);
        assertTrue(md.contains("POST /submit"), md);
    }

    @Test
    void markdown_emptyIsHandled() {
        String md = new CorrelationReport(Collections.<CorrelationCandidate>emptyList()).render("md");
        assertTrue(md.contains("No dynamic values detected"), md);
    }

    @Test
    void json_isWellFormed() {
        String json = new CorrelationReport(Collections.singletonList(candidate())).render("json");
        assertTrue(json.startsWith("{") && json.endsWith("}"), json);
        assertTrue(json.contains("\"count\":1"), json);
        assertTrue(json.contains("\"parameter\":\"csrfToken\""), json);
        assertTrue(json.contains("\"usageCount\":1"), json);
    }
}
