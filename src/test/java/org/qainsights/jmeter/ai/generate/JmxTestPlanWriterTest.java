package org.qainsights.jmeter.ai.generate;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class JmxTestPlanWriterTest {

    @Test
    void producesWellFormedJmxWithSamplerAndHeaders() throws Exception {
        TestPlanModel model = new TestPlanModel("Demo");
        model.add(new HttpRequestSpec()
                .setMethod("post").setProtocol("https").setDomain("api.example.com")
                .setPort(443).setPath("/login").setBody("{\"u\":\"a\"}")
                .addHeader("Content-Type", "application/json"));

        String jmx = JmxTestPlanWriter.write(model);

        // Must be well-formed XML.
        Document doc = parse(jmx);
        assertEquals("jmeterTestPlan", doc.getDocumentElement().getNodeName());

        assertTrue(jmx.contains("<HTTPSamplerProxy"), jmx);
        assertTrue(jmx.contains("<stringProp name=\"HTTPSampler.domain\">api.example.com</stringProp>"), jmx);
        assertTrue(jmx.contains("<stringProp name=\"HTTPSampler.path\">/login</stringProp>"), jmx);
        assertTrue(jmx.contains("<stringProp name=\"HTTPSampler.method\">POST</stringProp>"), jmx);
        assertTrue(jmx.contains("<boolProp name=\"HTTPSampler.postBodyRaw\">true</boolProp>"), jmx);
        assertTrue(jmx.contains("HTTP Header Manager"), jmx);
        assertTrue(jmx.contains("Content-Type"), jmx);
    }

    @Test
    void escapesXmlSpecialCharactersInValues() throws Exception {
        TestPlanModel model = new TestPlanModel("A & B <plan>");
        model.add(new HttpRequestSpec().setMethod("GET").setDomain("h").setPath("/a?x=1&y=2<z>"));

        String jmx = JmxTestPlanWriter.write(model);
        assertTrue(jmx.contains("/a?x=1&amp;y=2&lt;z&gt;"), jmx);
        assertTrue(jmx.contains("A &amp; B &lt;plan&gt;"), jmx);
        // Still well-formed despite the special chars.
        assertNotNull(parse(jmx));
    }

    @Test
    void omitsHeaderManagerWhenNoHeaders() {
        TestPlanModel model = new TestPlanModel("NoHeaders");
        model.add(new HttpRequestSpec().setMethod("GET").setDomain("h").setPath("/"));
        String jmx = JmxTestPlanWriter.write(model);
        assertFalse(jmx.contains("HeaderManager"), jmx);
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
