package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.AiService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandDispatcherWorkloadAiTest {
    @Test
    void returnsNullWhenNoModelIsSelectedWithoutResolvingService() {
        assertNull(CommandDispatcher.workloadAiCall(null, model -> {
            throw new AssertionError("No model should be resolved");
        }));
    }

    @Test
    void returnsNullWhenTheSelectedModelHasNoService() {
        assertNull(CommandDispatcher.workloadAiCall("model", model -> null));
    }

    @Test
    void sendsOnePromptToTheResolvedService() {
        AiService service = mock(AiService.class);
        when(service.generateResponse(List.of("prompt"))).thenReturn("response");

        String response = CommandDispatcher.workloadAiCall("model", model -> service).apply("prompt");

        assertEquals("response", response);
        verify(service).generateResponse(List.of("prompt"));
    }
}
