package org.qainsights.jmeter.ai.param;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.config.ConfigElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.qainsights.jmeter.ai.extract.ExtractPlacement;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic, scope-local candidates. No request is sent to a provider. */
public final class ParameterScanner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SKIP_HEADERS =
            Set.of(
                    "host",
                    "content-type",
                    "accept",
                    "accept-encoding",
                    "connection",
                    "user-agent",
                    "content-length");

    public static final class Candidate {
        public final String path;
        public final String value;
        public String variable;
        public int occurrences;

        Candidate(String path, String value, String variable) {
            this.path = path;
            this.value = value;
            this.variable = variable;
            this.occurrences = 1;
        }
    }

    private ParameterScanner() {}

    public static List<Candidate> scan(JMeterTreeNode scope) {
        Map<String, Candidate> found = new LinkedHashMap<>();
        Set<String> names = ParameterNames.used(scope);
        if (scope == null) {
            return new ArrayList<>();
        }
        Enumeration<?> nodes = scope.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            JMeterTreeNode node = (JMeterTreeNode) nodes.nextElement();
            TestElement element = node.getTestElement();
            if (element instanceof CSVDataSet || element instanceof Arguments) {
                continue;
            }
            String path = node.getName();
            if (element instanceof HTTPSamplerBase) {
                HTTPSamplerBase http = (HTTPSamplerBase) element;
                for (JMeterProperty property : http.getArguments().getArguments()) {
                    Argument arg = (Argument) property.getObjectValue();
                    if (http.getPostBodyRaw()) {
                        try (JsonParser parser = JSON.getFactory().createParser(arg.getValue())) {
                            JSON.readTree(arg.getValue());
                            parser.nextToken();
                            leaves(parser, path + " > body", "body", found, names);
                        } catch (Exception ignored) {
                            // Non-JSON raw bodies are not key/value structures.
                        }
                    } else {
                        add(
                                path + " > arguments > " + arg.getName(),
                                arg.getName(),
                                arg.getValue(),
                                found,
                                names);
                    }
                }
                int query = http.getPath().indexOf('?');
                if (query >= 0) {
                    for (String pair : http.getPath().substring(query + 1).split("&")) {
                        int equals = pair.indexOf('=');
                        if (equals > 0) {
                            add(
                                    path + " > query > " + pair.substring(0, equals),
                                    pair.substring(0, equals),
                                    pair.substring(equals + 1),
                                    found,
                                    names);
                        }
                    }
                }
                String urlPath = query < 0 ? http.getPath() : http.getPath().substring(0, query);
                for (String segment : urlPath.split("/")) {
                    if (segment.matches(
                            "[0-9]+|[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}|(?=[A-Za-z0-9]*[0-9])[A-Za-z0-9]{12,}")) {
                        add(path + " > path > id", "id", segment, found, names);
                    }
                }
            } else if (element instanceof HeaderManager) {
                HeaderManager headers = (HeaderManager) element;
                for (int i = 0; i < headers.size(); i++) {
                    Header header = headers.get(i);
                    if (!SKIP_HEADERS.contains(header.getName().toLowerCase(Locale.ROOT))) {
                        add(
                                path + " > " + header.getName(),
                                header.getName(),
                                header.getValue(),
                                found,
                                names);
                    }
                }
            } else {
                properties(
                        element.propertyIterator(), path, element instanceof Sampler,
                        element instanceof ConfigElement, found, names);
            }
        }
        for (Candidate candidate : found.values()) {
            candidate.occurrences = ParameterReplacement.replace(scope, List.of(candidate), false);
        }
        return new ArrayList<>(found.values());
    }

    private static void properties(
            PropertyIterator iterator,
            String path,
            boolean sampler,
            boolean config,
            Map<String, Candidate> found,
            Set<String> names) {
        while (iterator.hasNext()) {
            JMeterProperty property = iterator.next();
            if (ParameterPropertyPolicy.skip(property, config)) {
                continue;
            }
            if (property instanceof TestElementProperty
                    && property.getObjectValue() instanceof Argument) {
                Argument arg = (Argument) property.getObjectValue();
                add(path + " > " + arg.getName(), arg.getName(), arg.getValue(), found, names);
            } else if (property instanceof MultiProperty) {
                properties(((MultiProperty) property).iterator(), path, sampler, config, found, names);
            } else if (sampler && !property.getName().startsWith("TestElement.")) {
                String text = property.getStringValue();
                for (String pair : text.split("[&;\\r\\n]")) {
                    int equals = pair.indexOf('=');
                    if (equals > 0) {
                        String key = pair.substring(0, equals).trim();
                        if (key.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
                            add(
                                    path + " > " + property.getName() + " > " + key,
                                    key,
                                    pair.substring(equals + 1).trim(),
                                    found,
                                    names);
                        }
                    }
                }
            }
        }
    }

    private static void leaves(
            JsonParser parser,
            String path,
            String key,
            Map<String, Candidate> found,
            Set<String> names) throws java.io.IOException {
        JsonToken token = parser.currentToken();
        if (token == null || token == JsonToken.VALUE_NULL) {
            return;
        }
        if (token == JsonToken.START_OBJECT) {
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                leaves(parser, path + " > " + field, field, found, names);
            }
        } else if (token == JsonToken.START_ARRAY) {
            int i = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                leaves(parser, path + " > " + i++, key, found, names);
            }
        } else {
            String value = parser.getText();
            if (token == JsonToken.VALUE_STRING && !ParameterReplacement.safeJsonString(value)) {
                return;
            }
            add(path, key, value, found, names);
        }
    }

    private static void add(
            String path,
            String key,
            String value,
            Map<String, Candidate> found,
            Set<String> names) {
        if (value == null
                || value.length() < ExtractPlacement.MIN_REPLACE_LENGTH
                || value.contains("${")) {
            return;
        }
        Candidate existing = found.get(value);
        if (existing != null) {
            existing.occurrences++;
        } else {
            found.put(value, new Candidate(path, value, uniqueName(key, names)));
        }
    }

    public static String uniqueName(String key, Set<String> used) {
        String base = key.replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isEmpty()) {
            base = "value";
        }
        if (Character.isDigit(base.charAt(0))) {
            base = "value_" + base;
        }
        String name = base;
        for (int i = 2; !used.add(name); i++) {
            name = base + "_" + i;
        }
        return name;
    }
}
