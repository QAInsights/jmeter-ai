package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link CommandCallback}'s default methods: the attachment
 * marker resolution defaults to a pass-through for callbacks that have no
 * registry (the chat panel overrides it).
 */
class CommandCallbackTest {

    @Test
    void defaultResolveAttachmentMarkersPassesThrough() {
        CommandCallback cb = mock(CommandCallback.class, CALLS_REAL_METHODS);
        List<String> turns = List.of("User: a [file:f1]", "AI: b");
        assertSame(turns, cb.resolveAttachmentMarkers(turns));
    }
}
