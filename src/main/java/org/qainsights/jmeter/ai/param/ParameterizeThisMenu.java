package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.control.Controller;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.awt.AWTEvent;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.HierarchyEvent;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

/** JMeter's MenuCreator only exposes top-level menus, not tree context menus. */
public final class ParameterizeThisMenu {
    private static boolean installed;

    private ParameterizeThisMenu() {}

    public static boolean enabled() {
        return Boolean.parseBoolean(AiConfig.getProperty("jmeter.ai.param.enabled", "true"));
    }

    public static synchronized void install() {
        if (installed || GraphicsEnvironment.isHeadless() || !enabled()) {
            return;
        }
        installed = true;
        Toolkit.getDefaultToolkit()
                .addAWTEventListener(
                        event -> {
                            if (event instanceof HierarchyEvent
                                    && event.getSource() instanceof JPopupMenu) {
                                JPopupMenu popup = (JPopupMenu) event.getSource();
                                GuiPackage gui = GuiPackage.getInstance();
                                if (popup.isShowing()
                                        && gui != null
                                        && gui.getTreeListener() != null
                                        && popup.getInvoker() == gui.getTreeListener().getJTree()
                                        && popup.getClientProperty("featherWand.param") == null) {
                                    JMeterTreeNode node = gui.getTreeListener().getCurrentNode();
                                    if (!eligible(node)) {
                                        return;
                                    }
                                    popup.putClientProperty("featherWand.param", Boolean.TRUE);
                                    popup.addSeparator();
                                    JMenuItem item =
                                            new JMenuItem("Parameterize this (Feather Wand)");
                                    item.addActionListener(e -> open());
                                    popup.add(item);
                                    popup.setPopupSize(popup.getPreferredSize());
                                }
                            }
                        },
                        AWTEvent.HIERARCHY_EVENT_MASK);
    }

    static boolean eligible(JMeterTreeNode node) {
        return node != null
                && ParameterPlacement.threadGroupOf(node) != null
                && (node.getTestElement() instanceof Sampler
                        || node.getTestElement() instanceof Controller
                        || node.getTestElement() instanceof AbstractThreadGroup);
    }

    public static void open() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(ParameterizeThisMenu::open);
            return;
        }
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getTreeListener() == null || !enabled()) {
            return;
        }
        gui.updateCurrentNode();
        JMeterTreeNode scope = gui.getTreeListener().getCurrentNode();
        if (!eligible(scope) || ParameterPlacement.threadGroupOf(scope) == null) {
            JOptionPane.showMessageDialog(
                    gui.getMainFrame(),
                    "Select a sampler, controller or Thread Group inside a Thread Group.",
                    "Parameterize this",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ParameterizeThisDialog.show(gui, scope);
    }
}
