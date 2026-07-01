package org.qainsights.jmeter.ai.agent.dev;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import org.apache.jmeter.gui.GuiPackage;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.handlers.AddElementHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEV-ONLY menu item to smoke-test the {@code add_element} tool against the live
 * JMeter tree. Adds the selected node's child via a couple of input dialogs and
 * shows the {@link org.qainsights.jmeter.ai.agent.tool.ToolResult}. All logic
 * lives in {@link AddElementDevRunner}; this class is just GUI wiring and is
 * temporary scaffolding.
 */
public final class AddElementDevMenuItem extends JMenuItem implements ActionListener {

    public static final String LABEL = "AI Dev: Test add_element";

    private static final Logger log = LoggerFactory.getLogger(AddElementDevMenuItem.class);

    private final transient AddElementDevRunner runner =
            new AddElementDevRunner(new AddElementHandler(), new ElementIdResolver());

    public AddElementDevMenuItem() {
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
            log.error("Dev add_element invocation failed", err);
            JOptionPane.showMessageDialog(null, "Dev add_element threw: " + err.getMessage());
        }
    }
}
