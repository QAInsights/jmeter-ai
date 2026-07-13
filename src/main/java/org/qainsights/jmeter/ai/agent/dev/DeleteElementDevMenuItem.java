package org.qainsights.jmeter.ai.agent.dev;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import org.apache.jmeter.gui.GuiPackage;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEV-ONLY menu item to smoke-test the {@code delete_element} tool against the
 * live JMeter tree. Deletes the currently selected node after a confirmation
 * dialog. All logic lives in {@link DeleteElementDevRunner}; this is GUI wiring.
 */
public final class DeleteElementDevMenuItem extends JMenuItem implements ActionListener {

    public static final String LABEL = "AI Dev: Test delete_element";

    private static final Logger log = LoggerFactory.getLogger(DeleteElementDevMenuItem.class);

    private final transient DeleteElementDevRunner runner =
            new DeleteElementDevRunner(new DeleteElementHandler(), new ElementIdResolver());

    public DeleteElementDevMenuItem() {
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
                    message -> JOptionPane.showConfirmDialog(null, message, "Confirm delete",
                            JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION,
                    message -> JOptionPane.showMessageDialog(null, message));
        } catch (RuntimeException err) {
            log.error("Dev delete_element invocation failed", err);
            JOptionPane.showMessageDialog(null, "Dev delete_element threw: " + err.getMessage());
        }
    }
}
