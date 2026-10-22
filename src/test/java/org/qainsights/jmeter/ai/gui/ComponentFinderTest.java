package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ComponentFinderTest {

    @Test
    void testFindComponent_Success() {
        JPanel parent = new JPanel();
        JPanel childContainer = new JPanel();
        JButton targetButton = new JButton("Target");
        
        childContainer.add(targetButton);
        parent.add(childContainer);

        ComponentFinder<JButton> finder = new ComponentFinder<>(JButton.class);
        JButton found = finder.findComponentIn(parent);

        assertNotNull(found);
        assertEquals(targetButton, found);
    }

    @Test
    void testFindComponent_NotFound() {
        JPanel parent = new JPanel();
        JPanel childContainer = new JPanel();
        JLabel label = new JLabel("Not a button");
        
        childContainer.add(label);
        parent.add(childContainer);

        ComponentFinder<JButton> finder = new ComponentFinder<>(JButton.class);
        JButton found = finder.findComponentIn(parent);

        assertNull(found);
    }

    @Test
    void testFindComponent_NullContainerComponents() {
        JPanel parent = new JPanel() {
            @Override
            public Component[] getComponents() {
                return new Component[0];
            }
        };

        ComponentFinder<JButton> finder = new ComponentFinder<>(JButton.class);
        JButton found = finder.findComponentIn(parent);

        assertNull(found);
    }

    @Test
    void whenFoundRunsActionOnceAndStopsTimerForExistingComponent() throws Exception {
        JPanel root = new JPanel();
        JButton target = new JButton("Target");
        root.add(target);
        CountDownLatch found = new CountDownLatch(1);
        AtomicInteger actionCount = new AtomicInteger();
        AtomicReference<JButton> foundButton = new AtomicReference<>();
        AtomicReference<Timer> timer = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> timer.set(ComponentFinder.whenFound(
                root, JButton.class, 10, 3, component -> {
                    actionCount.incrementAndGet();
                    foundButton.set(component);
                    found.countDown();
                })));

        assertTrue(found.await(2, TimeUnit.SECONDS));
        assertSame(target, foundButton.get());
        assertFalse(timer.get().isRunning());
        assertEquals(1, actionCount.get());
    }

    @Test
    void whenFoundStopsAfterMaxAttemptsWithoutCallingAction() throws Exception {
        JPanel root = new JPanel();
        AtomicInteger actionCount = new AtomicInteger();
        AtomicReference<Timer> timer = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> timer.set(ComponentFinder.whenFound(
                root, JButton.class, 10, 3, component -> actionCount.incrementAndGet())));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (timer.get().isRunning()) {
                Thread.sleep(5);
            }
        });
        assertFalse(timer.get().isRunning());
        assertEquals(0, actionCount.get());
    }

    @Test
    void whenFoundFindsComponentAddedAfterSeveralTicks() throws Exception {
        JPanel root = new JPanel();
        JLabel target = new JLabel("Target");
        CountDownLatch found = new CountDownLatch(1);
        AtomicReference<Timer> timer = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            timer.set(ComponentFinder.whenFound(root, JLabel.class, 10, 10, component -> found.countDown()));
            Timer addTargetTimer = new Timer(30, event -> {
                root.add(target);
                ((Timer) event.getSource()).stop();
            });
            addTargetTimer.setRepeats(false);
            addTargetTimer.start();
        });

        assertTrue(found.await(2, TimeUnit.SECONDS));
        assertFalse(timer.get().isRunning());
    }
}
