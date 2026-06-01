package org.qainsights.jmeter.ai.correlation;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreeNode;
import java.util.*;

public class CorrelationInjector {

    private static final Logger log = LoggerFactory.getLogger(CorrelationInjector.class);

    public int apply(List<CorrelationCandidate> candidates) {
        GuiPackage gui = GuiPackage.getInstance();
        JMeterTreeModel model = gui.getTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();

        int applied = 0;
        for (CorrelationCandidate c : candidates) {
            if (c.getStatus() != CorrelationCandidate.Status.APPROVED) continue;

            log.info("Applying correlation: {} -> {} (pattern: {})",
                    c.getSourceSamplerName(), c.getVariableName(), c.getExtractionPattern());

            JMeterTreeNode sourceNode = findNode(root, c.getSourceSamplerName());
            if (sourceNode == null) {
                log.warn("Source sampler '{}' not found in test plan tree. Available samplers:", c.getSourceSamplerName());
                dumpSamplers(root, "");
                continue;
            }

            TestElement extractor = createExtractor(c);
            if (extractor == null) {
                log.error("Failed to create extractor for {}", c.getVariableName());
                continue;
            }

            try {
                model.addComponent(extractor, sourceNode);
                log.info("Added {} extractor to '{}'", c.getExtractorType(), sourceNode.getName());
            } catch (Exception ex) {
                log.error("Failed to add extractor to '{}': {}", sourceNode.getName(), ex.getMessage());
                continue;
            }

            for (String targetName : c.getTargetSamplerNames()) {
                replaceInTarget(root, targetName, c.getParameterName(), c.getSampleValue(), c.getVariableName());
            }

            applied++;
        }
        gui.getMainFrame().repaint();
        return applied;
    }

    private void dumpSamplers(JMeterTreeNode node, String indent) {
        if (node.getTestElement() instanceof org.apache.jmeter.samplers.AbstractSampler) {
            log.warn("  {}'{}'", indent, node.getName());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TreeNode child = node.getChildAt(i);
            if (child instanceof JMeterTreeNode) {
                dumpSamplers((JMeterTreeNode) child, indent + "  ");
            }
        }
    }

    private JMeterTreeNode findNode(JMeterTreeNode node, String name) {
        if (node.getName().equals(name)) return node;
        // Also try matching just the last segment (e.g., "Catalog.action-4" from "test/actions/Catalog.action-4")
        if (name.contains("/") && node.getName().equals(name.substring(name.lastIndexOf('/') + 1))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            TreeNode child = node.getChildAt(i);
            if (child instanceof JMeterTreeNode) {
                JMeterTreeNode found = findNode((JMeterTreeNode) child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private TestElement createExtractor(CorrelationCandidate c) {
        if ("json".equals(c.getExtractorType())) {
            return createJsonExtractor(c);
        }
        return createRegexExtractor(c);
    }

    private TestElement createRegexExtractor(CorrelationCandidate c) {
        try {
            TestElement el = (TestElement) Class.forName("org.apache.jmeter.extractor.RegexExtractor")
                    .getDeclaredConstructor().newInstance();
            el.setName("Correlation - " + c.getVariableName());
            el.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.extractor.gui.RegexExtractorGui");
            el.setProperty("RegexExtractor.refname", c.getVariableName());
            el.setProperty("RegexExtractor.regex", c.getExtractionPattern());
            el.setProperty("RegexExtractor.template", "$1$");
            el.setProperty("RegexExtractor.default", "NOT_FOUND");
            el.setProperty("RegexExtractor.match_number", "1");
            return el;
        } catch (Exception e) {
            log.error("Failed to create RegexExtractor", e);
            return null;
        }
    }

    private TestElement createJsonExtractor(CorrelationCandidate c) {
        try {
            TestElement el = (TestElement) Class.forName("org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor")
                    .getDeclaredConstructor().newInstance();
            el.setName("Correlation - " + c.getVariableName());
            el.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.extractor.json.jsonpath.gui.JSONPostProcessorGui");
            el.setProperty("JSONPostProcessor.refnames", c.getVariableName());
            el.setProperty("JSONPostProcessor.jsonPathExprs", c.getExtractionPattern());
            el.setProperty("JSONPostProcessor.defaultValues", "NOT_FOUND");
            return el;
        } catch (Exception e) {
            log.error("Failed to create JSONPostProcessor", e);
            return null;
        }
    }

    private void replaceInTarget(JMeterTreeNode root, String targetName, String paramName, String value, String varName) {
        JMeterTreeNode node = findNode(root, targetName);
        if (node == null) {
            log.warn("Target sampler '{}' not found for replacement", targetName);
            return;
        }

        TestElement te = node.getTestElement();
        String replacement = "${" + varName + "}";
        boolean replaced = false;

        if (te instanceof HTTPSamplerBase) {
            HTTPSamplerBase http = (HTTPSamplerBase) te;

            // Replace in path
            String path = http.getPath();
            if (path != null && path.contains(value)) {
                http.setPath(path.replace(value, replacement));
                log.info("Replaced value in path of '{}'", targetName);
                replaced = true;
            }

            // Replace in arguments
            Arguments args = http.getArguments();
            if (args != null) {
                for (int i = 0; i < args.getArgumentCount(); i++) {
                    org.apache.jmeter.config.Argument arg = args.getArgument(i);
                    
                    // If argument name matches the parameter name, replace the entire value
                    if (arg.getName() != null && arg.getName().equalsIgnoreCase(paramName)) {
                        arg.setValue(replacement);
                        log.info("Replaced argument '{}' value with variable reference in '{}'", arg.getName(), targetName);
                        replaced = true;
                        continue;
                    }
                    
                    // Otherwise, check if the argument value contains the extracted value
                    if (arg.getValue() != null && arg.getValue().contains(value)) {
                        arg.setValue(arg.getValue().replace(value, replacement));
                        log.info("Replaced value in argument '{}' of '{}'", arg.getName(), targetName);
                        replaced = true;
                    }
                }
            }
        }

        // Scan all properties for the value
        for (org.apache.jmeter.testelement.property.PropertyIterator it = te.propertyIterator(); it.hasNext(); ) {
            org.apache.jmeter.testelement.property.JMeterProperty prop = it.next();
            String strVal = prop.getStringValue();
            if (strVal != null && strVal.contains(value)) {
                try {
                    prop.setObjectValue(strVal.replace(value, replacement));
                    log.info("Replaced value in property '{}' of '{}'", prop.getName(), targetName);
                    replaced = true;
                } catch (Exception ignored) {}
            }
        }
        
        if (!replaced) {
            log.warn("No replacements made in target sampler '{}' - parameter '{}' or value '{}' not found", 
                    targetName, paramName, value);
        }
    }
}
