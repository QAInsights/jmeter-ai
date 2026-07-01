package org.qainsights.jmeter.ai.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ToolSpec}. */
class ToolSpecTest {

    private ToolParameter param(String name, boolean required) {
        return ToolParameter.builder(name, ParamType.STRING).required(required).build();
    }

    @Test
    void builder_buildsNameAndDescription() {
        ToolSpec spec = ToolSpec.builder("add_sampler").description("adds a sampler").build();
        assertEquals("add_sampler", spec.getName());
        assertEquals("adds a sampler", spec.getDescription());
        assertTrue(spec.getParameters().isEmpty());
        assertTrue(spec.getPreconditions().isEmpty());
    }

    @Test
    void getRequiredParameters_filtersOptionalOnes() {
        ToolSpec spec = ToolSpec.builder("t")
                .addParameter(param("a", true))
                .addParameter(param("b", false))
                .addParameter(param("c", true))
                .build();
        assertEquals(3, spec.getParameters().size());
        assertEquals(2, spec.getRequiredParameters().size());
    }

    @Test
    void addPrecondition_ignoresBlankEntries() {
        ToolSpec spec = ToolSpec.builder("t")
                .addPrecondition("must have parent")
                .addPrecondition("  ")
                .addPrecondition(null)
                .build();
        assertEquals(1, spec.getPreconditions().size());
    }

    @Test
    void parameters_listIsUnmodifiable() {
        ToolSpec spec = ToolSpec.builder("t").addParameter(param("a", true)).build();
        assertThrows(UnsupportedOperationException.class,
                () -> spec.getParameters().add(param("x", false)));
    }

    @Test
    void builder_blankNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> ToolSpec.builder(""));
    }

    @Test
    void addParameter_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> ToolSpec.builder("t").addParameter(null));
    }
}
