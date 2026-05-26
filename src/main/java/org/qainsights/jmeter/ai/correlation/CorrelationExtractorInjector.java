package org.qainsights.jmeter.ai.correlation;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreeNode;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class CorrelationExtractorInjector {
    private static final Logger log = LoggerFactory.getLogger(CorrelationExtractorInjector.class);

    public int applyApproved(List<CorrelationCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null || guiPackage.getTreeModel() == null) {
            throw new PluginException("JMeter GUI is not available.");
        }
        Object root = guiPackage.getTreeModel().getRoot();
        if (!(root instanceof JMeterTreeNode)) {
            throw new PluginException("JMeter test plan tree is not available.");
        }

        int applied = 0;
        for (CorrelationCandidate candidate : candidates) {
            if (candidate.getStatus() != CandidateStatus.APPROVED) {
                continue;
            }
            Optional<JMeterTreeNode> sourceNode = findNodeByName((JMeterTreeNode) root, candidate.getSourceResponse().getLabel());
            if (sourceNode.isEmpty()) {
                log.warn("Unable to find source sampler for correlation candidate: {}", candidate.getSourceResponse().getLabel());
                continue;
            }
            TestElement extractor = createExtractor(candidate.getSuggestion());
            try {
                guiPackage.getTreeModel().addComponent(extractor, sourceNode.get());
                guiPackage.getTreeModel().nodeStructureChanged(sourceNode.get());
                applied++;
            } catch (Exception e) {
                log.error("Failed to add extractor to sampler: {}", sourceNode.get().getName(), e);
                throw new PluginException("Failed to add extractor to sampler: " + sourceNode.get().getName(), e);
            }
        }
        guiPackage.getMainFrame().repaint();
        return applied;
    }

    private static Optional<JMeterTreeNode> findNodeByName(JMeterTreeNode node, String name) {
        if (node.getName().equals(name)) {
            return Optional.of(node);
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            TreeNode child = node.getChildAt(index);
            if (child instanceof JMeterTreeNode) {
                Optional<JMeterTreeNode> found = findNodeByName((JMeterTreeNode) child, name);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static TestElement createExtractor(ExtractorSuggestion suggestion) {
        try {
            switch (suggestion.getExtractorType()) {
                case JSON_PATH:
                    return createJsonPathExtractor(suggestion);
                case BOUNDARY:
                    return createBoundaryExtractor(suggestion);
                case REGEX:
                default:
                    return createRegexExtractor(suggestion);
            }
        } catch (Exception e) {
            log.error("Failed to create extractor", e);
            throw new PluginException("Failed to create extractor: " + e.getMessage(), e);
        }
    }

    private static TestElement createRegexExtractor(ExtractorSuggestion suggestion) throws Exception {
        TestElement element = instantiate("org.apache.jmeter.extractor.RegexExtractor", "org.apache.jmeter.extractor.gui.RegexExtractorGui");
        element.setName("Correlation - " + suggestion.getVariableName());
        invokeOrSet(element, "setRefName", "RegexExtractor.refname", suggestion.getVariableName());
        invokeOrSet(element, "setRegex", "RegexExtractor.regex", suggestion.getExpression());
        invokeOrSet(element, "setTemplate", "RegexExtractor.template", "$1$");
        invokeOrSet(element, "setDefaultValue", "RegexExtractor.default", "NOT_FOUND");
        invokeOrSet(element, "setMatchNumber", "RegexExtractor.match_number", suggestion.getMatchNo());
        return element;
    }

    private static TestElement createJsonPathExtractor(ExtractorSuggestion suggestion) throws Exception {
        TestElement element = instantiate("org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor", "org.apache.jmeter.extractor.json.jsonpath.gui.JSONPostProcessorGui");
        element.setName("Correlation - " + suggestion.getVariableName());
        invokeOrSet(element, "setRefNames", "JSONPostProcessor.referenceNames", suggestion.getVariableName());
        invokeOrSet(element, "setJsonPathExpressions", "JSONPostProcessor.jsonPathExprs", suggestion.getExpression());
        invokeOrSet(element, "setMatchNumbers", "JSONPostProcessor.match_numbers", suggestion.getMatchNo());
        invokeOrSet(element, "setDefaultValues", "JSONPostProcessor.defaultValues", "NOT_FOUND");
        return element;
    }

    private static TestElement createBoundaryExtractor(ExtractorSuggestion suggestion) throws Exception {
        TestElement element = instantiate("org.apache.jmeter.extractor.BoundaryExtractor", "org.apache.jmeter.extractor.gui.BoundaryExtractorGui");
        String[] boundaries = splitBoundaryExpression(suggestion.getExpression());
        element.setName("Correlation - " + suggestion.getVariableName());
        invokeOrSet(element, "setRefName", "BoundaryExtractor.refname", suggestion.getVariableName());
        invokeOrSet(element, "setLeftBoundary", "BoundaryExtractor.lboundary", boundaries[0]);
        invokeOrSet(element, "setRightBoundary", "BoundaryExtractor.rboundary", boundaries[1]);
        invokeOrSet(element, "setDefaultValue", "BoundaryExtractor.default", "NOT_FOUND");
        invokeOrSet(element, "setMatchNumber", "BoundaryExtractor.match_number", suggestion.getMatchNo());
        return element;
    }

    private static TestElement instantiate(String modelClassName, String guiClassName) throws Exception {
        Class<?> elementClass = Class.forName(modelClassName);
        TestElement element = elementClass.asSubclass(TestElement.class).getDeclaredConstructor().newInstance();
        element.setProperty(TestElement.TEST_CLASS, modelClassName);
        element.setProperty(TestElement.GUI_CLASS, guiClassName);
        return element;
    }

    private static void invokeOrSet(TestElement element, String methodName, String propertyName, String value) {
        try {
            Method method = element.getClass().getMethod(methodName, String.class);
            method.invoke(element, value);
        } catch (ReflectiveOperationException e) {
            element.setProperty(propertyName, value);
        }
    }

    private static String[] splitBoundaryExpression(String expression) {
        String safeExpression = expression == null ? "" : expression;
        if (safeExpression.contains("||")) {
            String[] parts = safeExpression.split("\\|\\|", 2);
            return new String[]{parts[0], parts.length > 1 ? parts[1] : ""};
        }
        int groupIndex = safeExpression.indexOf("(.+?)");
        if (groupIndex >= 0) {
            return new String[]{safeExpression.substring(0, groupIndex), safeExpression.substring(groupIndex + 5)};
        }
        return new String[]{safeExpression, ""};
    }
}
