package org.qainsights.jmeter.ai.param;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.gui.ParameterDialogControls;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ParameterLayoutTest {
    @Test
    void controlsAndButtonsRemainOutsideScrollingAtSmallScreenSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel content = surface();
            content.setSize(Math.min(content.getPreferredSize().width, 800),
                    Math.min(content.getPreferredSize().height, 580));
            layout(content);
            checkControls(content, content);
        });
    }

    static JPanel surface() {
        JTable table = new JTable(new Object[][] {{true, "Login > username", "alice", "user", 1}},
                new String[] {"Use", "Field", "Value", "Variable", "Occurrences"});
        ParameterizeThisDialog.styleTable(table);
        table.setPreferredScrollableViewportSize(new Dimension(740, 160));
        return ParameterizeThisDialog.content(table, false, new JTextField("data/users.csv", 36),
                new JTextField(",", 3), new JComboBox<>(new String[] {"All threads", "Thread Group", "Thread"}),
                new JCheckBox("Recycle on EOF", true), new JCheckBox("Stop thread on EOF", false),
                new JLabel(" "), ParameterDialogControls.button("Apply variables", true),
                ParameterDialogControls.button("Cancel", false));
    }

    static void layout(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container) {
                layout((Container) component);
            }
        }
    }

    private static void checkControls(Container container, JPanel root) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton && !(component.getParent() instanceof JScrollBar)) {
                JButton button = (JButton) component;
                if (button.getText() != null && !button.getText().isEmpty()) {
                    Rectangle bounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), root);
                    assertTrue(new Rectangle(root.getSize()).contains(bounds), button.getText() + " is clipped");
                    assertTrue(button.getWidth() >= button.getPreferredSize().width);
                    assertTrue(button.getHeight() >= button.getPreferredSize().height);
                    assertNull(SwingUtilities.getAncestorOfClass(JScrollPane.class, button));
                }
            }
            if (component instanceof Container && !(component instanceof JScrollPane)) {
                checkControls((Container) component, root);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties("nonexistent.properties");
        com.github.weisj.darklaf.LafManager.install(new com.github.weisj.darklaf.theme.DarculaTheme());
        SwingUtilities.invokeAndWait(() -> {
            JPanel content = surface();
            content.setSize(Math.min(content.getPreferredSize().width, 800),
                    Math.min(content.getPreferredSize().height, 580));
            layout(content);
            checkControls(content, content);
            BufferedImage shot = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = shot.createGraphics();
            content.printAll(graphics);
            graphics.dispose();
            try {
                ImageIO.write(shot, "png", Path.of(args[0]).toFile());
                System.out.println("Off-screen Darklaf layout saved: " + args[0]);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
