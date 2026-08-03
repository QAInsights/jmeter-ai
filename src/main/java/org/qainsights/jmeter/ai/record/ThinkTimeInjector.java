package org.qainsights.jmeter.ai.record;

import java.io.File;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Computes gaps between steps and injects FlowControlAction think times into the JMX XML.
 */
public final class ThinkTimeInjector {

    public void injectThinkTimes(File jmxFile, List<StepMarker> markers) throws RecordingException {
        try {
            Document doc;
            try (java.io.InputStream is = new java.io.FileInputStream(jmxFile)) {
                doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
            }
            doc.getDocumentElement().normalize();

            for (int i = 0; i < markers.size() - 1; i++) {
                StepMarker current = markers.get(i);
                StepMarker next = markers.get(i + 1);

                if ("end".equals(current.type()) && "start".equals(next.type())) {
                    long gap = next.timestamp() - current.timestamp();
                    long thinkTime = calculateThinkTime(gap);
                    if (thinkTime > 0) {
                        injectPauseAfter(doc, current.name(), thinkTime);
                    }
                }
            }

            writeDocument(doc, jmxFile);
        } catch (Exception e) {
            throw new RecordingException("Failed to inject think times: " + e.getMessage(), e);
        }
    }

    private long calculateThinkTime(long gapMs) {
        double scale = parseDouble("jmeter.ai.record.think_time.scale", 1.0);
        long min = parseLong("jmeter.ai.record.think_time.min.ms", 0);
        long max = parseLong("jmeter.ai.record.think_time.max.ms", 10000);
        return Math.max(min, Math.min(Math.round(gapMs * scale), max));
    }

    /** Property parse with fallback - a malformed value must never break plan finalization. */
    private static double parseDouble(String key, double fallback) {
        try {
            return Double.parseDouble(AiConfig.getProperty(key, String.valueOf(fallback)).trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Property parse with fallback - a malformed value must never break plan finalization. */
    private static long parseLong(String key, long fallback) {
        try {
            return Long.parseLong(AiConfig.getProperty(key, String.valueOf(fallback)).trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void injectPauseAfter(Document doc, String stepName, long durationMs) {
        NodeList controllers = doc.getElementsByTagName("TransactionController");
        for (int i = 0; i < controllers.getLength(); i++) {
            Element tc = (Element) controllers.item(i);
            if (stepName.equals(tc.getAttribute("testname"))) {
                Node hashTree = findNextSiblingElement(tc, "hashTree");
                if (hashTree != null) {
                    insertTestAction(doc, hashTree, durationMs);
                }
            }
        }
    }

    private Node findNextSiblingElement(Node node, String tagName) {
        Node sibling = node.getNextSibling();
        while (sibling != null) {
            if (sibling.getNodeType() == Node.ELEMENT_NODE && tagName.equals(sibling.getNodeName())) {
                return sibling;
            }
            sibling = sibling.getNextSibling();
        }
        return null;
    }

    private void insertTestAction(Document doc, Node insertAfterNode, long durationMs) {
        Element testAction = doc.createElement("TestAction");
        testAction.setAttribute("guiclass", "TestActionGui");
        testAction.setAttribute("testclass", "TestAction");
        testAction.setAttribute("testname", "Flow Control Action");
        testAction.setAttribute("enabled", "true");

        addProperty(doc, testAction, "ActionProcessor.action", "1");
        addProperty(doc, testAction, "ActionProcessor.target", "0");
        addStringProperty(doc, testAction, "ActionProcessor.duration", String.valueOf(durationMs));

        Element emptyHashTree = doc.createElement("hashTree");

        Node parent = insertAfterNode.getParentNode();
        Node nextSibling = insertAfterNode.getNextSibling();
        parent.insertBefore(testAction, nextSibling);
        parent.insertBefore(emptyHashTree, testAction.getNextSibling());
    }

    private void addProperty(Document doc, Element parent, String name, String value) {
        Element prop = doc.createElement("intProp");
        prop.setAttribute("name", name);
        prop.setTextContent(value);
        parent.appendChild(prop);
    }

    private void addStringProperty(Document doc, Element parent, String name, String value) {
        Element prop = doc.createElement("stringProp");
        prop.setAttribute("name", name);
        prop.setTextContent(value);
        parent.appendChild(prop);
    }

    private void writeDocument(Document doc, File file) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
        try (java.io.OutputStream os = new java.io.FileOutputStream(file)) {
            transformer.transform(new DOMSource(doc), new StreamResult(os));
        }
    }
}
