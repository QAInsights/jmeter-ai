package org.qainsights.jmeter.ai.fix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jorphan.collections.HashTree;
import org.qainsights.jmeter.ai.agent.jmeter.EdtExecutor;
import org.qainsights.jmeter.ai.utils.SafeText;

/** Structural before-and-after diff of a JMeter test plan. */
public final class PlanDiff {
    private static final Set<String> SENSITIVE_FIELD_PARTS = Set.of(
            "password", "passwd", "pwd", "secret", "token", "session", "cookie", "auth", "api_key", "apikey");
    private static final Set<String> RESULT_LISTENER_GUI_CLASSES = Set.of(
            "org.apache.jmeter.visualizers.ViewResultsFullVisualizer",
            "org.apache.jmeter.visualizers.StatVisualizer",
            "org.apache.jmeter.visualizers.SummaryReport");

    private final Map<String, ElementSnapshot> before;
    private final Map<String, ElementSnapshot> after;
    private final List<ElementChange> added;
    private final List<ElementChange> removed;
    private final List<ElementChange> modified;

    private PlanDiff(Map<String, ElementSnapshot> before, Map<String, ElementSnapshot> after) {
        this.before = orderedMap(before);
        this.after = orderedMap(after);
        List<ElementChange> additions = new ArrayList<>();
        List<ElementChange> removals = new ArrayList<>();
        List<ElementChange> modifications = new ArrayList<>();
        before.forEach((path, oldElement) -> {
            ElementSnapshot newElement = after.get(path);
            if (newElement == null) {
                removals.add(ElementChange.element(path, oldElement));
            } else if (!oldElement.equals(newElement)) {
                modifications.add(ElementChange.modified(path, oldElement, newElement));
            }
        });
        after.forEach((path, newElement) -> {
            if (!before.containsKey(path)) {
                additions.add(ElementChange.element(path, newElement));
            }
        });
        this.added = List.copyOf(additions);
        this.removed = List.copyOf(removals);
        this.modified = List.copyOf(modifications);
    }

    public static Map<String, ElementSnapshot> snapshot(HashTree tree) {
        Map<String, ElementSnapshot> result = new LinkedHashMap<>();
        walk(tree, "", result);
        return orderedMap(result);
    }

    public static Map<String, ElementSnapshot> liveSnapshot() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getTreeModel() == null) {
            return Map.of();
        }
        AtomicReference<Map<String, ElementSnapshot>> result = new AtomicReference<>(Map.of());
        EdtExecutor.swing().run(() -> {
            gui.updateCurrentNode();
            result.set(snapshot(gui.getTreeModel().getTestPlan()));
        });
        return result.get();
    }

    public static PlanDiff between(Map<String, ElementSnapshot> before, Map<String, ElementSnapshot> after) {
        return new PlanDiff(before == null ? Map.of() : before, after == null ? Map.of() : after);
    }

    public List<ElementChange> added() {
        return added;
    }

    public List<ElementChange> removed() {
        return removed;
    }

    public List<ElementChange> modified() {
        return modified;
    }

    public Map<String, ElementSnapshot> before() {
        return before;
    }

    public Map<String, ElementSnapshot> after() {
        return after;
    }

    private static void walk(HashTree tree, String parentPath, Map<String, ElementSnapshot> result) {
        if (tree == null) {
            return;
        }
        Map<String, Integer> siblingCounts = new LinkedHashMap<>();
        for (Object key : tree.list()) {
            TestElement element = element(key);
            if (element == null) {
                continue;
            }
            String segment = element.getName() == null ? "" : element.getName();
            int occurrence = siblingCounts.merge(segment, 1, Integer::sum);
            if (occurrence > 1) {
                segment += "[" + occurrence + "]";
            }
            String path = parentPath.isEmpty() ? segment : parentPath + "/" + segment;
            result.put(path, new ElementSnapshot(element.getClass().getName(), properties(element)));
            walk(tree.getTree(key), path, result);
        }
    }

    private static Map<String, String> properties(TestElement element) {
        Map<String, String> properties = new LinkedHashMap<>();
        PropertyIterator iterator = element.propertyIterator();
        while (iterator.hasNext()) {
            JMeterProperty property = iterator.next();
            String name = property.getName();
            String value = isSensitive(name) ? "[REDACTED]" : SafeText.redactSensitiveValues(property.getStringValue());
            properties.put(name, value == null ? "" : value);
        }
        return orderedMap(properties);
    }

    private static <K, V> Map<K, V> orderedMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static boolean isSensitive(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        return SENSITIVE_FIELD_PARTS.stream().anyMatch(normalized::contains);
    }

    private static TestElement element(Object key) {
        return key instanceof JMeterTreeNode ? ((JMeterTreeNode) key).getTestElement()
                : key instanceof TestElement ? (TestElement) key : null;
    }

    public static final class ElementSnapshot {
        private final String className;
        private final Map<String, String> properties;

        ElementSnapshot(String className, Map<String, String> properties) {
            this.className = className;
            this.properties = orderedMap(properties);
        }

        public String className() {
            return className;
        }

        public Map<String, String> properties() {
            return properties;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ElementSnapshot)) {
                return false;
            }
            ElementSnapshot that = (ElementSnapshot) other;
            return className.equals(that.className) && properties.equals(that.properties);
        }

        @Override
        public int hashCode() {
            return className.hashCode() * 31 + properties.hashCode();
        }
    }

    public static final class ElementChange {
        private final String path;
        private final String className;
        private final Map<String, String> beforeProperties;
        private final Map<String, String> afterProperties;

        private ElementChange(String path, String className, Map<String, String> beforeProperties,
                              Map<String, String> afterProperties) {
            this.path = path;
            this.className = className;
            this.beforeProperties = orderedMap(beforeProperties);
            this.afterProperties = orderedMap(afterProperties);
        }

        private static ElementChange element(String path, ElementSnapshot snapshot) {
            return new ElementChange(path, snapshot.className, Map.of(), snapshot.properties);
        }

        private static ElementChange modified(String path, ElementSnapshot before, ElementSnapshot after) {
            return new ElementChange(path, before.className, before.properties, after.properties);
        }

        public String path() { return path; }
        public String className() { return className; }
        public String guiClass() { return afterProperties.getOrDefault(TestElement.GUI_CLASS, ""); }
        public boolean isResponseAssertion() {
            return className.equals("org.apache.jmeter.assertions.ResponseAssertion")
                    && guiClass().equals("org.apache.jmeter.assertions.gui.AssertionGui");
        }
        public boolean isResultListener() { return RESULT_LISTENER_GUI_CLASSES.contains(guiClass()); }
        public Map<String, String> beforeProperties() { return beforeProperties; }
        public Map<String, String> afterProperties() { return afterProperties; }
    }
}
