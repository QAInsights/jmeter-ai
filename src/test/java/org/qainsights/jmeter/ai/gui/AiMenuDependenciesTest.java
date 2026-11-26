package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiMenuDependenciesTest {
    @Test
    void dependenciesPresent() {
        assertTrue(AiMenuCreator.dependenciesAvailable(getClass().getClassLoader()));
    }

    @Test
    void dependenciesAbsent() {
        assertFalse(AiMenuCreator.dependenciesAvailable(new ClassLoader(null) { }));
    }

    @Test
    void incompatibleDependency() {
        assertFalse(AiMenuCreator.dependenciesAvailable(new ClassLoader(null) {
            @Override
            public Class<?> loadClass(String name) {
                throw new NoClassDefFoundError(name);
            }
        }));
    }
}
