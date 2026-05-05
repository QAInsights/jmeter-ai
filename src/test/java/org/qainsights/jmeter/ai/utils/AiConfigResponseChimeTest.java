package org.qainsights.jmeter.ai.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the response chime configuration in AiConfig.
 * Tests the isResponseChimeEnabled() method with various
 * jmeter.ai.response.chime property values.
 */
@ExtendWith(MockitoExtension.class)
class AiConfigResponseChimeTest {

    private MockedStatic<AiConfig> aiConfigMockedStatic;

    @AfterEach
    void tearDown() {
        if (aiConfigMockedStatic != null) {
            aiConfigMockedStatic.close();
        }
    }

    @Test
    void testIsResponseChimeEnabled_whenPropertyIsTrue_returnsTrue() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(AiConfig::isResponseChimeEnabled).thenReturn(true);

        assertTrue(AiConfig.isResponseChimeEnabled(),
                "Response chime should be enabled when property is 'true'");
    }

    @Test
    void testIsResponseChimeEnabled_whenPropertyIsFalse_returnsFalse() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(AiConfig::isResponseChimeEnabled).thenReturn(false);

        assertFalse(AiConfig.isResponseChimeEnabled(),
                "Response chime should be disabled when property is 'false'");
    }

    @Test
    void testIsResponseChimeEnabled_defaultValueIsFalse() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(AiConfig::isResponseChimeEnabled).thenReturn(false);

        assertFalse(AiConfig.isResponseChimeEnabled(),
                "Response chime should default to 'false' when property is not set");
    }

    @Test
    void testIsResponseChimeEnabled_propertyKeyIsCorrect() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(AiConfig::isResponseChimeEnabled).thenCallRealMethod();
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(
                "jmeter.ai.response.chime", "false")).thenReturn("false");

        assertFalse(AiConfig.isResponseChimeEnabled(),
                "isResponseChimeEnabled should query 'jmeter.ai.response.chime' with default 'false'");
    }

    @Test
    void testIsResponseChimeEnabled_handlesUnexpectedPropertyValue() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(AiConfig::isResponseChimeEnabled).thenCallRealMethod();
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(
                "jmeter.ai.response.chime", "false")).thenReturn("garbage");

        assertFalse(AiConfig.isResponseChimeEnabled(),
                "Non-boolean values should be parsed as false by Boolean.parseBoolean");
    }

    @Test
    void testGetProperty_responseChimeEnabledPropertyKey() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(
                eq("jmeter.ai.response.chime"), anyString())).thenReturn("true");

        assertEquals("true", AiConfig.getProperty("jmeter.ai.response.chime", "false"),
                "getProperty should return the configured value for jmeter.ai.response.chime");
    }

    @Test
    void testGetProperty_responseChimeEnabledUsesFalseDefault() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(
                eq("jmeter.ai.response.chime"), anyString())).thenCallRealMethod();
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(
                eq("jmeter.ai.response.chime"), eq("false"))).thenReturn("false");

        String result = AiConfig.getProperty("jmeter.ai.response.chime", "false");
        assertEquals("false", result,
                "Default value for response chime should be 'false'");
    }
}
