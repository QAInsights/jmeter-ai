package org.qainsights.jmeter.ai.agent;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AgentRunListenersTest {

    @Test
    void emptyBundleHasNoConsumersAndOrEmptySubstitutesIt() {
        AgentRunListeners empty = AgentRunListeners.empty();

        assertNull(empty.progress());
        assertNull(empty.toolCallStarted());
        assertNull(empty.reasoning());
        assertNull(empty.routingNotice());
        assertNull(empty.triageNotice());
        assertNotNull(AgentRunListeners.orEmpty(null));

        AgentRunListeners listeners = new AgentRunListeners(
                s -> { }, t -> { }, s -> { }, n -> { }, n -> { });
        assertSame(listeners, AgentRunListeners.orEmpty(listeners));
    }

    @Test
    void slotsDispatchToTheirConsumers() {
        AtomicInteger calls = new AtomicInteger();
        AgentRunListeners listeners = new AgentRunListeners(
                s -> calls.incrementAndGet(), null, null, null,
                n -> calls.addAndGet(10));

        listeners.progress().accept("line");
        listeners.triageNotice().accept(
                new TriageNotice(1, 1, "TIMEOUT", java.util.List.of()));

        assertEquals(11, calls.get());
    }
}
