package org.qainsights.jmeter.ai.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.jmeter.gui.plugin.MenuCreator;
import org.qainsights.jmeter.ai.agent.dev.AddElementDevMenuItem;
import org.qainsights.jmeter.ai.agent.dev.DeleteElementDevMenuItem;
import org.qainsights.jmeter.ai.agent.dev.MoveElementDevMenuItem;
import org.qainsights.jmeter.ai.agent.dev.ToggleElementDevMenuItem;
import org.qainsights.jmeter.ai.agent.dev.UpdateElementPropertyDevMenuItem;
import org.qainsights.jmeter.ai.claudecode.ClaudeCodeMenuItem;
import org.qainsights.jmeter.ai.correlation.CorrelationMenuItem;

import javax.swing.*;

public class AiMenuCreator implements MenuCreator {
    private static final Logger log = LoggerFactory.getLogger(AiMenuCreator.class);

    @Override
    public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
        if (location == MENU_LOCATION.RUN) {
            try {
                // Create a temporary parent component to pass to AiMenuItem
                JMenu parentMenu = new JMenu("AI");
                return new JMenuItem[] {
                        new AiMenuItem(parentMenu),
                        new CorrelationMenuItem(),
                        new ClaudeCodeMenuItem(parentMenu),
                        new AddElementDevMenuItem(),
                        new UpdateElementPropertyDevMenuItem(),
                        new DeleteElementDevMenuItem(),
                        new ToggleElementDevMenuItem(),
                        new MoveElementDevMenuItem()
                };
            } catch (Throwable e) {
                log.error("Failed to load validate thread group plugin", e);
                return new JMenuItem[0];
            }

        } else {
            return new JMenuItem[0];
        }
    }

    @Override
    public JMenu[] getTopLevelMenus() {
        return new JMenu[0];
    }

    @Override
    public boolean localeChanged(MenuElement menu) {
        return false;
    }

    @Override
    public void localeChanged() {
    }
}
