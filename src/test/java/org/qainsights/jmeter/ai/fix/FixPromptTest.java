package org.qainsights.jmeter.ai.fix;

import java.net.URL;
import java.util.List;

import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FixPromptTest {
    @Test
    void includesRulesAndOnlyRedactedFailureDiagnostics() throws Exception {
        HTTPSampleResult sample = new HTTPSampleResult();
        sample.setSampleLabel("Login");
        sample.setSuccessful(false);
        sample.setResponseCode("401");
        sample.setResponseMessage("Rejected https://example.test/login?token=raw-query-token");
        sample.setHTTPMethod("POST");
        sample.setURL(new URL("https://example.test/login?token=raw-query-token"));
        sample.setResponseData("{\"password\":\"raw-password\"}", "UTF-8");
        String prompt = new FixPrompt().build(TestRunSummary.of(List.of(sample), false, 0));

        assertTrue(prompt.contains("Make every request"));
        assertTrue(prompt.contains("find_correlation_candidates"));
        assertTrue(prompt.contains("Never delete, disable, or move requests"));
        assertTrue(prompt.contains("NEEDS USER:"));
        assertTrue(prompt.contains("POST"));
        assertTrue(prompt.contains("Login"));
        assertFalse(prompt.contains("raw-password"));
        assertFalse(prompt.contains("raw-query-token"));
    }
}
