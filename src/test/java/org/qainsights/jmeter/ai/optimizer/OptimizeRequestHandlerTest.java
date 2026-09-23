package org.qainsights.jmeter.ai.optimizer;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.timers.ConstantTimer;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.service.AiService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OptimizeRequestHandler}: the @optimize command matching
 * and the per-element prompt that is sent to the {@link AiService}.
 */
@ExtendWith(MockitoExtension.class)
class OptimizeRequestHandlerTest {

    @Mock
    private AiService aiService;

    @Mock
    private GuiPackage guiPackage;

    @Mock
    private JMeterTreeListener treeListener;

    @Mock
    private JMeterTreeNode selectedNode;

    /** Element whose simple class name matches none of the type-specific branches. */
    private static class PlainElement extends AbstractTestElement {
    }

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    // ---- processOptimizeTestPlanRequest: matching --------------------------------

    @Test
    void nullMessageReturnsNull() {
        assertNull(OptimizeRequestHandler.processOptimizeTestPlanRequest(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hello", "please add a thread group", "optimizer settings", "improvement plan", ""})
    void nonMatchingMessagesReturnNullWithoutTouchingGui(String message) {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            assertNull(OptimizeRequestHandler.processOptimizeTestPlanRequest(message));
            gui.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"optimize", "  OPTIMIZE ", "@optimize", "please improve this sampler",
            "can you Enhance the thread group?", "Optimize my HTTP request"})
    void matchingMessagesReportGuiUnavailableWhenNoGui(String message) {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(null);

            String result = OptimizeRequestHandler.processOptimizeTestPlanRequest(message);

            assertEquals("I couldn't optimize the element because jmeter gui is not available. "
                    + "Please make sure you have a test plan open.", result);
        }
    }

    @Test
    void matchingMessageWithoutOpenTestPlanReportsNoTestPlan() {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(guiPackage);
            when(guiPackage.getTreeModel()).thenReturn(null);

            String result = OptimizeRequestHandler.processOptimizeTestPlanRequest("optimize");

            assertEquals("I couldn't optimize the element because no test plan is currently open. "
                    + "Please make sure you have a test plan open.", result);
        }
    }

    @Test
    void matchingMessageWithOpenTestPlanPointsToOptimizeCommand() {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(guiPackage);
            when(guiPackage.getTreeModel()).thenReturn(new JMeterTreeModel());

            String result = OptimizeRequestHandler.processOptimizeTestPlanRequest("improve this");

            assertEquals("Please use the @optimize command in the chat panel to get optimization suggestions.",
                    result);
        }
    }

    // ---- analyzeAndOptimizeSelectedElement: guards --------------------------------

    @Test
    void analyzeWithoutGuiReturnsGuiUnavailable() {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(null);

            assertEquals("I couldn't optimize because the JMeter GUI is not available.",
                    OptimizeRequestHandler.analyzeAndOptimizeSelectedElement(aiService));
            verifyNoInteractions(aiService);
        }
    }

    @Test
    void analyzeWithoutSelectedNodeReturnsNoSelection() {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(guiPackage);
            when(guiPackage.getTreeListener()).thenReturn(treeListener);
            when(treeListener.getCurrentNode()).thenReturn(null);

            String result = OptimizeRequestHandler.analyzeAndOptimizeSelectedElement(aiService);

            assertTrue(result.startsWith("I couldn't optimize because no element is currently selected"), result);
            verifyNoInteractions(aiService);
        }
    }

    @Test
    void analyzeWithNodeLackingTestElementReturnsInvalid() {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            selectNode(gui, null);

            assertEquals("I couldn't optimize because the selected element is not valid.",
                    OptimizeRequestHandler.analyzeAndOptimizeSelectedElement(aiService));
            verifyNoInteractions(aiService);
        }
    }

    @Test
    void analyzeWithNullServiceReturnsServiceUnavailable() {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            selectNode(gui, named(new ThreadGroup(), "Users"));

            assertEquals("I couldn't optimize because the AI service is not available. Please try again later.",
                    OptimizeRequestHandler.analyzeAndOptimizeSelectedElement(null));
        }
    }

    @Test
    void analyzeCatchesServiceExceptionAndReportsIt() {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            selectNode(gui, named(new ThreadGroup(), "Users"));
            when(aiService.generateResponse(anyList())).thenThrow(new RuntimeException("boom"));

            String result = OptimizeRequestHandler.analyzeAndOptimizeSelectedElement(aiService);

            assertEquals("I encountered an error while analyzing the selected element: boom", result);
        }
    }

    // ---- analyzeAndOptimizeSelectedElement: prompt building ---------------------

    @Test
    void httpSamplerPromptIncludesPropertiesAndHttpGuidance() {
        HTTPSamplerProxy sampler = named(new HTTPSamplerProxy(), "Login Request");
        sampler.setDomain("example.com");
        sampler.setPath("/login");

        String prompt = promptFor(sampler);

        assertTrue(prompt.startsWith("As a JMeter expert, analyze this JMeter element"), prompt);
        assertTrue(prompt.contains("Element Type: HTTPSamplerProxy\n"), prompt);
        assertTrue(prompt.contains("Element Name: Login Request\n"), prompt);
        assertTrue(prompt.contains("- HTTPSampler.domain: example.com\n"), prompt);
        assertTrue(prompt.contains("- HTTPSampler.path: /login\n"), prompt);
        assertFalse(prompt.contains("- TestElement."), "internal TestElement.* properties must be skipped");
        assertTrue(prompt.contains("recommendations for this HTTPSamplerProxy with focus on:\n"), prompt);
        assertTrue(prompt.contains("- Use of connection pooling\n"), prompt);
        assertFalse(prompt.contains("Thread count and ramp-up settings"), prompt);
        assertTrue(prompt.endsWith("Provide 3-5 specific, actionable recommendations to optimize this element."),
                prompt);
    }

    @Test
    void threadGroupPromptUsesThreadGroupGuidance() {
        String prompt = promptFor(named(new ThreadGroup(), "Users"));
        assertTrue(prompt.contains("Element Type: ThreadGroup\n"), prompt);
        assertTrue(prompt.contains("- Thread count and ramp-up settings\n"), prompt);
        assertFalse(prompt.contains("Use of connection pooling"), prompt);
    }

    @Test
    void timerPromptUsesTimerGuidance() {
        String prompt = promptFor(named(new ConstantTimer(), "Think Time"));
        assertTrue(prompt.contains("Element Type: ConstantTimer\n"), prompt);
        assertTrue(prompt.contains("- Realistic user behavior simulation\n"), prompt);
    }

    @Test
    void assertionPromptUsesAssertionGuidance() {
        String prompt = promptFor(named(new ResponseAssertion(), "Check 200"));
        assertTrue(prompt.contains("Element Type: ResponseAssertion\n"), prompt);
        assertTrue(prompt.contains("- Pattern matching efficiency\n"), prompt);
    }

    @Test
    void extractorPromptUsesExtractorGuidance() {
        String prompt = promptFor(named(new RegexExtractor(), "Token"));
        assertTrue(prompt.contains("Element Type: RegexExtractor\n"), prompt);
        assertTrue(prompt.contains("- Regular expression optimization\n"), prompt);
    }

    @Test
    void configElementPromptUsesConfigGuidance() {
        String prompt = promptFor(named(new ConfigTestElement(), "Defaults"));
        assertTrue(prompt.contains("Element Type: ConfigTestElement\n"), prompt);
        assertTrue(prompt.contains("- Reusability across test plan\n"), prompt);
    }

    @Test
    void controllerPromptUsesControllerGuidance() {
        String prompt = promptFor(named(new LoopController(), "Loop"));
        assertTrue(prompt.contains("Element Type: LoopController\n"), prompt);
        assertTrue(prompt.contains("- Nesting level considerations\n"), prompt);
    }

    @Test
    void unknownElementPromptUsesGenericGuidance() {
        String prompt = promptFor(named(new PlainElement(), "Mystery"));
        assertTrue(prompt.contains("Element Type: PlainElement\n"), prompt);
        assertTrue(prompt.contains("- Integration with other elements\n"), prompt);
        assertFalse(prompt.contains("Use of connection pooling"), prompt);
        assertFalse(prompt.contains("Thread count and ramp-up settings"), prompt);
    }

    @Test
    void successfulAnalysisWrapsRecommendationsInReport() {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            selectNode(gui, named(new ThreadGroup(), "Users"));
            when(aiService.generateResponse(anyList())).thenReturn("1. Lower ramp-up");

            String result = OptimizeRequestHandler.analyzeAndOptimizeSelectedElement(aiService);

            assertEquals("# Optimization Recommendations for Users (ThreadGroup)\n\n1. Lower ramp-up", result);
        }
    }

    // ---- helpers ----------------------------------------------------------------

    private static <T extends TestElement> T named(T element, String name) {
        element.setName(name);
        return element;
    }

    private void selectNode(MockedStatic<GuiPackage> gui, TestElement element) {
        gui.when(GuiPackage::getInstance).thenReturn(guiPackage);
        when(guiPackage.getTreeListener()).thenReturn(treeListener);
        when(treeListener.getCurrentNode()).thenReturn(selectedNode);
        when(selectedNode.getTestElement()).thenReturn(element);
    }

    /** Runs the analysis for {@code element} and returns the single prompt handed to the AI service. */
    @SuppressWarnings("unchecked")
    private String promptFor(TestElement element) {
        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            selectNode(gui, element);
            when(aiService.generateResponse(anyList())).thenReturn("ok");

            OptimizeRequestHandler.analyzeAndOptimizeSelectedElement(aiService);

            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(aiService).generateResponse(captor.capture());
            assertEquals(1, captor.getValue().size(), "prompt must be sent as a single-message conversation");
            return captor.getValue().get(0);
        }
    }
}
