package org.qainsights.jmeter.ai.agent.dev;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import org.apache.jmeter.gui.GuiPackage;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.handlers.MoveElementHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEV-ONLY menu item to smoke-test the {@code move_element} tool against the
 * live JMeter tree. Moves the currently selected node to a user-specified
 * parent via an input dialog and shows the result. All logic lives in
 * {@link MoveElementDevRunner}; this class is just GUI wiring.
 */
public final class MoveElementDevMenuItem extends JMenuItem implements ActionListener {

    public static final String LABEL = "AI Dev: Test move_element";

    private static final Logger log = LoggerFactory.getLogger(MoveElementDevMenuItem.class);

    private final transient MoveElementDevRunner runner =
            new MoveElementDevRunner(new MoveElementHandler(), new ElementIdResolver());

    public MoveElementDevMenuItem() {
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
            log.error("Dev move_element invocation failed", err);
            JOptionPane.showMessageDialog(null, "Dev move_element threw: " + err.getMessage());
        }
    }
}
