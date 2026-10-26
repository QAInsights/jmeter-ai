package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Objects;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.MainFrame;
import org.apache.jmeter.gui.util.JMeterToolBar;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorkloadDesignerMenuItem extends JMenuItem implements ActionListener {
    static final String TOOLBAR_ACTION_COMMAND = "open_workload_designer";
    static final String TOOLTIP = "Workload Designer: threads, ramp-up and throughput from a target load";
    private static final Logger log = LoggerFactory.getLogger(WorkloadDesignerMenuItem.class);

    public WorkloadDesignerMenuItem() {
        super("Workload Designer...", getLoadIcon(16));
        addActionListener(this);
        addToolbarIcon();
    }

    public static ImageIcon getLoadIcon(int size) {
        String path = String.format(
                "/org/qainsights/jmeter/ai/featherwand-load-%dx%d.png", size, size);
        return new ImageIcon(Objects.requireNonNull(
                WorkloadDesignerMenuItem.class.getResource(path), "Missing workload icon: " + path));
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        openWorkloadDesigner();
    }

    private void openFromToolbar(ActionEvent event) {
        Telemetry.record(TelemetryFeature.LOAD_TOOLBAR_OPEN);
        openWorkloadDesigner();
    }

    private void openWorkloadDesigner() {
        try {
            AiMenuItem.runInChat("@load");
        } catch (RuntimeException e) {
            log.error("Could not open Workload Designer", e);
        }
    }

    static int toolbarInsertPosition(Component[] components) {
        int featherWandPosition = -1;
        int startPosition = -1;
        for (int index = 0; index < components.length; index++) {
            if (!(components[index] instanceof JButton button)) {
                continue;
            }
            String actionCommand = button.getActionCommand();
            if ("toggle_claude_code_panel".equals(actionCommand)) {
                return index + 1;
            }
            if (FeatherWandToolbar.isFeatherWandButton(button)) {
                featherWandPosition = index;
            }
            if (startPosition < 0 && "start".equals(actionCommand)) {
                startPosition = index;
            }
        }
        if (featherWandPosition >= 0) {
            return featherWandPosition + 1;
        }
        return startPosition >= 0 ? startPosition : components.length;
    }

    static boolean insertToolbarButton(Container toolbar, JButton button) {
        if (toolbar == null || button == null
                || hasToolbarButton(toolbar.getComponents())) {
            return false;
        }
        int position = toolbarInsertPosition(toolbar.getComponents());
        if (toolbar.getComponentCount() > 0) {
            button.setSize(toolbar.getComponent(Math.min(position, toolbar.getComponentCount() - 1)).getSize());
        }
        toolbar.add(button, position);
        toolbar.revalidate();
        toolbar.repaint();
        return true;
    }

    private static boolean hasToolbarButton(Component[] components) {
        for (Component component : components) {
            if (component instanceof JButton button
                    && TOOLBAR_ACTION_COMMAND.equals(button.getActionCommand())) {
                return true;
            }
        }
        return false;
    }

    private void addToolbarIcon() {
        GuiPackage instance = GuiPackage.getInstance();
        MainFrame mainFrame = instance == null ? null : instance.getMainFrame();
        if (mainFrame == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> ComponentFinder.whenFound(
                mainFrame, JMeterToolBar.class, 500, 120,
                toolbar -> insertToolbarButton(toolbar, createToolbarButton())));
    }

    private JButton createToolbarButton() {
        JButton button = new JButton(getLoadIcon(22));
        button.setToolTipText(TOOLTIP);
        button.setActionCommand(TOOLBAR_ACTION_COMMAND);
        button.addActionListener(this::openFromToolbar);
        return button;
    }
}
