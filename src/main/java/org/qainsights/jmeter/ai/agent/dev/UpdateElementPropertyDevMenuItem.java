package org.qainsights.jmeter.ai.agent.dev;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import org.apache.jmeter.gui.GuiPackage;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.handlers.UpdateElementPropertyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEV-ONLY menu item to smoke-test the {@code update_element_property} tool
 * against the live JMeter tree. Edits a property on the currently selected node
 * via a couple of input dialogs and shows the result. All logic lives in
 * {@link UpdateElementPropertyDevRunner}; this class is just GUI wiring.
 */
public final class UpdateElementPropertyDevMenuItem extends JMenuItem implements ActionListener {

    public static final String LABEL = "AI Dev: Test update_element_property";

    private static final Logger log = LoggerFactory.getLogger(UpdateElementPropertyDevMenuItem.class);

    private final transient UpdateElementPropertyDevRunner runner =
            new UpdateElementPropertyDevRunner(new UpdateElementPropertyHandler(), new ElementIdResolver());

    public UpdateElementPropertyDevMenuItem() {
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
            log.error("Dev update_element_property invocation failed", err);
            JOptionPane.showMessageDialog(null, "Dev update_element_property threw: " + err.getMessage());
        }
    }
}
