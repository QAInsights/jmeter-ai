package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui;
import org.apache.jmeter.protocol.http.gui.HTTPArgumentsPanel;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBaseSchema;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;

/** Helpers for keeping HTTP Request Defaults elements compatible with JMeter's GUI. */
public final class HttpDefaultsSupport {
    private static final String ARGUMENTS_NAME = "User Defined Variables";

    private HttpDefaultsSupport() {
    }

    public static void ensureArguments(ConfigTestElement defaults) {
        if (defaults.getPropertyOrNull(HTTPSamplerBaseSchema.INSTANCE.getArguments()) == null) {
            Arguments arguments = new Arguments();
            arguments.setName(ARGUMENTS_NAME);
            arguments.setProperty(TestElement.GUI_CLASS, HTTPArgumentsPanel.class.getName());
            arguments.setProperty(TestElement.TEST_CLASS, Arguments.class.getName());
            defaults.set(HTTPSamplerBaseSchema.INSTANCE.getArguments(), arguments);
        }
    }

    public static void normalizeSerializedTree(HashTree tree) {
        for (Object key : tree.list()) {
            HashTree children = tree.getTree(key);
            if (children != null) {
                normalizeSerializedTree(children);
            }
            if (key instanceof ConfigTestElement defaults && isHttpDefaults(defaults)
                    && !hasArguments(defaults)) {
                ConfigTestElement normalized = (ConfigTestElement) defaults.clone();
                ensureArguments(normalized);
                tree.replaceKey(defaults, normalized);
            }
        }
    }

    public static void validateTree(HashTree tree) throws IllegalUserActionException {
        for (Object key : tree.list()) {
            if (key instanceof ConfigTestElement defaults && isHttpDefaults(defaults)
                    && !hasArguments(defaults)) {
                throw new IllegalUserActionException(
                        "HTTP Request Defaults is missing its HTTPsampler.Arguments element.");
            }
            HashTree children = tree.getTree(key);
            if (children != null) {
                validateTree(children);
            }
        }
    }

    private static boolean isHttpDefaults(ConfigTestElement element) {
        return HttpDefaultsGui.class.getName().equals(
                element.getPropertyAsString(TestElement.GUI_CLASS));
    }

    private static boolean hasArguments(ConfigTestElement element) {
        return element.getPropertyOrNull(HTTPSamplerBaseSchema.INSTANCE.getArguments()) != null;
    }
}
