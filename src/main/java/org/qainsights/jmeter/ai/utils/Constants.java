package org.qainsights.jmeter.ai.utils;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

public class Constants {

    private static final int MODIFIER =
            System.getProperty("os.name").toLowerCase().contains("mac")
                    ? InputEvent.META_DOWN_MASK
                    : InputEvent.CTRL_DOWN_MASK;

    public static final KeyStroke UNDO_KEY_STROKE =
            KeyStroke.getKeyStroke(KeyEvent.VK_Z, MODIFIER);

    public static final KeyStroke REDO_KEY_STROKE =
            KeyStroke.getKeyStroke(KeyEvent.VK_Z, MODIFIER | InputEvent.SHIFT_DOWN_MASK);

    public static final String WELCOME_MESSAGE = "# Welcome to Feather Wand - JMeter Agent\n\n" +
            "I'm here to help you with your JMeter test plan. You can ask me questions about JMeter, " +
            "request help with creating test elements, or get advice on optimizing your tests.\n\n" +
            "**Special commands:**\n" +
            "- Use `@this` to get information about the currently selected element\n" +
            "- Use `@optimize` to get optimization suggestions for your test plan\n" +
            "- Use `@lint` to rename elements in your test plan with meaningful names\n" +
            "- Use `@wrap` to group HTTP request samplers under Transaction Controllers\n" +
            "- Use `@usage` to view usage statistics for your AI interactions\n\n" +
            "How can I assist you today?";
}
