package org.qainsights.jmeter.ai.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Timer;
import java.awt.*;
import java.util.function.Consumer;

public class ComponentFinder<T extends Component> {
    private static final Logger log = LoggerFactory.getLogger(ComponentFinder.class);

    private final Class<T> search;

    public ComponentFinder(Class<T> cls) {
        search = cls;
    }

    public static <T extends Component> Timer whenFound(
            Container root, Class<T> type, int intervalMs, int maxAttempts, Consumer<T> action) {
        ComponentFinder<T> finder = new ComponentFinder<>(type);
        int[] attempts = { 0 };
        Timer timer = new Timer(intervalMs, null);
        timer.addActionListener(event -> {
            T component = finder.findComponentIn(root);
            Timer currentTimer = (Timer) event.getSource();
            if (component != null) {
                currentTimer.stop();
                action.accept(component);
            } else if (++attempts[0] >= maxAttempts) {
                currentTimer.stop();
                log.warn("Could not find {} after {} attempts", type.getName(), maxAttempts);
            }
        });
        timer.setInitialDelay(0);
        timer.start();
        return timer;
    }

    public T findComponentIn(Container container) {
        log.debug("Searching in " + container);
        for (Component a : container.getComponents()) {
            if (search.isAssignableFrom(a.getClass())) {
                log.debug("Found " + a);
                return (T) a;
            }

            if (a instanceof Container) {
                T res = findComponentIn((Container) a);
                if (res != null) {
                    return res;
                }
            }
        }

        return null;
    }
}
