package org.qainsights.jmeter.ai.utils;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

class JMeterElementRequestHandlerTest {

    private static final JMeterElementManager.TestPlanStatus READY =
            new JMeterElementManager.TestPlanStatus(true, null);
    private static final JMeterElementManager.TestPlanStatus NO_PLAN =
            new JMeterElementManager.TestPlanStatus(false, "No test plan is currently open");
    private static final JMeterElementManager.TestPlanStatus NO_GUI =
            new JMeterElementManager.TestPlanStatus(false, "JMeter GUI is not available");

    private MockedStatic<JMeterElementManager> manager;
    private MockedStatic<GuiPackage> guiPackage;
    private final List<String[]> addedElements = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Pure lookup helpers (normalizeElementType, isElementTypeSupported,
        // getDefaultNameForElement) keep their real behaviour; only the
        // GUI-touching entry points are stubbed.
        manager = mockStatic(JMeterElementManager.class, CALLS_REAL_METHODS);
        manager.when(JMeterElementManager::isTestPlanReady).thenReturn(READY);
        manager.when(() -> JMeterElementManager.addElement(anyString(), any())).thenAnswer(invocation -> {
            addedElements.add(new String[] { invocation.getArgument(0), invocation.getArgument(1) });
            return true;
        });
        guiPackage = mockStatic(GuiPackage.class);
        guiPackage.when(GuiPackage::getInstance).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        guiPackage.close();
        manager.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void returnsNullForBlankInput(String message) {
        assertNull(JMeterElementRequestHandler.processElementRequest(message));
        manager.verify(() -> JMeterElementManager.addElement(anyString(), any()), never());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "how do I configure a thread group?",
            "In one short sentence, what does HTTP status 503 mean?",
            "how do I add a thread group?",
            "what do a thread group and an http request do?",
            "Explain the csv data set and the http request",
            "thread group, is it the same as a user group?",
            "optimize my test plan",
            "hello there",
            "Write a detailed numbered list of 100 distinct JMeter test-plan review checks, each with two sentences of explanation. Do not edit or execute the plan.",
            "Write a guide to correlation and include examples of a regex extractor",
            "List the timers, controllers and listeners",
            "don't add a thread group",
            "summarize the thread group and the http request",
            "Include an example of a regex extractor in my guide"
    })
    void returnsNullWhenMessageIsNotAnAddRequest(String message) {
        assertNull(JMeterElementRequestHandler.processElementRequest(message));
        manager.verify(() -> JMeterElementManager.addElement(anyString(), any()), never());
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "add a thread group, threadgroup, Thread Group",
            "add an HTTP request, httprequest, Httprequest",
            "create a http sampler, httpsampler, HTTP Request",
            "add a JSR223 sampler, jsr223sampler, JSR223 Sampler",
            "add a JSR223 preprocessor, jsr223preprocessor, JSR223 PreProcessor",
            "add a JSR223 post processor, jsr223postprocessor, JSR223 PostProcessor",
            "add a CSV data set, csvdataset, CSV Data Set",
            "insert a loop controller, loopcontroller, Loop Controller",
            "add a response assertion, responseassert, Response Assertion",
            "add a constant timer, constanttimer, Constant Timer",
            "add a timer, constanttimer, Constant Timer",
            "add a listener, viewresultstree, View Results Tree",
            "add virtual users, threadgroup, Thread Group",
            "include a regex extractor, regexextractor, Regular Expression Extractor"
    })
    void mapsSynonymsToSupportedElementTypes(String message, String expectedType, String expectedDisplayName) {
        String response = JMeterElementRequestHandler.processElementRequest(message);

        assertEquals(1, addedElements.size(), "exactly one element should be added");
        assertEquals(expectedType, addedElements.get(0)[0]);
        assertNull(addedElements.get(0)[1], "no explicit name was requested");
        assertEquals("I've added a " + expectedDisplayName
                + " to your test plan. You can now configure it as needed.", response);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1} named {2}")
    @CsvSource({
            "add an HTTP request called Login, httprequest, Login",
            "add a thread group named Checkout Users, threadgroup, Checkout Users",
            "create a JSR223 sampler with name \"Setup Script\", jsr223sampler, Setup Script",
            "add a CSV data set with the name 'users.csv', csvdataset, users.csv"
    })
    void passesRequestedNameToAddElement(String message, String expectedType, String expectedName) {
        String response = JMeterElementRequestHandler.processElementRequest(message);

        assertEquals(1, addedElements.size());
        assertEquals(expectedType, addedElements.get(0)[0]);
        assertEquals(expectedName, addedElements.get(0)[1]);
        assertNotNull(response);
        assertTrue(response.startsWith("I've added a "), response);
        assertTrue(response.contains(" called \"" + expectedName + "\" to your test plan"), response);
    }

    @Test
    void stripsTrailingLocationClauseFromElementType() {
        String response = JMeterElementRequestHandler.processElementRequest(
                "add a constant timer to the thread group");

        assertEquals(1, addedElements.size());
        assertEquals("constanttimer", addedElements.get(0)[0]);
        assertNotNull(response);
        assertTrue(response.startsWith("I've added a Constant Timer"), response);
    }

    @Test
    void reportsUnknownElementTypeWithoutAddingAnything() {
        String response = JMeterElementRequestHandler.processElementRequest("add a flux capacitor");

        assertEquals("I couldn't find a JMeter element type \"flux capacitor\".", response);
        assertTrue(addedElements.isEmpty());
        manager.verify(JMeterElementManager::isTestPlanReady, never());
    }

    @Test
    void reportsFailureWhenAddElementReturnsFalse() {
        manager.when(() -> JMeterElementManager.addElement(anyString(), any())).thenReturn(false);

        String response = JMeterElementRequestHandler.processElementRequest("add a thread group");

        assertEquals("I tried to add a Thread Group, but encountered an error. "
                + "This might be due to compatibility issues with the selected node. "
                + "Please try selecting a different node in your test plan.", response);
    }

    @Test
    void splitsMultipleInstructionsAndPrependsActionVerb() {
        String response = JMeterElementRequestHandler.processElementRequest(
                "add a thread group, an HTTP request called Login, and a constant timer");

        assertEquals(3, addedElements.size());
        assertEquals("threadgroup", addedElements.get(0)[0]);
        assertNull(addedElements.get(0)[1]);
        assertEquals("httprequest", addedElements.get(1)[0]);
        assertEquals("Login", addedElements.get(1)[1]);
        assertEquals("constanttimer", addedElements.get(2)[0]);
        assertNull(addedElements.get(2)[1]);

        assertNotNull(response);
        assertTrue(response.startsWith("I've made the following changes:\n\n"), response);
        assertTrue(response.contains("1. I've added a Thread Group to your test plan."), response);
        assertTrue(response.contains("2. I've added a Httprequest called \"Login\" to your test plan."), response);
        assertTrue(response.contains("3. I've added a Constant Timer to your test plan."), response);
        assertFalse(response.contains("However, I encountered some issues"), response);
    }

    @Test
    void splitsOnSemicolonsAndHonoursPerClauseVerbs() {
        String response = JMeterElementRequestHandler.processElementRequest(
                "create a thread group; add a response assertion");

        assertEquals(2, addedElements.size());
        assertEquals("threadgroup", addedElements.get(0)[0]);
        assertEquals("responseassert", addedElements.get(1)[0]);
        assertNotNull(response);
        assertTrue(response.contains("1. I've added a Thread Group"), response);
        assertTrue(response.contains("2. I've added a Response Assertion"), response);
    }

    @Test
    void aggregatesSuccessesAndErrorsAcrossInstructions() {
        String response = JMeterElementRequestHandler.processElementRequest(
                "add a thread group, a flux capacitor, and a constant timer");

        assertEquals(2, addedElements.size());
        assertEquals("threadgroup", addedElements.get(0)[0]);
        assertEquals("constanttimer", addedElements.get(1)[0]);

        assertNotNull(response);
        assertTrue(response.startsWith("I've made the following changes:\n\n"), response);
        assertTrue(response.contains("1. I've added a Thread Group"), response);
        assertTrue(response.contains("2. I've added a Constant Timer"), response);
        assertTrue(response.contains("However, I encountered some issues:\n\n1. "
                + "I couldn't find a JMeter element type \"flux capacitor\"."), response);
    }

    @Test
    void multiInstructionWithoutVerbFallsBackToElementTypeExtraction() {
        String response = JMeterElementRequestHandler.processElementRequest(
                "thread group and http request");

        assertEquals(2, addedElements.size());
        assertEquals("threadgroup", addedElements.get(0)[0]);
        assertNull(addedElements.get(0)[1]);
        assertEquals("httpsampler", addedElements.get(1)[0]);
        assertNotNull(response);
        assertTrue(response.contains("1. I've added a Thread Group"), response);
        assertTrue(response.contains("2. I've added a HTTP Request"), response);
    }

    @Test
    void multiInstructionReportsUnrecognisedClauses() {
        String response = JMeterElementRequestHandler.processElementRequest(
                "add a thread group, then dance wildly");

        assertEquals(1, addedElements.size());
        assertEquals("threadgroup", addedElements.get(0)[0]);
        assertNotNull(response);
        assertTrue(response.startsWith("I've added a Thread Group to your test plan."), response);
        assertTrue(response.contains("However, I encountered some issues:\n\n1. "
                + "I couldn't find a JMeter element type \"then dance wildly\"."), response);
    }

    @Test
    void multiInstructionReportsFailedAdditionsAsErrors() {
        manager.when(() -> JMeterElementManager.addElement(eq("constanttimer"), any())).thenReturn(false);

        String response = JMeterElementRequestHandler.processElementRequest(
                "add a thread group and a constant timer");

        assertNotNull(response);
        assertTrue(response.startsWith("I've added a Thread Group to your test plan."), response);
        assertTrue(response.contains("However, I encountered some issues:\n\n1. "
                + "I tried to add a Constant Timer, but encountered an error."), response);
    }

    @Test
    void bootstrapsTestPlanWhenNoneIsOpenAndRetries() {
        manager.when(JMeterElementManager::isTestPlanReady).thenReturn(NO_PLAN, READY);
        manager.when(JMeterElementManager::ensureTestPlanExists).thenReturn(true);
        manager.when(JMeterElementManager::selectTestPlanNode).thenReturn(true);

        String response = JMeterElementRequestHandler.processElementRequest("add a thread group");

        manager.verify(JMeterElementManager::ensureTestPlanExists, times(1));
        manager.verify(JMeterElementManager::selectTestPlanNode, times(1));
        manager.verify(JMeterElementManager::isTestPlanReady, times(2));
        assertEquals(1, addedElements.size());
        assertEquals("threadgroup", addedElements.get(0)[0]);
        assertEquals("I've added a Thread Group to your test plan. You can now configure it as needed.", response);
    }

    @Test
    void explainsWhenPlanCreatedAndSelectedButStillNotReady() {
        manager.when(JMeterElementManager::isTestPlanReady).thenReturn(NO_PLAN);
        manager.when(JMeterElementManager::ensureTestPlanExists).thenReturn(true);
        manager.when(JMeterElementManager::selectTestPlanNode).thenReturn(true);

        String response = JMeterElementRequestHandler.processElementRequest("add a constant timer");

        assertTrue(addedElements.isEmpty());
        assertEquals("I've created a new test plan for you and selected it. However, a Constant Timer "
                + "cannot be added directly to the test plan. Please add a Thread Group first, "
                + "then try adding the Constant Timer again.", response);
    }

    @Test
    void asksUserToSelectPlanWhenCreatedButSelectionFailed() {
        manager.when(JMeterElementManager::isTestPlanReady).thenReturn(NO_PLAN);
        manager.when(JMeterElementManager::ensureTestPlanExists).thenReturn(true);
        manager.when(JMeterElementManager::selectTestPlanNode).thenReturn(false);

        String response = JMeterElementRequestHandler.processElementRequest("add a thread group");

        assertTrue(addedElements.isEmpty());
        assertEquals("I've created a new test plan for you. Please select the Test Plan node "
                + "and try adding the Thread Group again.", response);
    }

    @Test
    void reportsWhenTestPlanCouldNotBeCreated() {
        manager.when(JMeterElementManager::isTestPlanReady).thenReturn(NO_PLAN);
        manager.when(JMeterElementManager::ensureTestPlanExists).thenReturn(false);

        String response = JMeterElementRequestHandler.processElementRequest("add a thread group");

        manager.verify(JMeterElementManager::selectTestPlanNode, never());
        assertTrue(addedElements.isEmpty());
        assertEquals("I'd like to add a Thread Group for you, but I couldn't create a test plan. "
                + "Please create a test plan manually first.", response);
    }

    @Test
    void doesNotBootstrapForOtherReadinessErrors() {
        manager.when(JMeterElementManager::isTestPlanReady).thenReturn(NO_GUI);

        String response = JMeterElementRequestHandler.processElementRequest("add a thread group");

        manager.verify(JMeterElementManager::ensureTestPlanExists, never());
        assertTrue(addedElements.isEmpty());
        assertEquals("I'd like to add a Thread Group for you, but jmeter gui is not available. "
                + "Please make sure you have a test plan open.", response);
    }

    @Test
    void questionGateKeepsClausesWithActionVerbs() {
        assertFalse(JMeterElementRequestHandler.isQuestion("can you add a thread group?",
                java.util.List.of("can you add a thread group?")));
        assertFalse(JMeterElementRequestHandler.isQuestion("thread group and http request",
                java.util.List.of("thread group", "http request")));
        assertTrue(JMeterElementRequestHandler.isQuestion("In one short sentence, what does HTTP status 503 mean?",
                java.util.List.of("In one short sentence", "what does HTTP status 503 mean?")));
    }

    @ParameterizedTest
    @ValueSource(strings = { "please add a thread group", "can you add a thread group", "Could you please create a thread group", "add a thread group named Not Found" })
    void politeAddRequestsStillAddElements(String message) {
        String response = JMeterElementRequestHandler.processElementRequest(message);

        assertEquals(1, addedElements.size(), response);
        assertEquals("threadgroup", addedElements.get(0)[0]);
    }

    private JMeterTreeNode[] fakeTree(JMeterTreeNode selected) {
        JMeterTreeNode[] current = { selected };
        GuiPackage gui = org.mockito.Mockito.mock(GuiPackage.class);
        org.apache.jmeter.gui.tree.JMeterTreeListener listener =
                org.mockito.Mockito.mock(org.apache.jmeter.gui.tree.JMeterTreeListener.class);
        javax.swing.JTree tree = org.mockito.Mockito.mock(javax.swing.JTree.class);
        org.mockito.Mockito.when(gui.getTreeListener()).thenReturn(listener);
        org.mockito.Mockito.when(listener.getJTree()).thenReturn(tree);
        org.mockito.Mockito.when(listener.getCurrentNode()).thenAnswer(invocation -> current[0]);
        org.mockito.Mockito.doAnswer(invocation -> {
            current[0] = (JMeterTreeNode) ((javax.swing.tree.TreePath) invocation.getArgument(0))
                    .getLastPathComponent();
            return null;
        }).when(tree).setSelectionPath(any());
        guiPackage.when(GuiPackage::getInstance).thenReturn(gui);
        manager.when(() -> JMeterElementManager.addElement(anyString(), any())).thenAnswer(invocation -> {
            String type = invocation.getArgument(0);
            org.apache.jmeter.testelement.TestElement element;
            if (type.contains("thread")) {
                element = new org.apache.jmeter.threads.ThreadGroup();
            } else if (type.contains("assert")) {
                element = new org.apache.jmeter.assertions.ResponseAssertion();
            } else if (type.contains("runtime")) {
                element = new org.apache.jmeter.control.RunTime();
            } else {
                element = new org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy();
            }
            element.setName(type);
            current[0].add(new JMeterTreeNode(element, null));
            addedElements.add(new String[] { type, invocation.getArgument(1) });
            return true;
        });
        return current;
    }

    private static String childNames(JMeterTreeNode node) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) node.getChildAt(i);
            names.add(child.getName() + (child.getChildCount() > 0 ? childNames(child) : ""));
        }
        return names.toString();
    }

    @Test
    void bareListPutsTheSamplerInsideTheNewThreadGroup() {
        JMeterTreeNode plan = new JMeterTreeNode(new org.apache.jmeter.testelement.TestPlan(), null);
        fakeTree(plan);

        JMeterElementRequestHandler.processElementRequest("thread group and http request");

        assertEquals("[threadgroup[httpsampler]]", childNames(plan));
    }

    @Test
    void secondThreadGroupGoesBackUnderTheTestPlan() {
        JMeterTreeNode plan = new JMeterTreeNode(new org.apache.jmeter.testelement.TestPlan(), null);
        fakeTree(plan);

        JMeterElementRequestHandler.processElementRequest(
                "thread group, http request, thread group and http request");

        assertEquals("[threadgroup[httpsampler], threadgroup[httpsampler]]", childNames(plan));
    }

    @Test
    void consecutiveSamplersStaySiblings() {
        JMeterTreeNode plan = new JMeterTreeNode(new org.apache.jmeter.testelement.TestPlan(), null);
        fakeTree(plan);

        JMeterElementRequestHandler.processElementRequest("add a thread group, an http request and an http request");

        assertEquals("[threadgroup[httprequest, httprequest]]", childNames(plan));
    }

    @Test
    void assertionAfterASamplerIsScopedToThatSampler() {
        JMeterTreeNode plan = new JMeterTreeNode(new org.apache.jmeter.testelement.TestPlan(), null);
        fakeTree(plan);

        JMeterElementRequestHandler.processElementRequest("thread group, http request and response assertion");

        assertEquals("[threadgroup[httpsampler[responseassert]]]", childNames(plan));
    }

    @Test
    void samplerAfterARuntimeControllerGoesInsideIt() {
        JMeterTreeNode plan = new JMeterTreeNode(new org.apache.jmeter.testelement.TestPlan(), null);
        fakeTree(plan);

        JMeterElementRequestHandler.processElementRequest("thread group, runtime controller and http request");

        assertEquals("[threadgroup[runtimecontroller[httpsampler]]]", childNames(plan));
    }
}
