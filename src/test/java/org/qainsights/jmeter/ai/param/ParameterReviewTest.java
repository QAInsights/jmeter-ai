package org.qainsights.jmeter.ai.param;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParameterReviewTest {
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
