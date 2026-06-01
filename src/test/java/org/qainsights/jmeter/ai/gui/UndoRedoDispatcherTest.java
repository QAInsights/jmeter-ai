package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.service.AiService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UndoRedoDispatcherTest {

    @Mock
    private CommandCallback callback;

    @Mock
    private AiService aiService;

    @Test
    void testUndoLastRename_NoModelSelected() throws Exception {
        when(callback.getSelectedModel()).thenReturn(null);

        UndoRedoDispatcher dispatcher = new UndoRedoDispatcher(callback);
        dispatcher.undoLastRename();

        // Verify that appendMessageToChat is eventually called with the warning message
        verify(callback, timeout(2000)).appendMessageToChat("Please select a model first.");
    }

    @Test
    void testUndoLastRename_WithModelSelected() throws Exception {
        when(callback.getSelectedModel()).thenReturn("openai:gpt-4o");
        when(callback.resolveAiService("openai:gpt-4o")).thenReturn(aiService);

        UndoRedoDispatcher dispatcher = new UndoRedoDispatcher(callback);
        dispatcher.undoLastRename();

        // Since lastRenameOperation is empty initially, it should return "Nothing to undo."
        verify(callback, timeout(2000)).appendMessageToChat("Nothing to undo.");
    }

    @Test
    void testRedoLastUndo() throws Exception {
        when(callback.getSelectedModel()).thenReturn("openai:gpt-4o");
        when(callback.resolveAiService("openai:gpt-4o")).thenReturn(aiService);

        UndoRedoDispatcher dispatcher = new UndoRedoDispatcher(callback);
        dispatcher.redoLastUndo();

        // Since lastUndoneOperation is empty initially, it should return "Nothing to redo."
        verify(callback, timeout(2000)).appendMessageToChat("Nothing to redo.");
    }

    @Test
    void testUndoLastWrap() throws Exception {
        UndoRedoDispatcher dispatcher = new UndoRedoDispatcher(callback);
        dispatcher.undoLastWrap();

        // Since WrapUndoRedoHandler has no wraps to undo, it should return "Nothing to undo."
        verify(callback, timeout(2000)).appendMessageToChat("Nothing to undo.");
    }
}
