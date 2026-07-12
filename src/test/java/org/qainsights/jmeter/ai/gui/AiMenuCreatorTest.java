package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.jmeter.gui.plugin.MenuCreator.MENU_LOCATION;
import org.qainsights.jmeter.ai.utils.AiConfig;

import javax.swing.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class AiMenuCreatorTest {

    private MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeEach
    void setUp() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String defaultValue = invocation.getArgument(1);
            if (key.equals("jmeter.ai.service.type")) return "openai";
            if (key.equals("openai.api.key")) return "test-key";
            if (key.equals("openai.default.model")) return "gpt-4o";
            return defaultValue;
        });
    }

    @AfterEach
    void tearDown() {
        if (aiConfigMockedStatic != null) {
            aiConfigMockedStatic.close();
        }
    }

    @Test
    void testGetMenuItemsAtLocation_Run() {
        AiMenuCreator creator = new AiMenuCreator();
        JMenuItem[] items = creator.getMenuItemsAtLocation(MENU_LOCATION.RUN);

        assertNotNull(items);
        assertEquals(8, items.length);
        assertTrue(items[0] instanceof AiMenuItem);
        assertTrue(items[1] instanceof org.qainsights.jmeter.ai.correlation.CorrelationMenuItem);
        assertTrue(items[2] instanceof org.qainsights.jmeter.ai.claudecode.ClaudeCodeMenuItem);
        assertTrue(items[3] instanceof org.qainsights.jmeter.ai.agent.dev.AddElementDevMenuItem);
        assertTrue(items[4] instanceof org.qainsights.jmeter.ai.agent.dev.UpdateElementPropertyDevMenuItem);
        assertTrue(items[5] instanceof org.qainsights.jmeter.ai.agent.dev.DeleteElementDevMenuItem);
        assertTrue(items[6] instanceof org.qainsights.jmeter.ai.agent.dev.ToggleElementDevMenuItem);
        assertTrue(items[7] instanceof org.qainsights.jmeter.ai.agent.dev.MoveElementDevMenuItem);
    }

    @Test
    void testGetMenuItemsAtLocation_Other() {
        AiMenuCreator creator = new AiMenuCreator();
        JMenuItem[] items = creator.getMenuItemsAtLocation(MENU_LOCATION.EDIT);

        assertNotNull(items);
        assertEquals(0, items.length);
    }

    @Test
    void testGetTopLevelMenus() {
        AiMenuCreator creator = new AiMenuCreator();
        JMenu[] menus = creator.getTopLevelMenus();

        assertNotNull(menus);
        assertEquals(0, menus.length);
    }

    @Test
    void testLocaleChanged() {
        AiMenuCreator creator = new AiMenuCreator();
        assertFalse(creator.localeChanged(new JMenu()));
        
        // This should run without throwing exceptions
        assertDoesNotThrow(() -> creator.localeChanged());
    }
}
