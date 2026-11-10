package org.qainsights.jmeter.ai.param;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.config.ConfigElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class ParameterReplacement {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SKIPPED =
            Set.of(
                    TestElement.NAME,
                    TestElement.GUI_CLASS,
                    TestElement.TEST_CLASS,
                    TestElement.ENABLED,
                    TestElement.COMMENTS,
                    "Argument.name",
                    "Header.name");

    private ParameterReplacement() {}

    static int replace(
            JMeterTreeNode scope, List<ParameterScanner.Candidate> chosen, boolean apply) {
        int count = 0;
        Pattern literalPattern = literalPattern(chosen);
        Enumeration<?> nodes = scope.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            JMeterTreeNode node = (JMeterTreeNode) nodes.nextElement();
            if (node.getTestElement() instanceof CSVDataSet
                    || node.getTestElement() instanceof Arguments) {
                continue;
            }
            Set<JMeterProperty> bodies = Collections.newSetFromMap(new IdentityHashMap<>());
            if (node.getTestElement() instanceof HTTPSamplerBase) {
                HTTPSamplerBase http = (HTTPSamplerBase) node.getTestElement();
                if (http.getPostBodyRaw()) {
                    for (JMeterProperty property : http.getArguments().getArguments()) {
                        Argument argument = (Argument) property.getObjectValue();
                        bodies.add(argument.getProperty(Argument.VALUE));
                    }
                }
            }
            count += properties(node.getTestElement().propertyIterator(), bodies, chosen, apply,
                    node.getTestElement() instanceof ConfigElement, literalPattern,
                    node.getTestElement() instanceof HTTPSamplerBase);
        }
        return count;
    }

    private static int properties(
            PropertyIterator iterator,
            Set<JMeterProperty> bodies,
            List<ParameterScanner.Candidate> chosen,
            boolean apply, boolean config, Pattern literalPattern, boolean httpRoot) {
        int count = 0;
        while (iterator.hasNext()) {
            JMeterProperty property = iterator.next();
            if (httpRoot && !property.getName().equals("HTTPsampler.Arguments")
                    && !property.getName().equals("HTTPSampler.path")) {
                continue;
            }
            if (SKIPPED.contains(property.getName()) || ParameterPropertyPolicy.skip(property, config)) {
                continue;
            }
            if (property instanceof MultiProperty) {
                count += properties(((MultiProperty) property).iterator(), bodies, chosen, apply, config,
                        literalPattern, false);
            } else if (property instanceof StringProperty) {
                List<Edit> edits =
                        bodies.contains(property)
                                ? jsonEdits(property.getStringValue(), chosen)
                                : literalEdits(property.getStringValue(), chosen, literalPattern);
                count += edits.size();
                if (apply && !edits.isEmpty()) {
                    StringBuilder text = new StringBuilder(property.getStringValue());
                    for (int i = edits.size() - 1; i >= 0; i--) {
                        Edit edit = edits.get(i);
                        text.replace(edit.start, edit.end, edit.replacement);
                    }
                    property.setObjectValue(text.toString());
                }
            }
        }
        return count;
    }

    private static List<Edit> jsonEdits(String text, List<ParameterScanner.Candidate> chosen) {
        List<Edit> edits = new ArrayList<>();
        ParameterJson.Input input = ParameterJson.mask(text);
        try (JsonParser parser = JSON.getFactory().createParser(input.text())) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (!token.isScalarValue() || token == JsonToken.VALUE_NULL
                        || input.protectedToken(parser.getTokenLocation().getCharOffset())) {
                    continue;
                }
                String value = parser.getText();
                if (token == JsonToken.VALUE_STRING && !safeJsonString(value)) {
                    continue;
                }
                for (ParameterScanner.Candidate candidate : chosen) {
                    if (candidate.value.equals(value)) {
                        int start = (int) parser.getTokenLocation().getCharOffset();
                        int end = (int) parser.getCurrentLocation().getCharOffset();
                        String variable = "${" + candidate.variable + "}";
                        edits.add(
                                new Edit(
                                        start,
                                        end,
                                        token == JsonToken.VALUE_STRING
                                                ? JSON.writeValueAsString(variable)
                                                : variable));
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return edits;
    }

    static boolean safeJsonString(String value) {
        return value.chars().noneMatch(c -> c == '"' || c == '\\' || c < 0x20);
    }

    private static Pattern literalPattern(List<ParameterScanner.Candidate> chosen) {
        if (chosen.isEmpty()) {
            return Pattern.compile("(?!)");
        }
        String alternatives =
                chosen.stream()
                        .sorted(
                                Comparator.comparingInt(
                                                (ParameterScanner.Candidate c) -> c.value.length())
                                        .reversed())
                        .map(c -> Pattern.quote(c.value))
                        .collect(Collectors.joining("|"));
        return Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + alternatives + ")(?![\\p{L}\\p{N}_])");
    }

    private static List<Edit> literalEdits(String text, List<ParameterScanner.Candidate> chosen, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        java.util.BitSet protectedCharacters =
                org.qainsights.jmeter.ai.extract.ReplacementExpressions.protectedCharacters(text);
        List<Edit> edits = new ArrayList<>();
        while (matcher.find()) {
            int protectedStart = protectedCharacters.nextSetBit(matcher.start());
            if (protectedStart >= 0 && protectedStart < matcher.end()) {
                continue;
            }
            for (ParameterScanner.Candidate candidate : chosen) {
                if (candidate.value.equals(matcher.group())) {
                    edits.add(
                            new Edit(
                                    matcher.start(),
                                    matcher.end(),
                                    "${" + candidate.variable + "}"));
                    break;
                }
            }
        }
        return edits;
    }

    private static final class Edit {
        final int start;
        final int end;
        final String replacement;

        Edit(int start, int end, String replacement) {
            this.start = start;
            this.end = end;
            this.replacement = replacement;
        }
    }
}
