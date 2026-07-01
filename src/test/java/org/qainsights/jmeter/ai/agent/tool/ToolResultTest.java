package org.qainsights.jmeter.ai.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ToolResult}. */
class ToolResultTest {

    @Test
    void ok_marksSuccessAndStoresData() {
        ToolResult r = ToolResult.ok("payload");
        assertTrue(r.isSuccess());
        assertEquals("payload", r.getData());
        assertNull(r.getErrorCode());
        assertNull(r.getMessage());
    }

    @Test
    void ok_withNullData_normalisesToEmptyString() {
        assertEquals("", ToolResult.ok(null).getData());
    }

    @Test
    void error_marksFailureAndStoresCodeAndMessage() {
        ToolResult r = ToolResult.error("bad_input", "explain");
        assertFalse(r.isSuccess());
        assertEquals("bad_input", r.getErrorCode());
        assertEquals("explain", r.getMessage());
        assertNull(r.getData());
    }

    @Test
    void error_withNullMessage_normalisesToEmptyString() {
        assertEquals("", ToolResult.error("code", null).getMessage());
    }

    @Test
    void error_withBlankCode_throws() {
        assertThrows(IllegalArgumentException.class, () -> ToolResult.error("  ", "msg"));
    }

    @Test
    void equalsAndHashCode_areValueBased() {
        assertEquals(ToolResult.ok("x"), ToolResult.ok("x"));
        assertEquals(ToolResult.ok("x").hashCode(), ToolResult.ok("x").hashCode());
        assertNotEquals(ToolResult.ok("x"), ToolResult.ok("y"));
        assertNotEquals(ToolResult.ok("x"), ToolResult.error("c", "x"));
    }

    @Test
    void toString_reflectsState() {
        assertTrue(ToolResult.ok("d").toString().contains("ok"));
        assertTrue(ToolResult.error("c", "m").toString().contains("error"));
    }
}
