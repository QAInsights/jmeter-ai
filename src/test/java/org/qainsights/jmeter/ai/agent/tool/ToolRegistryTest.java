package org.qainsights.jmeter.ai.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ToolRegistry}. */
class ToolRegistryTest {

    /** Minimal {@link Tool} that returns a fixed result. */
    private static Tool tool(String name) {
        ToolSpec spec = ToolSpec.builder(name).build();
        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return ToolResult.ok(name);
            }
        };
    }

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    @Test
    void register_thenGet_returnsSameTool() {
        Tool t = tool("get_tree_state");
        registry.register(t);
        assertSame(t, registry.get("get_tree_state"));
        assertTrue(registry.isRegistered("get_tree_state"));
        assertEquals(1, registry.size());
    }

    @Test
    void get_unknownName_returnsNull() {
        assertNull(registry.get("missing"));
        assertFalse(registry.isRegistered("missing"));
        assertFalse(registry.isRegistered(null));
    }

    @Test
    void register_duplicateName_throws() {
        registry.register(tool("dup"));
        assertThrows(IllegalStateException.class, () -> registry.register(tool("dup")));
    }

    @Test
    void register_nullTool_throws() {
        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    }

    @Test
    void getSpecs_preservesRegistrationOrder() {
        registry.register(tool("first"));
        registry.register(tool("second"));
        List<ToolSpec> specs = registry.getSpecs();
        assertEquals("first", specs.get(0).getName());
        assertEquals("second", specs.get(1).getName());
    }

    @Test
    void getAll_isUnmodifiable() {
        registry.register(tool("a"));
        assertThrows(UnsupportedOperationException.class, () -> registry.getAll().clear());
    }
}
