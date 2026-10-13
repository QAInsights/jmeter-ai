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

    @Test
    void undoLastRemovesOnlyTheAssertionsItAdded() throws Exception {
        org.apache.jmeter.gui.tree.JMeterTreeModel model = new org.apache.jmeter.gui.tree.JMeterTreeModel();
        org.apache.jmeter.gui.tree.JMeterTreeNode plan =
                (org.apache.jmeter.gui.tree.JMeterTreeNode) ((org.apache.jmeter.gui.tree.JMeterTreeNode) model.getRoot()).getChildAt(0);
        org.apache.jmeter.gui.tree.JMeterTreeNode controller = model.addComponent(new LoopController(), plan);
        HTTPSamplerProxy existing = new HTTPSamplerProxy();
        existing.setName("Existing sampler");
        model.addComponent(existing, controller);
        SlaParser.SlaSpec spec = SlaParser.parse("@sla under 800 ms and no 5xx");

        SlaCommandHandler.addAll(model, controller, SlaCommandHandler.buildAssertions(spec, "x"));
        assertEquals(3, controller.getChildCount());

        assertTrue(SlaCommandHandler.undoLast(model).contains("Removed 2 SLA assertions"));
        assertEquals(1, controller.getChildCount());
        assertEquals("Existing sampler",
                ((org.apache.jmeter.gui.tree.JMeterTreeNode) controller.getChildAt(0)).getName());
        assertEquals(1, plan.getChildCount());
        assertEquals(SlaCommandHandler.NOTHING_TO_UNDO, SlaCommandHandler.undoLast(model));
    }

    @Test
    void processAddsAssertionsUnderTheGivenNodeOnly() throws Exception {
        org.apache.jmeter.gui.tree.JMeterTreeModel model = new org.apache.jmeter.gui.tree.JMeterTreeModel();
        org.apache.jmeter.gui.tree.JMeterTreeNode plan =
                (org.apache.jmeter.gui.tree.JMeterTreeNode) ((org.apache.jmeter.gui.tree.JMeterTreeNode) model.getRoot()).getChildAt(0);
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("Login");
        org.apache.jmeter.gui.tree.JMeterTreeNode samplerNode = model.addComponent(sampler, plan);
        org.apache.jmeter.gui.tree.JMeterTreeNode configNode = model.addComponent(new ConfigTestElement(), plan);

        String reply = new SlaCommandHandler().process("@sla p95 under 800 ms and no 5xx", samplerNode, model);

        assertTrue(reply.contains("Added 2 assertions under **Login**"), reply);
        assertEquals(2, samplerNode.getChildCount());
        assertTrue(((org.apache.jmeter.gui.tree.JMeterTreeNode) samplerNode.getChildAt(0)).getTestElement()
                instanceof DurationAssertion);
        assertTrue(((org.apache.jmeter.gui.tree.JMeterTreeNode) samplerNode.getChildAt(1)).getTestElement()
                instanceof ResponseAssertion);

        String refused = new SlaCommandHandler().process("@sla under 800 ms", configNode, model);
        assertTrue(refused.startsWith("Select a sampler"), refused);
        assertEquals(0, configNode.getChildCount());
        assertEquals(2, samplerNode.getChildCount());
    }

    @Test
    void expectedErrorCodeLetsTheAssertionDecideSuccess() {
        ResponseAssertion notFound = (ResponseAssertion) SlaCommandHandler.buildAssertions(
                SlaParser.parse("@sla status 404"), "x").get(0);
        ResponseAssertion ok = (ResponseAssertion) SlaCommandHandler.buildAssertions(
                SlaParser.parse("@sla status 200"), "x").get(0);
        ResponseAssertion no5xx = (ResponseAssertion) SlaCommandHandler.buildAssertions(
                SlaParser.parse("@sla no 5xx"), "x").get(0);

        assertTrue(notFound.getAssumeSuccess());
        assertFalse(ok.getAssumeSuccess());
        assertFalse(no5xx.getAssumeSuccess());
    }

    @Test
    void replyReportsSupersededLimitsAndConflicts() {
        SlaParser.SlaSpec spec = SlaParser.parse("@sla p95 under 1.5 s and avg under 800 ms, status 500 and no 5xx");

        String reply = SlaCommandHandler.reply(spec, "Login", SlaCommandHandler.buildAssertions(spec, "x"));

        assertTrue(reply.contains("Also asked for: \"p95 under 1.5 s\""), reply);
        assertTrue(reply.contains("No response-code assertion added"), reply);
        assertTrue(reply.contains("status 500 vs no 5xx response codes"), reply);
    }
}
