package org.qainsights.jmeter.ai.sla;

import java.util.List;

import org.apache.jmeter.assertions.DurationAssertion;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.config.ConfigTestElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlaCommandHandlerTest {

    @Test
    void buildsDurationAndNegatedResponseCodeAssertions() {
        SlaParser.SlaSpec spec = SlaParser.parse("@sla p95 under 800 ms and no 5xx");

        List<TestElement> elements = SlaCommandHandler.buildAssertions(spec, "@sla p95 under 800 ms and no 5xx");

        assertEquals(2, elements.size());
        DurationAssertion duration = (DurationAssertion) elements.get(0);
        assertEquals(800, duration.getPropertyAsLong(DurationAssertion.DURATION_KEY));
        assertEquals(SlaCommandHandler.DURATION_GUI, duration.getPropertyAsString(TestElement.GUI_CLASS));
        assertEquals("SLA: each sample under 800 ms", duration.getName());

        ResponseAssertion codes = (ResponseAssertion) elements.get(1);
        assertTrue(codes.isTestFieldResponseCode());
        assertTrue(codes.isMatchType());
        assertTrue(codes.isNotType());
        assertEquals("5\\d\\d", codes.getTestStrings().get(0).getStringValue());
        assertEquals(SlaCommandHandler.RESPONSE_GUI, codes.getPropertyAsString(TestElement.GUI_CLASS));
        assertTrue(codes.getComment().contains("p95 under 800 ms"));
    }

    @Test
    void exactCodeUsesEqualsWithoutNot() {
        List<TestElement> elements = SlaCommandHandler.buildAssertions(SlaParser.parse("@sla status 200"), "@sla status 200");

        ResponseAssertion codes = (ResponseAssertion) elements.get(0);
        assertTrue(codes.isEqualsType());
        assertFalse(codes.isNotType());
        assertEquals("200", codes.getTestStrings().get(0).getStringValue());
    }

    @Test
    void replyExplainsPerSampleCheckForPercentiles() {
        SlaParser.SlaSpec spec = SlaParser.parse("@sla p95 under 1.5 s and error rate under 1%");
        String reply = SlaCommandHandler.reply(spec, "Checkout",
                SlaCommandHandler.buildAssertions(spec, "x"));

        assertTrue(reply.contains("Added 1 assertion under **Checkout**"));
        assertTrue(reply.contains("not the p95"));
        assertTrue(reply.contains("1.5 s"));
        assertTrue(reply.contains("Not added: \"error rate under 1%\""));
    }

    @Test
    void onlySamplersControllersAndTestPlanCanHoldAssertions() {
        assertTrue(SlaCommandHandler.canHoldAssertions(new HTTPSamplerProxy()));
        assertTrue(SlaCommandHandler.canHoldAssertions(new LoopController()));
        assertTrue(SlaCommandHandler.canHoldAssertions(new TestPlan()));
        assertFalse(SlaCommandHandler.canHoldAssertions(new ConfigTestElement()));
        assertFalse(SlaCommandHandler.canHoldAssertions(null));
    }

    @Test
    void emptySpecReturnsHelpWithoutTouchingTheTree() {
        String reply = new SlaCommandHandler().process("@sla");

        assertTrue(reply.contains("@sla under 800 ms and no 5xx"));
    }

    @Test
    void formatsMillis() {
        assertEquals("800 ms", SlaCommandHandler.formatMillis(800));
        assertEquals("2 s", SlaCommandHandler.formatMillis(2000));
        assertEquals("1.5 s", SlaCommandHandler.formatMillis(1500));
        assertEquals("1250 ms", SlaCommandHandler.formatMillis(1250));
    }
}
