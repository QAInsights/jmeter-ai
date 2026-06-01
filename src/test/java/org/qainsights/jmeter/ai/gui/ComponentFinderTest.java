package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.*;

class ComponentFinderTest {

    @Test
    void testFindComponent_Success() {
        JPanel parent = new JPanel();
        JPanel childContainer = new JPanel();
        JButton targetButton = new JButton("Target");
        
        childContainer.add(targetButton);
        parent.add(childContainer);

        ComponentFinder<JButton> finder = new ComponentFinder<>(JButton.class);
        JButton found = finder.findComponentIn(parent);

        assertNotNull(found);
        assertEquals(targetButton, found);
    }

    @Test
    void testFindComponent_NotFound() {
        JPanel parent = new JPanel();
        JPanel childContainer = new JPanel();
        JLabel label = new JLabel("Not a button");
        
        childContainer.add(label);
        parent.add(childContainer);

        ComponentFinder<JButton> finder = new ComponentFinder<>(JButton.class);
        JButton found = finder.findComponentIn(parent);

        assertNull(found);
    }

    @Test
    void testFindComponent_NullContainerComponents() {
        JPanel parent = new JPanel() {
            @Override
            public Component[] getComponents() {
                return new Component[0];
            }
        };

        ComponentFinder<JButton> finder = new ComponentFinder<>(JButton.class);
        JButton found = finder.findComponentIn(parent);

        assertNull(found);
    }
}
