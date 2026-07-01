package org.qainsights.jmeter.ai.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ToolParameter}. */
class ToolParameterTest {

    @Test
    void builder_appliesDefaults() {
        ToolParameter p = ToolParameter.builder("depth", ParamType.INTEGER).build();
        assertEquals("depth", p.getName());
        assertEquals(ParamType.INTEGER, p.getType());
        assertEquals("", p.getDescription());
        assertFalse(p.isRequired());
        assertTrue(p.getEnumValues().isEmpty());
    }

    @Test
    void builder_setsAllFields() {
        ToolParameter p = ToolParameter.builder("type", ParamType.STRING)
                .description("element type")
                .required(true)
                .enumValues(Arrays.asList("a", "b"))
                .build();
        assertEquals("element type", p.getDescription());
        assertTrue(p.isRequired());
        assertEquals(Arrays.asList("a", "b"), p.getEnumValues());
    }

    @Test
    void enumValues_areDefensivelyCopied() {
        List<String> source = new java.util.ArrayList<>(Arrays.asList("a"));
        ToolParameter p = ToolParameter.builder("t", ParamType.STRING).enumValues(source).build();
        source.add("mutated");
        assertEquals(1, p.getEnumValues().size());
    }

    @Test
    void enumValues_returnedListIsUnmodifiable() {
        ToolParameter p = ToolParameter.builder("t", ParamType.STRING)
                .enumValues(Arrays.asList("a")).build();
        assertThrows(UnsupportedOperationException.class, () -> p.getEnumValues().add("x"));
    }

    @Test
    void builder_blankNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> ToolParameter.builder(" ", ParamType.STRING));
    }

    @Test
    void builder_nullTypeThrows() {
        assertThrows(IllegalArgumentException.class, () -> ToolParameter.builder("n", null));
    }
}
