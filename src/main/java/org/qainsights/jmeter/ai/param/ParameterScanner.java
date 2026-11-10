package org.qainsights.jmeter.ai.param;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.jmeter.config.Argument;
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
import java.util.HashSet;
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
        Set<String> names = new HashSet<>();
        if (scope == null) {
            return new ArrayList<>();
        }
        Enumeration<?> nodes = scope.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            JMeterTreeNode node = (JMeterTreeNode) nodes.nextElement();
            TestElement element = node.getTestElement();
            String path = node.getName();
            if (element instanceof HTTPSamplerBase) {
                HTTPSamplerBase http = (HTTPSamplerBase) element;
                for (JMeterProperty property : http.getArguments().getArguments()) {
                    Argument arg = (Argument) property.getObjectValue();
                    if (http.getPostBodyRaw()) {
                        try {
                            leaves(
                                    JSON.readTree(arg.getValue()),
                                    path + " > body",
                                    "body",
                                    found,
                                    names);
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
                        element.propertyIterator(), path, element instanceof Sampler, found, names);
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
            Map<String, Candidate> found,
            Set<String> names) {
        while (iterator.hasNext()) {
            JMeterProperty property = iterator.next();
            if (property instanceof TestElementProperty
                    && property.getObjectValue() instanceof Argument) {
                Argument arg = (Argument) property.getObjectValue();
                add(path + " > " + arg.getName(), arg.getName(), arg.getValue(), found, names);
            } else if (property instanceof MultiProperty) {
                properties(((MultiProperty) property).iterator(), path, sampler, found, names);
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
            JsonNode node,
            String path,
            String key,
            Map<String, Candidate> found,
            Set<String> names) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.fields()
                    .forEachRemaining(
                            e ->
                                    leaves(
                                            e.getValue(),
                                            path + " > " + e.getKey(),
                                            e.getKey(),
                                            found,
                                            names));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                leaves(node.get(i), path + " > " + i, key, found, names);
            }
        } else {
            if (node.isTextual() && !ParameterReplacement.safeJsonString(node.asText())) {
                return;
            }
            add(path, key, node.asText(), found, names);
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
