package org.qainsights.jmeter.ai.extract;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.RequestPanel;
import org.apache.jmeter.visualizers.ViewResultsFullVisualizer;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** Adds "Extract this" to the right-click menu of View Results Tree response panes. */
public final class ExtractThisMenu {

    private static final Logger log = LoggerFactory.getLogger(ExtractThisMenu.class);
    static final String ENABLED_PROPERTY = "jmeter.ai.extract.enabled";
    static final String LABEL = "Extract this (Feather Wand)";
    private static final String INSTALLED_KEY = "featherWand.extractThis";
    private static boolean installed;

    private ExtractThisMenu() {
    }

    public static synchronized void install() {
        if (installed || GraphicsEnvironment.isHeadless()
                || !Boolean.parseBoolean(AiConfig.getProperty(ENABLED_PROPERTY, "true"))) {
            return;
        }
        installed = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            int id = event.getID();
            if (id == MouseEvent.MOUSE_ENTERED || id == MouseEvent.MOUSE_PRESSED) {
                attach(event.getSource());
            }
        }, AWTEvent.MOUSE_EVENT_MASK);
        log.info("Extract this menu installed for View Results Tree");
    }

    /** Adds the item to a response text pane inside View Results Tree; other components are left alone. */
    static void attach(Object source) {
        if (!(source instanceof JTextComponent) || source instanceof JTextField) {
            return;
        }
        JTextComponent text = (JTextComponent) source;
        if (text.getClientProperty(INSTALLED_KEY) != null) {
            return;
        }
        Container visualizer = (Container) SwingUtilities.getAncestorOfClass(ViewResultsFullVisualizer.class, text);
        if (visualizer == null || SwingUtilities.getAncestorOfClass(RequestPanel.class, text) != null) {
            return;
        }
        text.putClientProperty(INSTALLED_KEY, Boolean.TRUE);
        addItem(text, () -> extract(text, visualizer));
    }

    /**
     * Response headers use JMeter's JSyntaxTextArea, whose RTextArea popup is separate from the Swing
     * component popup, so the item is appended there. Anything else gets a plain Copy / Select All popup:
     * Darklaf's text popup refuses to open on read-only panes because all its built-in actions are disabled.
     */
    static JPopupMenu addItem(JTextComponent text, Runnable action) {
        JPopupMenu menu = syntaxAreaPopup(text);
        if (menu == null) {
            menu = new JPopupMenu();
            JMenuItem copy = new JMenuItem("Copy");
            copy.addActionListener(e -> text.copy());
            JMenuItem selectAll = new JMenuItem("Select All");
            selectAll.addActionListener(e -> text.selectAll());
            menu.add(copy);
            menu.add(selectAll);
            menu.addPopupMenuListener(onShow(() -> copy.setEnabled(text.getSelectionStart() != text.getSelectionEnd())));
            text.setComponentPopupMenu(menu);
        }
        menu.addSeparator();
        JMenuItem item = new JMenuItem(LABEL);
        item.addActionListener(e -> action.run());
        menu.add(item);
        menu.addPopupMenuListener(onShow(
                () -> item.setEnabled(!ExtractorSuggester.normalize(text.getSelectedText()).isEmpty())));
        return menu;
    }

    private static JPopupMenu syntaxAreaPopup(JTextComponent text) {
        try {
            Object menu = text.getClass().getMethod("getPopupMenu").invoke(text);
            return menu instanceof JPopupMenu ? (JPopupMenu) menu : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static PopupMenuListener onShow(Runnable r) {
        return new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                r.run();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        };
    }

    static void extract(JTextComponent text, Container visualizer) {
        String value = ExtractorSuggester.normalize(text.getSelectedText());
        if (value.isEmpty()) {
            tell(text, "Highlight the value you want to extract first.");
            return;
        }
        SampleResult[] results = selectedResults(findTree(visualizer));
        if (results == null) {
            tell(text, "Select a sample in the results tree first.");
            return;
        }
        SampleResult top = results[0];
        SampleResult selected = results[1];
        GuiPackage gui = GuiPackage.getInstance();
        JMeterTreeModel model = gui.getTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        JMeterTreeNode sampler = pickSampler(text, ExtractPlacement.findSamplers(root, top.getSampleLabel()));
        if (sampler == null) {
            if (ExtractPlacement.findSamplers(root, top.getSampleLabel()).isEmpty()) {
                tell(text, "There is no sampler named \"" + top.getSampleLabel()
                        + "\" in the test plan, so there is nowhere to put the extractor.");
            }
            return;
        }
        List<ExtractorSuggestion> suggestions = ExtractorSuggester.suggest(top, selected, value);
        if (suggestions.isEmpty()) {
            tell(text, "I couldn't build an extractor that returns exactly \"" + ExtractThisDialog.abbreviate(value, 60)
                    + "\" from this response. Highlight just the value, without surrounding quotes or spaces.");
            return;
        }
        gui.updateCurrentNode();
        List<JMeterTreeNode> uses = ExtractPlacement.laterUses(root, sampler, value);
        List<String> useNames = new ArrayList<>();
        for (JMeterTreeNode n : uses) {
            useNames.add(n.getName());
        }
        ExtractThisDialog.Choice choice = ExtractThisDialog.show(text, value, sampler.getName(), suggestions, useNames);
        if (choice == null) {
            return;
        }
        apply(gui, model, sampler, choice, uses, value);
    }

    private static void apply(GuiPackage gui, JMeterTreeModel model, JMeterTreeNode sampler,
            ExtractThisDialog.Choice choice, List<JMeterTreeNode> uses, String value) {
        JMeterTreeNode current = gui.getTreeListener() == null ? null : gui.getTreeListener().getCurrentNode();
        gui.updateCurrentNode();
        try {
            TestElement extractor = choice.suggestion.toElement(choice.variableName);
            JMeterTreeNode added = model.addComponent(extractor, sampler);
            if (choice.replaceLaterUses) {
                ExtractPlacement.replace(uses, value, choice.variableName);
                for (JMeterTreeNode n : uses) {
                    model.nodeChanged(n);
                }
                if (current != null && uses.contains(current)) {
                    JMeterGUIComponent comp = gui.getGui(current.getTestElement());
                    if (comp != null) {
                        comp.clearGui();
                        comp.configure(current.getTestElement());
                    }
                }
            }
            model.nodeStructureChanged(sampler);
            if (gui.getTreeListener() != null) {
                JTree planTree = gui.getTreeListener().getJTree();
                TreePath path = new TreePath(added.getPath());
                planTree.setSelectionPath(path);
                planTree.scrollPathToVisible(path);
            }
            gui.getMainFrame().repaint();
            Telemetry.record(TelemetryFeature.EXTRACT_ADDED);
            log.info("Extract this: added {} '{}' under '{}'", choice.suggestion.type().label(),
                    choice.variableName, sampler.getName());
        } catch (Exception e) {
            log.warn("Extract this: could not add the extractor", e);
            tell(null, "Could not add the extractor: " + e.getMessage());
        }
    }

    /** The only match, or the user's pick when several samplers share the label; null if none or cancelled. */
    private static JMeterTreeNode pickSampler(Component parent, List<JMeterTreeNode> matches) {
        if (matches.size() <= 1) {
            return matches.isEmpty() ? null : matches.get(0);
        }
        String[] paths = new String[matches.size()];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = (i + 1) + ". " + ExtractPlacement.pathOf(matches.get(i));
        }
        Object picked = JOptionPane.showInputDialog(parent,
                matches.size() + " samplers have this name. Which one produced this response?",
                "Extract this", JOptionPane.QUESTION_MESSAGE, null, paths, paths[0]);
        for (int i = 0; i < paths.length; i++) {
            if (paths[i].equals(picked)) {
                return matches.get(i);
            }
        }
        return null;
    }

    /** {top-level result, highlighted result}, or null when no sample is selected. */
    static SampleResult[] selectedResults(JTree tree) {
        TreePath path = tree == null ? null : tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        SampleResult top = null;
        SampleResult selected = null;
        for (Object o : path.getPath()) {
            Object user = o instanceof DefaultMutableTreeNode ? ((DefaultMutableTreeNode) o).getUserObject() : null;
            if (user instanceof SampleResult) {
                if (top == null) {
                    top = (SampleResult) user;
                }
                selected = (SampleResult) user;
            }
        }
        return top == null ? null : new SampleResult[] {top, selected};
    }

    static JTree findTree(Component c) {
        if (c instanceof JTree) {
            return (JTree) c;
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                JTree t = findTree(child);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private static void tell(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Extract this", JOptionPane.INFORMATION_MESSAGE);
    }
}
