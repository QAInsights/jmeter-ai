package org.qainsights.jmeter.ai.wrap;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.MainFrame;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link WrapCommandHandler}: pure name/grouping helpers plus wrap, undo and redo
 * against a real headless {@link JMeterTreeModel} with {@code GuiPackage} mocked via
 * {@code MockedStatic} (same pattern as {@code CodeCommandHandlerTest}).
 */
class WrapCommandHandlerTest {

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    @BeforeEach
    void resetHistory() {
        WrapCommandHandler.clearHistory();
    }

    private static JMeterTreeNode node(TestElement element, String name) {
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    private static JMeterTreeNode samplerNode(String name) {
        return node(new HTTPSamplerProxy(), name);
    }

    private static List<String> childNames(JMeterTreeNode parent) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            names.add(((JMeterTreeNode) parent.getChildAt(i)).getName());
        }
        return names;
    }

    private static JMeterTreeNode child(JMeterTreeNode parent, int index) {
        return (JMeterTreeNode) parent.getChildAt(index);
    }

    @Nested
    class SimplifyName {
        private final WrapCommandHandler handler = new WrapCommandHandler();

        @Test
        void replacesStandaloneNumericIdsWithIdPlaceholder() {
            assertEquals("GET /users/{ID}", handler.simplifyName("GET /users/123"));
            assertEquals("GET /users/{ID}/orders/{ID}", handler.simplifyName("GET /users/7/orders/42"));
        }

        @Test
        void replacesUuidsWithUuidPlaceholder() {
            assertEquals("GET /items/{UUID}",
                    handler.simplifyName("GET /items/3f2504e0-4f89-11d3-9a0c-0305e82c3301"));
        }

        @Test
        void replacesEmbeddedDigitsWithNumPlaceholder() {
            assertEquals("user{NUM} profile", handler.simplifyName("user42 profile"));
            assertEquals("v{NUM}api", handler.simplifyName("v2api"));
        }

        @Test
        void handlesMixedIdsAndEmbeddedDigits() {
            assertEquals("GET /v{NUM}/users/{ID}/item{NUM}",
                    handler.simplifyName("GET /v1/users/99/item7"));
        }

        @Test
        void leavesNamesWithoutDigitsUnchanged() {
            assertEquals("POST /login", handler.simplifyName("POST /login"));
            assertEquals("", handler.simplifyName(""));
        }

        @Test
        void identicalAndEquivalentNamesProduceSamePattern() {
            assertEquals(handler.simplifyName("Search 1"), handler.simplifyName("Search 1"));
            assertEquals(handler.simplifyName("Search 1"), handler.simplifyName("Search 250"));
            assertNotEquals(handler.simplifyName("Search 1"), handler.simplifyName("Browse 1"));
        }
    }

    @Nested
    class GroupSamplersBySimilarity {
        private final WrapCommandHandler handler = new WrapCommandHandler();

        @Test
        void emptyListProducesNoGroups() {
            assertTrue(handler.groupSamplersBySimilarity(new ArrayList<>()).isEmpty());
        }

        @Test
        void groupsByParentPathAndSimplifiedName() {
            JMeterTreeNode tg = node(new ThreadGroup(), "Thread Group");
            JMeterTreeNode u1 = samplerNode("GET /users/1");
            JMeterTreeNode u2 = samplerNode("GET /users/2");
            JMeterTreeNode o1 = samplerNode("POST /orders");
            JMeterTreeNode o2 = samplerNode("POST /orders");
            JMeterTreeNode login = samplerNode("POST /login");
            for (JMeterTreeNode s : List.of(u1, u2, o1, o2, login)) {
                tg.add(s);
            }

            Map<String, List<JMeterTreeNode>> groups =
                    handler.groupSamplersBySimilarity(List.of(u1, u2, o1, o2, login));

            assertEquals(3, groups.size());
            assertEquals(List.of(u1, u2), groups.get("Thread Group: GET /users/{ID}"));
            assertEquals(List.of(o1, o2), groups.get("Thread Group: POST /orders"));
            assertEquals(List.of(login), groups.get("Thread Group: POST /login"));
        }

        @Test
        void sameNameUnderDifferentParentsStaysInSeparateGroups() {
            JMeterTreeNode tg = node(new ThreadGroup(), "Thread Group");
            JMeterTreeNode nested = node(new TransactionController(), "Nested");
            tg.add(nested);
            JMeterTreeNode direct = samplerNode("GET /users/1");
            JMeterTreeNode inNested = samplerNode("GET /users/2");
            tg.add(direct);
            nested.add(inNested);

            Map<String, List<JMeterTreeNode>> groups =
                    handler.groupSamplersBySimilarity(List.of(direct, inNested));

            assertEquals(2, groups.size());
            assertEquals(List.of(direct), groups.get("Thread Group: GET /users/{ID}"));
            assertEquals(List.of(inNested), groups.get("Thread Group > Nested: GET /users/{ID}"));
        }
    }

    @Nested
    class TreeMutations {
        private final WrapCommandHandler handler = new WrapCommandHandler();
        private JMeterTreeModel model;
        private JMeterTreeNode testPlanNode;
        private JMeterTreeNode threadGroupNode;
        private GuiPackage gui;
        private JMeterTreeListener treeListener;

        @BeforeEach
        void setUp() throws Exception {
            model = new JMeterTreeModel();
            JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
            for (int i = 0; i < root.getChildCount(); i++) {
                JMeterTreeNode c = (JMeterTreeNode) root.getChildAt(i);
                if (c.getTestElement() instanceof TestPlan) {
                    testPlanNode = c;
                }
            }
            assertNotNull(testPlanNode);
            ThreadGroup tg = new ThreadGroup();
            tg.setName("Thread Group");
            threadGroupNode = model.addComponent(tg, testPlanNode);

            gui = mock(GuiPackage.class);
            treeListener = mock(JMeterTreeListener.class);
            when(gui.getTreeModel()).thenReturn(model);
            when(gui.getTreeListener()).thenReturn(treeListener);
            when(gui.getGui(any(TestElement.class))).thenReturn(mock(JMeterGUIComponent.class));
            when(gui.getMainFrame()).thenReturn(mock(MainFrame.class));
            when(treeListener.getCurrentNode()).thenReturn(threadGroupNode);
        }

        private JMeterTreeNode addSampler(String name) throws Exception {
            HTTPSamplerProxy sampler = new HTTPSamplerProxy();
            sampler.setName(name);
            return model.addComponent(sampler, threadGroupNode);
        }

        private MockedStatic<GuiPackage> mockGui() {
            MockedStatic<GuiPackage> mocked = mockStatic(GuiPackage.class);
            mocked.when(GuiPackage::getInstance).thenReturn(gui);
            return mocked;
        }

        private List<JMeterTreeNode> addFourSamplersInTwoGroups() throws Exception {
            return List.of(
                    addSampler("GET /users/1"),
                    addSampler("GET /users/2"),
                    addSampler("POST /orders"),
                    addSampler("POST /orders"));
        }

        @Test
        void wrap_guiPackageNull_returnsError() {
            try (MockedStatic<GuiPackage> mocked = mockStatic(GuiPackage.class)) {
                mocked.when(GuiPackage::getInstance).thenReturn(null);
                assertEquals("Error: JMeter GUI is not available.", handler.processWrapCommand());
            }
        }

        @Test
        void wrap_noNodeSelected_asksForThreadGroup() {
            when(treeListener.getCurrentNode()).thenReturn(null);
            try (MockedStatic<GuiPackage> mocked = mockGui()) {
                assertEquals("Please select a Thread Group in the test plan before using the @wrap command.",
                        handler.processWrapCommand());
            }
        }

        @Test
        void wrap_nonThreadGroupSelected_namesSelectedElementType() {
            when(treeListener.getCurrentNode()).thenReturn(testPlanNode);
            try (MockedStatic<GuiPackage> mocked = mockGui()) {
                assertEquals("Please select a Thread Group in the test plan before using the @wrap command. "
                                + "The currently selected element is a TestPlan.",
                        handler.processWrapCommand());
            }
            assertEquals(1, testPlanNode.getChildCount());
        }

        @Test
        void wrap_threadGroupWithoutSamplers_reportsNoSamplers() {
            try (MockedStatic<GuiPackage> mocked = mockGui()) {
                assertEquals("No samplers found in the selected Thread Group.", handler.processWrapCommand());
            }
            assertEquals(0, threadGroupNode.getChildCount());
        }

        @Test
        void wrap_groupsSamplersIntoTransactionControllersPreservingOrder() throws Exception {
            List<JMeterTreeNode> samplers = addFourSamplersInTwoGroups();
            JMeterTreeNode assertion = model.addComponent(new ResponseAssertion(), samplers.get(0));
            assertion.getTestElement().setName("Assert 200");

            String result;
            try (MockedStatic<GuiPackage> mocked = mockGui()) {
                result = handler.processWrapCommand();
            }

            assertEquals("Successfully grouped samplers into 2 Transaction Controllers based on similarity.", result);
            assertEquals(List.of("Transaction - GET /users/{ID}", "Transaction - POST /orders"),
                    childNames(threadGroupNode));

            JMeterTreeNode usersTc = child(threadGroupNode, 0);
            JMeterTreeNode ordersTc = child(threadGroupNode, 1);
            assertInstanceOf(TransactionController.class, usersTc.getTestElement());
            assertInstanceOf(TransactionController.class, ordersTc.getTestElement());
            assertEquals(List.of(samplers.get(0), samplers.get(1)),
                    List.of(child(usersTc, 0), child(usersTc, 1)));
            assertEquals(List.of(samplers.get(2), samplers.get(3)),
                    List.of(child(ordersTc, 0), child(ordersTc, 1)));

            assertSame(assertion, child(samplers.get(0), 0), "sampler children must move with the sampler");
        }

        @Test
        void wrap_skipsSamplersAlreadyInsideTransactionController() throws Exception {
            TransactionController existing = new TransactionController();
            existing.setName("Existing TC");
            JMeterTreeNode existingNode = model.addComponent(existing, threadGroupNode);
            HTTPSamplerProxy wrapped = new HTTPSamplerProxy();
            wrapped.setName("GET /already/1");
            JMeterTreeNode wrappedNode = model.addComponent(wrapped, existingNode);
            JMeterTreeNode loose = addSampler("GET /loose");

            String result;
            try (MockedStatic<GuiPackage> mocked = mockGui()) {
                result = handler.processWrapCommand();
            }

            assertEquals("Successfully grouped samplers into 1 Transaction Controllers based on similarity.", result);
            assertEquals(List.of("Existing TC", "Transaction - GET /loose"), childNames(threadGroupNode));
            assertSame(wrappedNode, child(existingNode, 0));
            assertSame(loose, child(child(threadGroupNode, 1), 0));
        }

        @Test
        void undo_withoutPriorWrap_reportsNothingToUndo() {
            assertEquals("Nothing to undo.", handler.undoLastWrap());
        }

        @Test
        void redo_withoutPriorUndo_reportsNothingToRedo() {
            assertEquals("Nothing to redo.", handler.redoLastUndo());
        }

        @Test
        void undo_restoresOriginalOrderAndRemovesEmptyControllers() throws Exception {
            List<JMeterTreeNode> samplers = addFourSamplersInTwoGroups();

            String undoResult;
            try (MockedStatic<GuiPackage> mocked = mockGui()) {
                handler.processWrapCommand();
                undoResult = handler.undoLastWrap();
            }

            assertEquals("Successfully unwrapped 4 samplers.", undoResult);
            assertEquals(4, threadGroupNode.getChildCount());
            for (int i = 0; i < samplers.size(); i++) {
                assertSame(samplers.get(i), child(threadGroupNode, i), "sampler at index " + i);
            }
            assertEquals("Nothing to undo.", handler.undoLastWrap());
        }

        @Test
        void undo_restoresOrderWhenGroupsWereInterleaved() throws Exception {
            List<JMeterTreeNode> samplers = List.of(
                    addSampler("GET /users/1"),
                    addSampler("POST /orders"),
                    addSampler("GET /users/2"),
                    addSampler("POST /orders"),
                    addSampler("GET /users/3"));

            String wrapResult;
            String undoResult;
            try (MockedStatic<GuiPackage> mocked = mockGui()) {
                wrapResult = handler.processWrapCommand();
                undoResult = handler.undoLastWrap();
            }

            assertEquals("Successfully grouped samplers into 2 Transaction Controllers based on similarity.", wrapResult);
            assertEquals("Successfully unwrapped 5 samplers.", undoResult);
            assertEquals(5, threadGroupNode.getChildCount());
            for (int i = 0; i < samplers.size(); i++) {
                assertSame(samplers.get(i), child(threadGroupNode, i), "sampler at index " + i);
            }
        }

        @Test
        void undo_guiPackageNull_returnsErrorAndKeepsTree() throws Exception {
            addFourSamplersInTwoGroups();
            try (MockedStatic<GuiPackage> mocked = mockGui()) {
                handler.processWrapCommand();
            }
            List<String> wrapped = childNames(threadGroupNode);

            try (MockedStatic<GuiPackage> mocked = mockStatic(GuiPackage.class)) {
                mocked.when(GuiPackage::getInstance).thenReturn(null);
                assertEquals("Error: JMeter GUI is not available.", handler.undoLastWrap());
            }
            assertEquals(wrapped, childNames(threadGroupNode));
        }

        @Test
        void redo_afterUndo_rewrapsSamplersUnderRecreatedControllers() throws Exception {
            List<JMeterTreeNode> samplers = addFourSamplersInTwoGroups();

            String redoResult;
            try (MockedStatic<GuiPackage> mocked = mockGui()) {
                handler.processWrapCommand();
                handler.undoLastWrap();
                redoResult = handler.redoLastUndo();
            }

            assertEquals("Successfully rewrapped 4 samplers.", redoResult);
            assertSame(threadGroupNode, child(testPlanNode, 0), "thread group must stay in the plan");
            assertEquals(List.of("Transaction - GET /users/{ID}", "Transaction - POST /orders"),
                    childNames(threadGroupNode));
            for (int i = 0; i < threadGroupNode.getChildCount(); i++) {
                assertInstanceOf(TransactionController.class, child(threadGroupNode, i).getTestElement());
            }
            JMeterTreeNode usersTc = (JMeterTreeNode) samplers.get(0).getParent();
            JMeterTreeNode ordersTc = (JMeterTreeNode) samplers.get(2).getParent();
            assertEquals("Transaction - GET /users/{ID}", usersTc.getName());
            assertEquals("Transaction - POST /orders", ordersTc.getName());
            assertSame(usersTc, samplers.get(1).getParent());
            assertSame(ordersTc, samplers.get(3).getParent());
            assertEquals(List.of(samplers.get(0), samplers.get(1)), List.of(child(usersTc, 0), child(usersTc, 1)));
            assertEquals(List.of(samplers.get(2), samplers.get(3)), List.of(child(ordersTc, 0), child(ordersTc, 1)));
            assertEquals("Nothing to redo.", handler.redoLastUndo());
        }

        @Test
        void undo_afterRedo_restoresOriginalOrderAgain() throws Exception {
            List<JMeterTreeNode> samplers = addFourSamplersInTwoGroups();

            String secondUndo;
            try (MockedStatic<GuiPackage> mocked = mockGui()) {
                handler.processWrapCommand();
                handler.undoLastWrap();
                handler.redoLastUndo();
                secondUndo = handler.undoLastWrap();
            }

            assertEquals("Successfully unwrapped 4 samplers.", secondUndo);
            assertEquals(4, threadGroupNode.getChildCount());
            for (int i = 0; i < samplers.size(); i++) {
                assertSame(samplers.get(i), child(threadGroupNode, i), "sampler at index " + i);
            }
        }

        @Test
        void newWrap_clearsRedoHistory() throws Exception {
            addFourSamplersInTwoGroups();
            try (MockedStatic<GuiPackage> mocked = mockGui()) {
                handler.processWrapCommand();
                handler.undoLastWrap();
                handler.processWrapCommand();
            }
            assertEquals("Nothing to redo.", handler.redoLastUndo());
        }
    }
}
