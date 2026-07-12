package org.qainsights.jmeter.ai.gui;

import javax.swing.JTree;
import javax.swing.tree.DefaultTreeModel;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TreeActivityGlowController}, using {@code MockedStatic}
 * for {@code GuiPackage} (per {@code TreeNavigationButtonsTest}'s established
 * pattern) and a real {@link JTree}/{@link JMeterTreeNode} tree so renderer
 * installation and row-bounds lookups exercise real Swing tree behavior.
 */
class TreeActivityGlowControllerTest {

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    private static JTree realTreeFor(JMeterTreeNode wrapperRoot) {
        return new JTree(new DefaultTreeModel(wrapperRoot));
    }

    @Test
    void handleToolCallStarted_guiPackageNull_doesNotThrowAndDoesNotStartAnimating() {
        TreeActivityGlowController controller = new TreeActivityGlowController();
        try (MockedStatic<GuiPackage> mocked = mockStatic(GuiPackage.class)) {
            mocked.when(GuiPackage::getInstance).thenReturn(null);

            assertDoesNotThrow(() -> controller.handleToolCallStarted(null));
            assertFalse(controller.isAnimating());
        }
    }

    @Test
    void handleToolCallStarted_treeModelNull_doesNotThrow() {
        TreeActivityGlowController controller = new TreeActivityGlowController();
        GuiPackage gui = mock(GuiPackage.class);
        when(gui.getTreeModel()).thenReturn(null);
        try (MockedStatic<GuiPackage> mocked = mockStatic(GuiPackage.class)) {
            mocked.when(GuiPackage::getInstance).thenReturn(gui);

            assertDoesNotThrow(() -> controller.handleToolCallStarted("Test Plan"));
            assertFalse(controller.isAnimating());
        }
    }

    @Test
    void handleToolCallStarted_validSetup_installsRendererAndStartsAnimating() {
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        JMeterTreeNode testPlan = node("Test Plan");
        wrapperRoot.add(testPlan);
        JTree tree = realTreeFor(wrapperRoot);

        GuiPackage gui = mock(GuiPackage.class);
        JMeterTreeModel model = mock(JMeterTreeModel.class);
        JMeterTreeListener listener = mock(JMeterTreeListener.class);
        when(gui.getTreeModel()).thenReturn(model);
        when(model.getRoot()).thenReturn(wrapperRoot);
        when(gui.getTreeListener()).thenReturn(listener);
        when(listener.getJTree()).thenReturn(tree);

        TreeActivityGlowController controller = new TreeActivityGlowController();
        try (MockedStatic<GuiPackage> mocked = mockStatic(GuiPackage.class)) {
            mocked.when(GuiPackage::getInstance).thenReturn(gui);

            controller.handleToolCallStarted(null);

            assertTrue(tree.getCellRenderer() instanceof AgentActivityCellRenderer);
            assertSame(tree, controller.getInstalledTree());
            assertTrue(controller.isAnimating());
        }
    }

    @Test
    void handleToolCallStarted_calledTwice_installsRendererOnlyOnce() {
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        JMeterTreeNode testPlan = node("Test Plan");
        wrapperRoot.add(testPlan);
        JTree tree = realTreeFor(wrapperRoot);

        GuiPackage gui = mock(GuiPackage.class);
        JMeterTreeModel model = mock(JMeterTreeModel.class);
        JMeterTreeListener listener = mock(JMeterTreeListener.class);
        when(gui.getTreeModel()).thenReturn(model);
        when(model.getRoot()).thenReturn(wrapperRoot);
        when(gui.getTreeListener()).thenReturn(listener);
        when(listener.getJTree()).thenReturn(tree);

        TreeActivityGlowController controller = new TreeActivityGlowController();
        try (MockedStatic<GuiPackage> mocked = mockStatic(GuiPackage.class)) {
            mocked.when(GuiPackage::getInstance).thenReturn(gui);

            controller.handleToolCallStarted(null);
            Object firstRenderer = tree.getCellRenderer();
            controller.handleToolCallStarted("Test Plan");

            assertSame(firstRenderer, tree.getCellRenderer());
        }
    }

    @Test
    void handleRunFinished_withNothingStarted_doesNotThrow() {
        TreeActivityGlowController controller = new TreeActivityGlowController();
        assertDoesNotThrow(controller::handleRunFinished);
        assertFalse(controller.isAnimating());
    }

    @Test
    void onToolCallStarted_andOnRunFinished_areSafeToCallFromAnyThread() {
        TreeActivityGlowController controller = new TreeActivityGlowController();
        try (MockedStatic<GuiPackage> mocked = mockStatic(GuiPackage.class)) {
            mocked.when(GuiPackage::getInstance).thenReturn(null);

            assertDoesNotThrow(() -> controller.onToolCallStarted("Test Plan"));
            assertDoesNotThrow(controller::onRunFinished);
        }
    }
}
