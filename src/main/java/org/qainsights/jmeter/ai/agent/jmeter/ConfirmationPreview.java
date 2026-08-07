package org.qainsights.jmeter.ai.agent.jmeter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.tool.handlers.ApplyCorrelationHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.MoveElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.OpenPlanHandler;

/**
 * Builds the structured content of the agent's confirmation dialog for gated
 * (destructive) tools: a one-line summary, label/value detail rows, and a
 * severity-tinted note. Reads the live tree (via {@link ElementIdResolver})
 * so delete/move show the actual target, its children, and where it moves to -
 * instead of today's bare "run 'tool' on 'id'" text.
 * <p>
 * Pure logic over an already-obtained tree root - no {@code GuiPackage}
 * dependency - so it is testable with plain constructed nodes. Unresolvable
 * ids degrade to an honest "may fail" note rather than an empty dialog.
 * Rendering lives in {@link SwingToolConfirmationGate} (label-value rows;
 * sketch 001 variant B).
 */
public final class ConfirmationPreview {

    /** Severity of the note line, driving its color in the dialog. */
    public enum Level {
        INFO,
        WARN,
        DANGER
    }

    /** One label/value detail row. */
    public record Row(String label, String value) {
    }

    /** The dialog content: summary line, detail rows, and a tinted note. */
    public record Preview(String summary, List<Row> rows, String note, Level level) {
    }

    /** How many direct child names are listed before "… +N more". */
    static final int MAX_CHILD_NAMES = 4;

    private final ElementIdResolver resolver;

    public ConfirmationPreview() {
        this(new ElementIdResolver());
    }

    ConfirmationPreview(ElementIdResolver resolver) {
        this.resolver = resolver == null ? new ElementIdResolver() : resolver;
    }

    /**
     * Describes a gated tool call. {@code root} is the live tree model's root
     * wrapper node (its first child is the visible Test Plan node); may be
     * null when no GUI is available - previews then fall back to arg-only
     * content.
     */
    public Preview describe(String toolName, Map<String, Object> args, JMeterTreeNode root) {
        if (toolName == null) {
            toolName = "";
        }
        if (args == null) {
            args = Map.of();
        }
        switch (toolName) {
            case DeleteElementHandler.DELETE_ELEMENT:
                return describeDelete(args, root);
            case MoveElementHandler.MOVE_ELEMENT:
                return describeMove(args, root);
            case OpenPlanHandler.OPEN_PLAN:
                return describeOpenPlan(args);
            case ApplyCorrelationHandler.APPLY_CORRELATION:
                return describeCorrelation(args);
            default:
                return generic(toolName, args);
        }
    }

    // --- delete_element ------------------------------------------------------

    private Preview describeDelete(Map<String, Object> args, JMeterTreeNode root) {
        String id = str(args.get("element_id"));
        boolean force = Boolean.parseBoolean(str(args.get("force")));
        JMeterTreeNode node = resolve(root, id);
        if (node == null) {
            return new Preview("Delete '" + orUnknown(id) + "'?", List.of(),
                    "Element not found in the current tree - the call may fail.", Level.WARN);
        }
        int total = descendants(node);
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Element", label(node)));
        if (total > 0) {
            rows.add(new Row("Children", node.getChildCount() + " direct · " + total + " total"));
            rows.add(new Row("Child names", childNames(node)));
            rows.add(new Row("Force flag", force ? "yes - delete including children" : "no"));
        }
        String note;
        Level level;
        if (force && total > 0) {
            note = "This cannot be undone by the agent. Children go with it.";
            level = Level.DANGER;
        } else if (total > 0) {
            note = "This element has children; without force=true the call will be rejected.";
            level = Level.WARN;
        } else {
            note = "This cannot be undone by the agent.";
            level = Level.INFO;
        }
        return new Preview("Delete '" + node.getName() + "' (" + typeName(node) + ")?", rows, note, level);
    }

    // --- move_element --------------------------------------------------------

    private Preview describeMove(Map<String, Object> args, JMeterTreeNode root) {
        String id = str(args.get("element_id"));
        String parentId = str(args.get("new_parent_id"));
        JMeterTreeNode node = resolve(root, id);
        JMeterTreeNode parent = resolve(root, parentId);
        if (node == null || parent == null) {
            String which = node == null ? orUnknown(id) : orUnknown(parentId);
            return new Preview("Move '" + orUnknown(id) + "' under '" + orUnknown(parentId) + "'?",
                    List.of(), "Element '" + which + "' not found in the current tree - the call may fail.",
                    Level.WARN);
        }
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Element", label(node)));
        rows.add(new Row("From", parentName(node)));
        rows.add(new Row("To", label(parent)));
        int total = descendants(node);
        rows.add(new Row("Children", total == 0 ? "none" : total + " carried along"));
        return new Preview("Move '" + node.getName() + "' to '" + parent.getName() + "'?", rows,
                "The element keeps its own settings; only its position changes.", Level.INFO);
    }

    // --- open_plan -----------------------------------------------------------

    private Preview describeOpenPlan(Map<String, Object> args) {
        String path = str(args.get("file_path"));
        boolean force = Boolean.parseBoolean(str(args.get("force")));
        List<Row> rows = new ArrayList<>();
        File file = path == null ? null : new File(path);
        rows.add(new Row("File", file != null ? file.getName() : orUnknown(path)));
        if (file != null && file.getParent() != null) {
            rows.add(new Row("Location", file.getParent()));
        }
        rows.add(new Row("Size", file != null && file.isFile() ? formatSize(file.length()) : "unknown"));
        rows.add(new Row("Force flag", force
                ? "yes - replace even with unsaved changes"
                : "no - fails if the current plan is dirty"));
        return new Preview("Open '" + (file != null ? file.getName() : orUnknown(path))
                + "', replacing the current plan?", rows,
                "The current plan will be replaced. Unsaved changes are lost unless you saved first.",
                Level.WARN);
    }

    // --- apply_correlation ---------------------------------------------------

    private Preview describeCorrelation(Map<String, Object> args) {
        boolean applyAll = Boolean.TRUE.equals(args.get("apply_all"));
        Object ids = args.get("candidate_ids");
        int count = ids instanceof List ? ((List<?>) ids).size() : 0;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Candidates", applyAll ? "all pending" : count + " selected"));
        return new Preview(applyAll
                ? "Apply correlation to all pending candidates?"
                : "Apply correlation to " + count + " candidate" + (count == 1 ? "" : "s") + "?", rows,
                "Creates extractors and rewrites matching values to ${...} references.", Level.INFO);
    }

    // --- fallback ------------------------------------------------------------

    /** Arg-only preview for unrecognized tools - the pre-F10 dialog content as rows. */
    private Preview generic(String toolName, Map<String, Object> args) {
        List<Row> rows = new ArrayList<>();
        String id = str(args.get("element_id"));
        if (id != null) {
            rows.add(new Row("Element", id));
        }
        String newParent = str(args.get("new_parent_id"));
        if (newParent != null) {
            rows.add(new Row("New parent", newParent));
        }
        boolean force = Boolean.parseBoolean(str(args.get("force")));
        return new Preview("The AI agent wants to run '" + toolName + "'", rows,
                force ? "Includes all of its children." : "Review the arguments before allowing.",
                force ? Level.WARN : Level.INFO);
    }

    // --- helpers ---------------------------------------------------------------

    private JMeterTreeNode resolve(JMeterTreeNode root, String id) {
        if (root == null || id == null || id.isBlank()) {
            return null;
        }
        return resolver.resolve(root, id);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String orUnknown(String id) {
        return id == null || id.isBlank() ? "?" : id;
    }

    /** Display name plus a friendly type, e.g. "Login Sampler — ConfigTestElement". */
    private static String label(JMeterTreeNode node) {
        return node.getName() + " — " + typeName(node);
    }

    private static String typeName(JMeterTreeNode node) {
        return node.getTestElement() == null
                ? "element"
                : node.getTestElement().getClass().getSimpleName();
    }

    private static String parentName(JMeterTreeNode node) {
        return node.getParent() instanceof JMeterTreeNode
                ? ((JMeterTreeNode) node.getParent()).getName()
                : "(root)";
    }

    /** Total number of descendants (all depths). */
    static int descendants(JMeterTreeNode node) {
        int count = 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            count += 1 + descendants((JMeterTreeNode) node.getChildAt(i));
        }
        return count;
    }

    private static String childNames(JMeterTreeNode node) {
        int direct = node.getChildCount();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Math.min(direct, MAX_CHILD_NAMES); i++) {
            names.add(((JMeterTreeNode) node.getChildAt(i)).getName());
        }
        String joined = String.join(", ", names);
        return direct > MAX_CHILD_NAMES ? joined + " … +" + (direct - MAX_CHILD_NAMES) + " more" : joined;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
