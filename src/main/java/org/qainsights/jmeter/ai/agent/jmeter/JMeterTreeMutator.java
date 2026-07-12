package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.tree.TreeNode;

import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performs structural mutations on the live JMeter tree (property updates,
 * enable/disable, move, delete) and fires the corresponding tree-model events
 * so the GUI refreshes. All mutations run on the EDT via an injected
 * {@link EdtExecutor}; tests pass {@link EdtExecutor#direct()}.
 * <p>
 * Each method returns {@code true} on success and {@code false} when a guard is
 * violated (null inputs, missing element, root operation, unsafe delete) or the
 * underlying JMeter call fails. Callers translate {@code false} into a
 * descriptive tool error for agent self-correction.
 */
public final class JMeterTreeMutator {

    private static final Logger log = LoggerFactory.getLogger(JMeterTreeMutator.class);

    private final EdtExecutor edt;

    public JMeterTreeMutator() {
        this(EdtExecutor.swing());
    }

    public JMeterTreeMutator(EdtExecutor edt) {
        this.edt = edt == null ? EdtExecutor.swing() : edt;
    }

    /**
     * Sets a string property on the node's test element. Refuses to overwrite a
     * property that is currently a collection/map/nested element (e.g. an
     * assertion's pattern list or a header manager's headers) - doing so would
     * replace it with a plain {@code StringProperty} of the same name and
     * corrupt the element, crashing its GUI panel the next time it is opened.
     * <p>
     * If {@code property} isn't owned directly by the node's element but is
     * owned by one of its nested {@code TestElementProperty} children (e.g. a
     * {@code ThreadGroup}'s {@code LoopController.loops}, which lives on the
     * nested {@code main_controller}), the write is delegated to that nested
     * element instead of silently creating an unrelated top-level property of
     * the same name that JMeter never reads.
     */
    public boolean updateProperty(JMeterTreeModel model, JMeterTreeNode node, String property, String value) {
        if (!isValid(model, node) || property == null || property.trim().isEmpty()) {
            return false;
        }
        String safeValue = value == null ? "" : value;
        return mutate(() -> {
            TestElement target = resolvePropertyOwner(node.getTestElement(), property);
            if (isNonScalar(target.getProperty(property))) {
                log.warn("Refusing to overwrite non-scalar property '{}' on {} with a plain string value",
                        property, target.getClass().getSimpleName());
                return false;
            }
            target.setProperty(property, safeValue);
            model.nodeChanged(node);
            return true;
        });
    }

    /**
     * Returns the element that actually owns {@code property}: {@code element}
     * itself, unless the property is absent there but present on a nested
     * {@code TestElementProperty} child - in which case that nested element is
     * returned instead. Falls back to {@code element} when no owner is found
     * (e.g. {@code property} is genuinely new), preserving the ability to set
     * brand-new top-level properties.
     */
    private static TestElement resolvePropertyOwner(TestElement element, String property) {
        if (!(element.getProperty(property) instanceof NullProperty)) {
            return element;
        }
        PropertyIterator it = element.propertyIterator();
        while (it.hasNext()) {
            JMeterProperty candidate = it.next();
            if (candidate instanceof TestElementProperty) {
                TestElement nested = ((TestElementProperty) candidate).getElement();
                if (nested != null && !(nested.getProperty(property) instanceof NullProperty)) {
                    return nested;
                }
            }
        }
        return element;
    }

    /**
     * True when {@code property} is a collection, map or nested test-element
     * property - these are not settable as a plain string via
     * {@code update_element_property} without corrupting the element.
     */
    private static boolean isNonScalar(JMeterProperty property) {
        return property instanceof CollectionProperty
                || property instanceof MapProperty
                || property instanceof TestElementProperty;
    }

    /**
     * Replaces a flat string-list property (e.g. a Response Assertion's
     * patterns) with {@code values} in full. Refuses when the existing property
     * is present but isn't a flat {@code CollectionProperty} of plain strings
     * (e.g. a header/argument list, whose entries are nested test elements) -
     * callers must only invoke this for property keys known to be flat string
     * lists (see {@code ElementPropertyCatalog.isFlatStringListProperty}).
     */
    public boolean replacePropertyList(JMeterTreeModel model, JMeterTreeNode node, String property,
                                        List<String> values) {
        if (!isValid(model, node) || property == null || property.trim().isEmpty() || values == null) {
            return false;
        }
        return mutate(() -> {
            if (!isFlatStringListOrAbsent(node.getTestElement().getProperty(property))) {
                log.warn("Refusing to replace '{}' on {} - existing property is not a flat string list",
                        property, node.getTestElement().getClass().getSimpleName());
                return false;
            }
            node.getTestElement().setProperty(new CollectionProperty(property, new ArrayList<>(values)));
            model.nodeChanged(node);
            return true;
        });
    }

    /** True when {@code property} is absent, or is a CollectionProperty made only of plain strings. */
    private static boolean isFlatStringListOrAbsent(JMeterProperty property) {
        if (property == null || property instanceof NullProperty) {
            return true;
        }
        if (!(property instanceof CollectionProperty)) {
            return false;
        }
        PropertyIterator it = ((CollectionProperty) property).iterator();
        while (it.hasNext()) {
            if (!(it.next() instanceof StringProperty)) {
                return false;
            }
        }
        return true;
    }

    /** {@code AuthManager}'s container property key; the JMeter constant for it is package-private. */
    private static final String AUTH_LIST = "AuthManager.auth_list";

    /**
     * Replaces a structured-list property (HTTP headers, User Defined
     * Variables/HTTP parameters, or HTTP Authorization entries) with
     * {@code entries} in full. Each map's expected keys depend on
     * {@code property}: {@code HeaderManager.headers} and
     * {@code Arguments.arguments} expect {@code name}/{@code value};
     * {@code AuthManager.auth_list} expects {@code url}/{@code username}/
     * {@code password}/{@code domain}/{@code realm}/{@code mechanism}. Refuses
     * when the existing property is present but isn't a collection of nested
     * test elements, or when {@code property} isn't one of the three above, or
     * an entry's {@code mechanism} isn't a valid {@code AuthManager.Mechanism} -
     * callers must only invoke this for property keys known to be structured
     * lists (see {@code ElementPropertyCatalog.isStructuredListProperty}).
     */
    public boolean replaceStructuredPropertyList(JMeterTreeModel model, JMeterTreeNode node, String property,
                                                  List<Map<String, String>> entries) {
        if (!isValid(model, node) || property == null || property.trim().isEmpty() || entries == null) {
            return false;
        }
        return mutate(() -> {
            if (!isStructuredListOrAbsent(node.getTestElement().getProperty(property))) {
                log.warn("Refusing to replace '{}' on {} - existing property is not a structured list",
                        property, node.getTestElement().getClass().getSimpleName());
                return false;
            }
            List<TestElement> built = new ArrayList<>();
            for (Map<String, String> entry : entries) {
                TestElement element = buildStructuredEntry(property, entry);
                if (element == null) {
                    log.warn("Refusing to replace '{}' - unsupported property or invalid entry {}", property, entry);
                    return false;
                }
                built.add(element);
            }
            node.getTestElement().setProperty(new CollectionProperty(property, built));
            model.nodeChanged(node);
            return true;
        });
    }

    /** True when {@code property} is absent, or is a CollectionProperty of nested test elements. */
    private static boolean isStructuredListOrAbsent(JMeterProperty property) {
        if (property == null || property instanceof NullProperty) {
            return true;
        }
        if (!(property instanceof CollectionProperty)) {
            return false;
        }
        PropertyIterator it = ((CollectionProperty) property).iterator();
        while (it.hasNext()) {
            if (!(it.next() instanceof TestElementProperty)) {
                return false;
            }
        }
        return true;
    }

    /** Builds the concrete test element an entry represents, or {@code null} if unsupported/invalid. */
    private static TestElement buildStructuredEntry(String property, Map<String, String> entry) {
        if (HeaderManager.HEADERS.equals(property)) {
            return new Header(field(entry, "name"), field(entry, "value"));
        }
        if (Arguments.ARGUMENTS.equals(property)) {
            return new Argument(field(entry, "name"), field(entry, "value"));
        }
        if (AUTH_LIST.equals(property)) {
            return buildAuthorization(entry);
        }
        return null;
    }

    private static Authorization buildAuthorization(Map<String, String> entry) {
        Authorization auth = new Authorization();
        auth.setURL(field(entry, "url"));
        auth.setUser(field(entry, "username"));
        auth.setPass(field(entry, "password"));
        auth.setDomain(field(entry, "domain"));
        auth.setRealm(field(entry, "realm"));
        String mechanism = field(entry, "mechanism");
        if (!mechanism.isEmpty()) {
            try {
                auth.setMechanism(AuthManager.Mechanism.valueOf(mechanism.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return auth;
    }

    private static String field(Map<String, String> entry, String key) {
        String value = entry.get(key);
        return value == null ? "" : value;
    }

    /** Enables or disables the node and refreshes its display. */
    public boolean setEnabled(JMeterTreeModel model, JMeterTreeNode node, boolean enabled) {
        if (!isValid(model, node)) {
            return false;
        }
        return mutate(() -> {
            node.setEnabled(enabled);
            model.nodeChanged(node);
            return true;
        });
    }

    /** Renames the node's element and refreshes its display. */
    public boolean renameElement(JMeterTreeModel model, JMeterTreeNode node, String newName) {
        if (!isValid(model, node) || newName == null || newName.trim().isEmpty()) {
            return false;
        }
        return mutate(() -> {
            node.setName(newName);
            model.nodeChanged(node);
            return true;
        });
    }

    /**
     * Deletes the node. A node with children is only removed when
     * {@code force} is {@code true}, preventing accidental subtree deletion.
     * The tree root cannot be deleted.
     */
    public boolean deleteElement(JMeterTreeModel model, JMeterTreeNode node, boolean force) {
        if (!isValid(model, node) || node.getParent() == null) {
            return false;
        }
        if (node.getChildCount() > 0 && !force) {
            return false;
        }
        return mutate(() -> {
            model.removeNodeFromParent(node);
            return true;
        });
    }

    /**
     * Moves the node to become the last child of {@code newParent}. The root
     * cannot be moved, and a node cannot be moved under itself or a descendant.
     */
    public boolean moveElement(JMeterTreeModel model, JMeterTreeNode node, JMeterTreeNode newParent) {
        if (!isValid(model, node) || newParent == null || node.getParent() == null) {
            return false;
        }
        if (node == newParent || isAncestor(node, newParent)) {
            return false;
        }
        return mutate(() -> {
            model.removeNodeFromParent(node);
            model.insertNodeInto(node, newParent, newParent.getChildCount());
            return true;
        });
    }

    /**
     * Duplicates the node's subtree - deep-cloning its {@code TestElement} and
     * every descendant's, via {@link TreeNodeCloner} - and inserts the clone as
     * the next sibling immediately after {@code node}, under the same parent.
     * This is the same result as JMeter's own Copy+Paste/Duplicate menu
     * command, without depending on the live tree selection. The root (a node
     * with no parent) cannot be duplicated.
     */
    public JMeterTreeNode duplicateElement(JMeterTreeModel model, JMeterTreeNode node) {
        if (!isValid(model, node) || !(node.getParent() instanceof JMeterTreeNode)) {
            return null;
        }
        JMeterTreeNode parent = (JMeterTreeNode) node.getParent();
        AtomicReference<JMeterTreeNode> result = new AtomicReference<>();
        edt.run(() -> {
            try {
                JMeterTreeNode clone = TreeNodeCloner.cloneSubtree(node);
                model.insertNodeInto(clone, parent, parent.getIndex(node) + 1);
                result.set(clone);
            } catch (RuntimeException e) {
                log.error("Tree mutation failed", e);
                result.set(null);
            }
        });
        return result.get();
    }

    private boolean isValid(JMeterTreeModel model, JMeterTreeNode node) {
        return model != null && node != null && node.getTestElement() != null;
    }

    /** True if {@code maybeDescendant} is {@code node} itself or below it. */
    private boolean isAncestor(JMeterTreeNode node, JMeterTreeNode maybeDescendant) {
        TreeNode current = maybeDescendant;
        while (current != null) {
            if (current == node) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /** Runs the mutation on the EDT, capturing its result and swallowing failures. */
    private boolean mutate(Mutation mutation) {
        AtomicBoolean result = new AtomicBoolean(false);
        edt.run(() -> {
            try {
                result.set(mutation.apply());
            } catch (RuntimeException e) {
                log.error("Tree mutation failed", e);
                result.set(false);
            }
        });
        return result.get();
    }

    /** A tree mutation that returns whether it succeeded. */
    @FunctionalInterface
    private interface Mutation {
        boolean apply();
    }
}
