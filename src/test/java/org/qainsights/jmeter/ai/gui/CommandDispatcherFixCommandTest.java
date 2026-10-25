package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CommandDispatcherFixCommandTest {
    @Mock
    private CommandCallback callback;

    @Test
    void fixCommandShowsPlanGuidanceWhenNoPlanIsOpen() {
        new CommandDispatcher(callback, ignored -> null).dispatch("@fix");

        verify(callback).appendRedMessage("Open a JMeter test plan before using @fix.");
    }
}
