package org.qainsights.jmeter.ai.gui;

import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WrapLayoutTest {

    @Test
    void capsOversizedComponentAtContainerContentWidth() {
        JPanel container = new JPanel(new WrapLayout(FlowLayout.LEFT, 2, 2));
        container.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        JPanel pill = new JPanel();
        pill.setPreferredSize(new Dimension(240, 30));
        container.add(pill);
        container.setSize(100, 50);

        container.doLayout();

        int contentWidth = container.getWidth() - container.getInsets().left - container.getInsets().right;
        assertTrue(pill.getWidth() <= contentWidth);
    }
}
