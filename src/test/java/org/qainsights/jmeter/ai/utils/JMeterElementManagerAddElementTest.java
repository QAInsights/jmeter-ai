package org.qainsights.jmeter.ai.utils;

import org.apache.jmeter.control.gui.TestPlanGui;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.gui.ThreadGroupGui;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JMeterElementManagerAddElementTest {

    /**
     * Entries shipped by ApacheJMeter_core/_http/_java (the only JMeter modules on this
     * project's classpath) that {@code addElement} must be able to instantiate.
     */
    private static final Set<String> CORE_ENTRIES = Set.of(
            "httpsampler", "httptestsample", "httprequest", "graphqlhttprequest", "ajpsampler", "javarequest",
            "jsr223sampler", "beanshellsampler", "debugsampler", "flowcontrolaction", "threadgroup",
            "loopcontroller", "ifcontroller", "whilecontroller", "transactioncontroller", "foreachcontroller",
            "modulecontroller", "runtimecontroller", "responseassert", "jsonassertion", "durationassertion",
            "sizeassertion", "xpathassertion", "jsr223assertion", "constanttimer", "uniformrandomtimer",
            "gaussianrandomtimer", "poissonrandomtimer", "jsr223timer", "jsr223preprocessor", "userparameters",
            "regexextractor", "xpathextractor", "jsonpostprocessor", "jsonpathextractor", "boundaryextractor",
            "jsr223postprocessor", "csvdatasetconfig", "csvdataset", "headermanager", "cookiemanager",
            "cachemanager", "httpdefaults", "counterconfig", "authmanager", "arguments", "viewresultstree",
            "summaryreport", "aggregatereport", "backendlistener", "summariser", "resultsaver", "jsr223listener");

    /** Entry whose model class is not a {@link TestElement}; addElement can never create it. */
    private static final Set<String> KNOWN_NON_TEST_ELEMENT_ENTRIES = Set.of("sampleresultsaveconfiguration");

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    private static Stream<String> elementClassMapKeys() {
        return JMeterElementManager.getElementClassMap().keySet().stream().sorted();
    }

    private static Stream<String> coreEntries() {
        return CORE_ENTRIES.stream().sorted();
    }

    /** Resolves a class, or returns null when it is not loadable on this classpath. */
    private static Class<?> tryLoad(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- ELEMENT_CLASS_MAP

    @ParameterizedTest(name = "{0}")
    @MethodSource("elementClassMapKeys")
    void everyElementClassMapKeyIsNormalisedAndSupported(String key) {
        assertEquals(key, JMeterElementManager.normalizeElementType(key), "keys must already be normalised");
        assertTrue(JMeterElementManager.isElementTypeSupported(key));
        assertTrue(JMeterElementManager.isElementTypeSupported(key.toUpperCase()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("elementClassMapKeys")
    void everyResolvableElementClassMapEntryIsATestElementWithAGuiComponent(String key) {
        JMeterElementManager.ElementClassInfo info = JMeterElementManager.getElementClassMap().get(key);
        assertTrue(info.modelClassName.startsWith("org.apache.jmeter."), key + ": " + info.modelClassName);
        assertTrue(info.guiClassName.startsWith("org.apache.jmeter."), key + ": " + info.guiClassName);

        Class<?> modelClass = tryLoad(info.modelClassName);
        if (modelClass != null) {
            if (KNOWN_NON_TEST_ELEMENT_ENTRIES.contains(key)) {
                assertFalse(TestElement.class.isAssignableFrom(modelClass));
            } else {
                assertTrue(TestElement.class.isAssignableFrom(modelClass),
                        key + ": " + info.modelClassName + " is not a TestElement");
                try {
                    modelClass.getDeclaredConstructor();
                } catch (NoSuchMethodException e) {
                    fail(key + ": " + info.modelClassName + " has no no-arg constructor");
                }
            }
        }

        Class<?> guiClass = tryLoad(info.guiClassName);
        if (guiClass != null && !KNOWN_NON_TEST_ELEMENT_ENTRIES.contains(key)) {
            assertTrue(JMeterGUIComponent.class.isAssignableFrom(guiClass),
                    key + ": " + info.guiClassName + " is not a JMeterGUIComponent");
            assertSame(guiClass, JMeterElementManager.getJMeterGuiClass(key));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coreEntries")
    void coreElementClassMapEntriesResolveOnTheClasspath(String key) {
        JMeterElementManager.ElementClassInfo info = JMeterElementManager.getElementClassMap().get(key);
        assertNotNull(info, key + " is missing from ELEMENT_CLASS_MAP");
        assertNotNull(tryLoad(info.modelClassName), key + ": model class " + info.modelClassName + " not found");
        assertNotNull(tryLoad(info.guiClassName), key + ": GUI class " + info.guiClassName + " not found");
        assertNotNull(JMeterElementManager.getJMeterGuiClass(key));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("elementClassMapKeys")
    void everyElementClassMapKeyHasAReadableDefaultName(String key) {
        String name = JMeterElementManager.getDefaultNameForElement(key);
        assertNotNull(name);
        assertFalse(name.isBlank());
        assertTrue(Character.isUpperCase(name.charAt(0)), key + " -> " + name);
        assertEquals(name, JMeterElementManager.getDefaultNameForElement(key.toUpperCase()));
    }

    // ---------------------------------------------------------------- normalizeElementType

    @ParameterizedTest
    @ValueSource(strings = {"httpsampler", "HTTP Sampler", "http-sampler", "Http   Sampler", " HTTP\tSAMPLER "})
    void normalizeElementTypeLowercasesAndStripsWhitespaceAndHyphens(String input) {
        assertEquals("httpsampler", JMeterElementManager.normalizeElementType(input));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void normalizeElementTypeOfNullOrBlankIsEmpty(String input) {
        assertEquals("", JMeterElementManager.normalizeElementType(input));
    }

    @Test
    void normalizeElementTypeKeepsUnderscoresAndDigits() {
        assertEquals("jsr223_sampler", JMeterElementManager.normalizeElementType("JSR223_Sampler"));
    }

    // ---------------------------------------------------------------- getDefaultNameForElement

    @Test
    void defaultNamesForWellKnownTypes() {
        assertEquals("HTTP Request", JMeterElementManager.getDefaultNameForElement("httpsampler"));
        assertEquals("HTTP Request", JMeterElementManager.getDefaultNameForElement("httptestsample"));
        assertEquals("Thread Group", JMeterElementManager.getDefaultNameForElement("Thread Group"));
        assertEquals("Response Assertion", JMeterElementManager.getDefaultNameForElement("responseassert"));
        assertEquals("Regular Expression Extractor",
                JMeterElementManager.getDefaultNameForElement("regex-extractor"));
        assertEquals("JSR223 PostProcessor", JMeterElementManager.getDefaultNameForElement("JSR223 PostProcessor"));
    }

    @Test
    void defaultNameFallsBackToCapitalisedTypeForUnknownTypes() {
        assertEquals("Cookiemanager", JMeterElementManager.getDefaultNameForElement("cookiemanager"));
        assertEquals("Something", JMeterElementManager.getDefaultNameForElement("Some Thing"));
    }

    @Test
    void defaultNameForNullIsNewElement() {
        assertEquals("New Element", JMeterElementManager.getDefaultNameForElement(null));
    }

    // ---------------------------------------------------------------- test plan readiness

    @Test
    void isTestPlanReadyReportsMissingGui() {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(null);

            JMeterElementManager.TestPlanStatus status = JMeterElementManager.isTestPlanReady();

            assertFalse(status.isReady());
            assertEquals("JMeter GUI is not available", status.getErrorMessage());
        }
    }

    @Test
    void isTestPlanReadyReportsMissingTreeModelOrRoot() {
        GuiPackage guiPackage = mock(GuiPackage.class);
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(guiPackage);

            when(guiPackage.getTreeModel()).thenReturn(null);
            JMeterElementManager.TestPlanStatus noModel = JMeterElementManager.isTestPlanReady();
            assertFalse(noModel.isReady());
            assertEquals("No test plan is currently open", noModel.getErrorMessage());

            JMeterTreeModel model = mock(JMeterTreeModel.class);
            when(model.getRoot()).thenReturn(null);
            when(guiPackage.getTreeModel()).thenReturn(model);
            JMeterElementManager.TestPlanStatus noRoot = JMeterElementManager.isTestPlanReady();
            assertFalse(noRoot.isReady());
            assertEquals("No test plan is currently open", noRoot.getErrorMessage());
        }
    }

    @Test
    void isTestPlanReadyWhenTreeHasRoot() {
        GuiPackage guiPackage = mock(GuiPackage.class);
        JMeterTreeModel model = mock(JMeterTreeModel.class);
        when(model.getRoot()).thenReturn(new JMeterTreeNode(new TestPlan(), null));
        when(guiPackage.getTreeModel()).thenReturn(model);
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(guiPackage);

            JMeterElementManager.TestPlanStatus status = JMeterElementManager.isTestPlanReady();

            assertTrue(status.isReady());
            assertEquals(null, status.getErrorMessage());
        }
    }

    @Test
    void ensureTestPlanExistsReturnsFalseWithoutGui() {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(null);
            assertFalse(JMeterElementManager.ensureTestPlanExists());
        }
    }

    @Test
    void ensureTestPlanExistsLeavesExistingPlanAlone() {
        GuiPackage guiPackage = mock(GuiPackage.class);
        JMeterTreeModel model = mock(JMeterTreeModel.class);
        when(model.getRoot()).thenReturn(new JMeterTreeNode(new TestPlan(), null));
        when(guiPackage.getTreeModel()).thenReturn(model);
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(guiPackage);

            assertTrue(JMeterElementManager.ensureTestPlanExists());
            verify(model, never()).setRoot(any());
        }
    }

    @Test
    void ensureTestPlanExistsCreatesRootTestPlanWhenTreeIsEmpty() {
        GuiPackage guiPackage = mock(GuiPackage.class);
        JMeterTreeModel model = mock(JMeterTreeModel.class);
        when(model.getRoot()).thenReturn(null);
        when(guiPackage.getTreeModel()).thenReturn(model);
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(guiPackage);

            assertTrue(JMeterElementManager.ensureTestPlanExists());

            ArgumentCaptor<javax.swing.tree.TreeNode> root = ArgumentCaptor.forClass(javax.swing.tree.TreeNode.class);
            verify(model).setRoot(root.capture());
            JMeterTreeNode rootNode = assertInstanceOf(JMeterTreeNode.class, root.getValue());
            TestElement testPlan = rootNode.getTestElement();
            assertInstanceOf(TestPlan.class, testPlan);
            assertEquals("Test Plan", testPlan.getName());
            assertEquals(TestPlan.class.getName(), testPlan.getPropertyAsString(TestElement.TEST_CLASS));
            assertEquals(TestPlanGui.class.getName(), testPlan.getPropertyAsString(TestElement.GUI_CLASS));
        }
    }

    // ---------------------------------------------------------------- addElement

    private static final class Gui {
        final GuiPackage guiPackage = mock(GuiPackage.class);
        final JMeterTreeListener treeListener = mock(JMeterTreeListener.class);
        final JMeterTreeModel treeModel = mock(JMeterTreeModel.class);

        Gui(JMeterTreeNode currentNode) {
            when(guiPackage.getTreeListener()).thenReturn(treeListener);
            when(guiPackage.getTreeModel()).thenReturn(treeModel);
            when(treeListener.getCurrentNode()).thenReturn(currentNode);
        }

        TestElement addedElement() throws Exception {
            ArgumentCaptor<TestElement> element = ArgumentCaptor.forClass(TestElement.class);
            verify(treeModel).addComponent(element.capture(), any());
            return element.getValue();
        }
    }

    private static JMeterTreeNode threadGroupNode() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setProperty(TestElement.GUI_CLASS, ThreadGroupGui.class.getName());
        return new JMeterTreeNode(threadGroup, null);
    }

    private static JMeterTreeNode samplerNode() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setProperty(TestElement.GUI_CLASS, HttpTestSampleGui.class.getName());
        return new JMeterTreeNode(sampler, null);
    }

    @Test
    void addElementReturnsFalseWithoutGuiPackage() {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(null);
            assertFalse(JMeterElementManager.addElement("httpsampler", "Login"));
        }
    }

    @Test
    void addElementReturnsFalseWhenNothingIsSelected() throws Exception {
        Gui g = new Gui(null);
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(g.guiPackage);

            assertFalse(JMeterElementManager.addElement("httpsampler", "Login"));
            verify(g.treeModel, never()).addComponent(any(), any());
        }
    }

    @Test
    void addElementReturnsFalseForUnknownType() throws Exception {
        Gui g = new Gui(threadGroupNode());
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(g.guiPackage);

            assertFalse(JMeterElementManager.addElement("teleporter", "Beam me up"));
            verify(g.treeModel, never()).addComponent(any(), any());
        }
    }

    @Test
    void addElementReturnsFalseWhenPlacementIsIncompatible() throws Exception {
        Gui g = new Gui(samplerNode());
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(g.guiPackage);

            assertFalse(JMeterElementManager.addElement("threadgroup", "Users"));
            assertFalse(JMeterElementManager.addElement("httpsampler", "Nested"));
            verify(g.treeModel, never()).addComponent(any(), any());
        }
    }

    @Test
    void addElementAddsNamedHttpSamplerWithDefaultsUnderThreadGroup() throws Exception {
        JMeterTreeNode threadGroup = threadGroupNode();
        Gui g = new Gui(threadGroup);
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(g.guiPackage);

            assertTrue(JMeterElementManager.addElement("HTTP Sampler", "Login"));

            HTTPSamplerProxy sampler = assertInstanceOf(HTTPSamplerProxy.class, g.addedElement());
            verify(g.treeModel).addComponent(sampler, threadGroup);
            verify(g.treeModel).nodeStructureChanged(threadGroup);
            assertEquals("Login", sampler.getName());
            assertEquals("GET", sampler.getMethod());
            assertTrue(sampler.getFollowRedirects());
            assertTrue(sampler.getUseKeepAlive());
            assertEquals(HTTPSamplerProxy.class.getName(), sampler.getPropertyAsString(TestElement.TEST_CLASS));
            assertEquals(HttpTestSampleGui.class.getName(), sampler.getPropertyAsString(TestElement.GUI_CLASS));
        }
    }

    @Test
    void addElementUsesDefaultNameWhenNoneGiven() throws Exception {
        Gui g = new Gui(threadGroupNode());
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(g.guiPackage);

            assertTrue(JMeterElementManager.addElement("httpsampler", null));

            assertEquals("HTTP Request", g.addedElement().getName());
        }
    }

    @Test
    void addElementUsesDefaultNameWhenNameIsEmpty() throws Exception {
        Gui g = new Gui(threadGroupNode());
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(g.guiPackage);

            assertTrue(JMeterElementManager.addElement("constanttimer", ""));

            assertEquals("Constant Timer", g.addedElement().getName());
        }
    }

    @Test
    void addElementInitialisesThreadGroupDefaultsUnderTestPlan() throws Exception {
        TestPlan plan = new TestPlan();
        plan.setProperty(TestElement.GUI_CLASS, TestPlanGui.class.getName());
        Gui g = new Gui(new JMeterTreeNode(plan, null));
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(g.guiPackage);

            assertTrue(JMeterElementManager.addElement("Thread Group", "Users"));

            ThreadGroup threadGroup = assertInstanceOf(ThreadGroup.class, g.addedElement());
            assertEquals("Users", threadGroup.getName());
            assertEquals(1, threadGroup.getNumThreads());
            assertEquals(1, threadGroup.getRampUp());
            assertNotNull(threadGroup.getSamplerController());
        }
    }

    @Test
    void addElementReturnsFalseWhenTreeModelRejectsElement() throws Exception {
        Gui g = new Gui(threadGroupNode());
        when(g.treeModel.addComponent(any(), any())).thenThrow(new IllegalStateException("boom"));
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(g.guiPackage);

            assertFalse(JMeterElementManager.addElement("httpsampler", "Login"));
        }
    }
}
