package org.nfe.jmeter.ai.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;

public class ComponentFinder<T extends Component> {
    private static final Logger log = LoggerFactory.getLogger(ComponentFinder.class);

    private final Class<T> search;

    public ComponentFinder(Class<T> cls) {
        search = cls;
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
