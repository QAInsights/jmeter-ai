package org.qainsights.jmeter.ai.agent.dev;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import org.apache.jmeter.gui.GuiPackage;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.handlers.ToggleElementHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEV-ONLY menu item to smoke-test the {@code toggle_element} tool against the
 * live JMeter tree. Toggles the currently selected node via an input dialog and
 * shows the result. All logic lives in {@link ToggleElementDevRunner}; this
 * class is just GUI wiring.
 */
public final class ToggleElementDevMenuItem extends JMenuItem implements ActionListener {

    public static final String LABEL = "AI Dev: Test toggle_element";

    private static final Logger log = LoggerFactory.getLogger(ToggleElementDevMenuItem.class);

    private final transient ToggleElementDevRunner runner =
            new ToggleElementDevRunner(new ToggleElementHandler(), new ElementIdResolver());

    public ToggleElementDevMenuItem() {
        super(LABEL);
        addActionListener(this);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        try {
            runner.run(
                    () -> {
                        GuiPackage gui = GuiPackage.getInstance();
                        return gui == null ? null : gui.getTreeListener().getCurrentNode();
                    },
                    (label, defaultValue) -> JOptionPane.showInputDialog(null, label, defaultValue),
                    message -> JOptionPane.showMessageDialog(null, message));
        } catch (RuntimeException err) {
            log.error("Dev toggle_element invocation failed", err);
            JOptionPane.showMessageDialog(null, "Dev toggle_element threw: " + err.getMessage());
        }
    }
}
