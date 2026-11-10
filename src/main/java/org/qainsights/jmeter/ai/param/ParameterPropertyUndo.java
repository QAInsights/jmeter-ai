package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ParameterPropertyUndo {
    private record Key(String name, int occurrence) {
    }

    record Change(JMeterTreeNode node, List<Key> path, JMeterProperty previous) {
        void restore() {
            TestElement element = node.getTestElement();
            if (path.size() == 1) {
                if (previous == null) {
                    element.removeProperty(path.get(0).name);
                } else {
                    element.setProperty(previous.clone());
                }
                return;
            }
            PropertyIterator properties = element.propertyIterator();
            for (int i = 0; i < path.size(); i++) {
                JMeterProperty property = find(properties, path.get(i));
                if (property == null) {
                    return;
                }
                if (i == path.size() - 1) {
                    property.setObjectValue(previous.getObjectValue());
                } else if (property instanceof MultiProperty) {
                    properties = ((MultiProperty) property).iterator();
                } else {
                    return;
                }
            }
        }
    }

    private ParameterPropertyUndo() {
    }

    static List<Change> changes(Map<JMeterTreeNode, TestElement> before) {
        List<Change> changes = new ArrayList<>();
        before.forEach((node, element) -> collect(node, element.propertyIterator(),
                node.getTestElement().propertyIterator(), List.of(), changes));
        return List.copyOf(changes);
    }

    private static void collect(JMeterTreeNode node, PropertyIterator before, PropertyIterator after,
            List<Key> path, List<Change> changes) {
        Map<Key, JMeterProperty> old = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        while (before.hasNext()) {
            JMeterProperty property = before.next();
            old.put(new Key(property.getName(), counts.merge(property.getName(), 1, Integer::sum)), property);
        }
        counts.clear();
        while (after.hasNext()) {
            JMeterProperty property = after.next();
            Key key = new Key(property.getName(), counts.merge(property.getName(), 1, Integer::sum));
            JMeterProperty previous = old.get(key);
            List<Key> location = new ArrayList<>(path);
            location.add(key);
            if (property instanceof MultiProperty && previous instanceof MultiProperty) {
                collect(node, ((MultiProperty) previous).iterator(), ((MultiProperty) property).iterator(),
                        location, changes);
            } else if (!property.equals(previous)) {
                changes.add(new Change(node, List.copyOf(location), previous == null ? null : previous.clone()));
            }
        }
    }

    private static JMeterProperty find(PropertyIterator properties, Key key) {
        int occurrence = 0;
        while (properties.hasNext()) {
            JMeterProperty property = properties.next();
            if (property.getName().equals(key.name) && ++occurrence == key.occurrence) {
                return property;
            }
        }
        return null;
    }
}
