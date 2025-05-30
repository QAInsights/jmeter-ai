package org.nfe.jmeter.ai.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;

class AI extends AbstractAction {

    public static final KeyStroke AI   = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.ALT_DOWN_MASK);
    private static final Logger log = LoggerFactory.getLogger(AI.class);

    AI() {
        super("AI");
        putValue(Action.ACTION_COMMAND_KEY, "ai");
        putValue(Action.ACCELERATOR_KEY, AI);
        putValue(Action.SMALL_ICON, AiMenuItem.getButtonIcon(12));
    }

    public void actionPerformed(ActionEvent actionEvent) {
        log.debug("Validate Thread Groups Action performed from Run menu!");
    }
}
