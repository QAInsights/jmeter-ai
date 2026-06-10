package org.qainsights.jmeter.ai.gui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.utils.AiConfig;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.event.PopupMenuEvent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class JSR223ContextMenuTest {

    @Test
    void addContextMenuUsesRsyntaxPopupMenu() {
        RSyntaxTextArea textArea = new RSyntaxTextArea();
        JPopupMenu existingPopup = textArea.getPopupMenu();
        AiService aiService = mock(AiService.class);

        try (MockedStatic<AiConfig> aiConfig = mockStatic(AiConfig.class)) {
            aiConfig.when(() -> AiConfig.getProperty("jmeter.ai.refactoring.enabled", "true")).thenReturn("true");

            JSR223ContextMenu.addContextMenu(textArea, aiService);

            assertSame(existingPopup, textArea.getPopupMenu());
            assertTrue(Arrays.stream(textArea.getPopupMenu().getComponents())
                    .filter(JMenuItem.class::isInstance)
                    .map(JMenuItem.class::cast)
                    .anyMatch(item -> "Refactor Code".equals(item.getText())));
            assertTrue(Arrays.stream(textArea.getPopupMenu().getComponents())
                    .filter(JMenuItem.class::isInstance)
                    .map(JMenuItem.class::cast)
                    .anyMatch(item -> "Functions Dialog".equals(item.getText())));
            assertTrue(Boolean.TRUE.equals(textArea.getClientProperty("contextMenuAdded")));
        }
    }

    @Test
    void addContextMenuLeavesAiActionsDisabledWithoutService() {
        RSyntaxTextArea textArea = new RSyntaxTextArea();
        textArea.setText("vars.put('key', 'value')");
        textArea.select(0, textArea.getText().length());

        try (MockedStatic<AiConfig> aiConfig = mockStatic(AiConfig.class)) {
            aiConfig.when(() -> AiConfig.getProperty("jmeter.ai.refactoring.enabled", "true")).thenReturn("true");

            JSR223ContextMenu.addContextMenu(textArea, null);
            JPopupMenu popupMenu = textArea.getPopupMenu();
            for (javax.swing.event.PopupMenuListener listener : popupMenu.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeVisible(new PopupMenuEvent(popupMenu));
            }

            JMenuItem cutItem = Arrays.stream(popupMenu.getComponents())
                    .filter(JMenuItem.class::isInstance)
                    .map(JMenuItem.class::cast)
                    .filter(item -> "Cut".equals(item.getText()))
                    .findFirst()
                    .orElse(null);
            JMenuItem refactorItem = Arrays.stream(popupMenu.getComponents())
                    .filter(JMenuItem.class::isInstance)
                    .map(JMenuItem.class::cast)
                    .filter(item -> "Refactor Code".equals(item.getText()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(cutItem);
            assertNotNull(refactorItem);
            assertTrue(cutItem.isEnabled());
            assertFalse(refactorItem.isEnabled());
        }
    }

    @Test
    void addContextMenuEnablesAiActionsWithSelectionAndService() {
        RSyntaxTextArea textArea = new RSyntaxTextArea();
        textArea.setText("vars.put('key', 'value')");
        textArea.select(0, textArea.getText().length());

        try (MockedStatic<AiConfig> aiConfig = mockStatic(AiConfig.class)) {
            aiConfig.when(() -> AiConfig.getProperty("jmeter.ai.refactoring.enabled", "true")).thenReturn("true");

            JSR223ContextMenu.addContextMenu(textArea, mock(AiService.class));
            JPopupMenu popupMenu = textArea.getPopupMenu();
            for (javax.swing.event.PopupMenuListener listener : popupMenu.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeVisible(new PopupMenuEvent(popupMenu));
            }

            JMenuItem refactorItem = Arrays.stream(popupMenu.getComponents())
                    .filter(JMenuItem.class::isInstance)
                    .map(JMenuItem.class::cast)
                    .filter(item -> "Refactor Code".equals(item.getText()))
                    .findFirst()
                    .orElse(null);
            JMenuItem tryCatchItem = Arrays.stream(popupMenu.getComponents())
                    .filter(JMenuItem.class::isInstance)
                    .map(JMenuItem.class::cast)
                    .filter(item -> "Try, Catch, Finally".equals(item.getText()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(refactorItem);
            assertNotNull(tryCatchItem);
            assertTrue(refactorItem.isEnabled());
            assertTrue(tryCatchItem.isEnabled());
        }
    }

    @Test
    void addContextMenuDoesNotInstallCustomItemsWhenRefactoringDisabled() {
        RSyntaxTextArea textArea = new RSyntaxTextArea();

        try (MockedStatic<AiConfig> aiConfig = mockStatic(AiConfig.class)) {
            aiConfig.when(() -> AiConfig.getProperty("jmeter.ai.refactoring.enabled", "true")).thenReturn("false");

            JSR223ContextMenu.addContextMenu(textArea, mock(AiService.class));

            assertFalse(Arrays.stream(textArea.getPopupMenu().getComponents())
                    .filter(JMenuItem.class::isInstance)
                    .map(JMenuItem.class::cast)
                    .anyMatch(item -> "Refactor Code".equals(item.getText())));
            assertFalse(Boolean.TRUE.equals(textArea.getClientProperty("contextMenuAdded")));
        }
    }
}
