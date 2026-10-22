package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.*;

class SidePanelDockTest {
    private JPanel contentPane;
    private JPanel main;
    private JPanel cli;
    private JPanel chat;

    @BeforeEach
    void setUp() {
        contentPane = new JPanel(new BorderLayout());
        main = new JPanel();
        main.setName("main");
        cli = new JPanel();
        cli.setName("cli");
        cli.setPreferredSize(new Dimension(300, 200));
        chat = new JPanel();
        chat.setName("chat");
        chat.setPreferredSize(new Dimension(350, 200));
        contentPane.add(main, BorderLayout.CENTER);
    }

    @Test
    void dockingAndUndockingRestoresMainAtCenter() {
        JSplitPane cliSplit = dock(cli, 0.65);

        SidePanelDock.undock(cliSplit);

        assertEquals(1, contentPane.getComponentCount());
        assertSame(main, contentPane.getComponent(0));
        assertEquals(BorderLayout.CENTER, centerConstraint(main));
    }

    @Test
    void undockingInnerSplitKeepsOuterPanelAndReplacesItsLeftSide() {
        JSplitPane cliSplit = dock(cli, 0.65);
        JSplitPane chatSplit = dock(chat, 0.7);

        SidePanelDock.undock(cliSplit);

        assertEquals(1, contentPane.getComponentCount());
        assertSame(chatSplit, contentPane.getComponent(0));
        assertEquals(BorderLayout.CENTER, centerConstraint(chatSplit));
        assertSame(main, chatSplit.getLeftComponent());
        assertFalse(SwingUtilities.isDescendingFrom(cli, contentPane));
    }

    @Test
    void undockingOuterSplitRestoresInnerSplitAtCenter() {
        JSplitPane cliSplit = dock(cli, 0.65);
        JSplitPane chatSplit = dock(chat, 0.7);

        SidePanelDock.undock(chatSplit);

        assertEquals(1, contentPane.getComponentCount());
        assertSame(cliSplit, contentPane.getComponent(0));
        assertEquals(BorderLayout.CENTER, centerConstraint(cliSplit));
    }

    @Test
    void undockingInnerThenOuterRestoresMainAtCenter() {
        JSplitPane cliSplit = dock(cli, 0.65);
        JSplitPane chatSplit = dock(chat, 0.7);

        SidePanelDock.undock(cliSplit);
        SidePanelDock.undock(chatSplit);

        assertRestoredMain();
    }

    @Test
    void undockingOuterThenInnerRestoresMainAtCenter() {
        JSplitPane cliSplit = dock(cli, 0.65);
        JSplitPane chatSplit = dock(chat, 0.7);

        SidePanelDock.undock(chatSplit);
        SidePanelDock.undock(cliSplit);

        assertRestoredMain();
    }

    @Test
    void undockingSplitWithoutParentIsNoOp() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, main, cli);

        assertDoesNotThrow(() -> SidePanelDock.undock(split));
        assertNull(split.getParent());
    }

    @Test
    void undockingInnerSplitPreservesOuterDividerLocation() {
        main.setPreferredSize(new Dimension(700, 600));
        JSplitPane cliSplit = dock(cli, 0.65);
        JSplitPane chatSplit = dock(chat, 0.7);
        chatSplit.setResizeWeight(0.25);
        contentPane.setSize(1000, 600);
        layoutTree(contentPane);
        chatSplit.setDividerLocation(420);
        layoutTree(contentPane);
        int before = chatSplit.getDividerLocation();

        assertEquals(420, before);
        SidePanelDock.undock(cliSplit);
        layoutTree(contentPane);

        assertEquals(before, chatSplit.getDividerLocation());
    }

    private JSplitPane dock(Component panel, double resizeWeight) {
        return SidePanelDock.dock(contentPane, panel, resizeWeight, 1000);
    }

    private Object centerConstraint(Component component) {
        return ((BorderLayout) contentPane.getLayout()).getConstraints(component);
    }

    private void layoutTree(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container) {
                layoutTree((Container) component);
            }
        }
    }

    private void assertRestoredMain() {
        assertEquals(1, contentPane.getComponentCount());
        assertSame(main, contentPane.getComponent(0));
        assertEquals(BorderLayout.CENTER, centerConstraint(main));
    }
}
