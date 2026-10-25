package org.qainsights.jmeter.ai.gui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.UIManager;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.AgentJudgmentConfig;
import org.qainsights.jmeter.ai.agent.AgentRoutingConfig;
import org.qainsights.jmeter.ai.agent.AgentTriageConfig;
import org.qainsights.jmeter.ai.service.ProviderSetupOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandLogoTest {

    private static final List<String> LOGOS = List.of(
            "anthropic", "openai", "gemini", "deepseek", "grok", "meta",
            "ollama", "openrouter", "opencode", "apple", "gemma", "typesafe");

    @Test
    void everyLogoRendersAtCommonSizesWithLightAndDarkForegrounds() {
        Object originalForeground = UIManager.get("Label.foreground");
        try {
            for (Color foreground : List.of(new Color(0x252525), new Color(0xF5F5F5))) {
                UIManager.put("Label.foreground", foreground);
                for (String name : LOGOS) {
                    for (int size : List.of(16, 24, 48)) {
                        Icon icon = BrandLogo.icon(name, size);
                        assertNotNull(icon, name);
                        assertEquals(size, icon.getIconWidth());
                        assertEquals(size, icon.getIconHeight());
                        assertNonBlank(icon, size, name);
                    }
                }
            }
        } finally {
            restoreUiManagerValue("Label.foreground", originalForeground);
        }
    }

    @Test
    void unknownLogoReturnsNull() {
        assertNull(BrandLogo.icon("missing", 24));
    }

    @Test
    void mapsProviderOptionsAndSmartBackendsToLogoNames() {
        assertEquals("anthropic", BrandLogo.logoFor(ProviderSetupOption.ANTHROPIC));
        assertEquals("openai", BrandLogo.logoFor(ProviderSetupOption.OPENAI));
        assertEquals("gemini", BrandLogo.logoFor(ProviderSetupOption.GEMINI));
        assertEquals("deepseek", BrandLogo.logoFor(ProviderSetupOption.DEEPSEEK));
        assertEquals("grok", BrandLogo.logoFor(ProviderSetupOption.GROK));
        assertEquals("meta", BrandLogo.logoFor(ProviderSetupOption.META_MUSE));
        assertEquals("openrouter", BrandLogo.logoFor(ProviderSetupOption.OPENROUTER));
        assertEquals("opencode", BrandLogo.logoFor(ProviderSetupOption.OPENCODE));
        assertEquals("ollama", BrandLogo.logoFor(ProviderSetupOption.OLLAMA));
        assertEquals("apple", BrandLogo.logoFor(ProviderSetupOption.APPLE_ON_DEVICE));
        assertNull(BrandLogo.logoFor(null));

        assertEquals("typesafe", BrandLogo.logoForBackend("typesafe"));
        assertEquals("openai", BrandLogo.logoForBackend("openai"));
        assertEquals("openrouter", BrandLogo.logoForBackend("openrouter"));
        assertEquals("gemma", BrandLogo.logoForBackend("local"));
        assertEquals("ollama", BrandLogo.logoForBackend("ollama"));
        assertNull(BrandLogo.logoForBackend("off"));
        assertNull(BrandLogo.logoForBackend("unknown"));
        assertNull(BrandLogo.logoForBackend(null));

        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        String[] keys = {
                AgentRoutingConfig.TYPESAFE_ENABLED_KEY,
                AgentRoutingConfig.ROUTING_ENABLED_KEY,
                AgentTriageConfig.TRIAGE_ENABLED_KEY,
                AgentJudgmentConfig.PROVIDER_KEY
        };
        Map<String, String> original = snapshotProperties(keys);
        try {
            JMeterUtils.setProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "true");
            JMeterUtils.setProperty(AgentRoutingConfig.ROUTING_ENABLED_KEY, "true");
            JMeterUtils.setProperty(AgentTriageConfig.TRIAGE_ENABLED_KEY, "false");
            JMeterUtils.setProperty(AgentJudgmentConfig.PROVIDER_KEY, "typesafe");
            assertEquals("typesafe", BrandLogo.logoFor(ProviderSetupOption.SMART_ROUTING));
            JMeterUtils.setProperty(AgentJudgmentConfig.PROVIDER_KEY, "openai");
            assertEquals("openai", BrandLogo.logoFor(ProviderSetupOption.SMART_ROUTING));
            JMeterUtils.setProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "false");
            assertNull(BrandLogo.logoFor(ProviderSetupOption.SMART_ROUTING));
        } finally {
            restoreProperties(original);
        }
    }

    private static void assertNonBlank(Icon icon, int size, String name) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        boolean rendered = false;
        for (int y = 0; y < size && !rendered; y++) {
            for (int x = 0; x < size; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    rendered = true;
                    break;
                }
            }
        }
        assertTrue(rendered, name + " should render visible pixels");
    }

    private static Map<String, String> snapshotProperties(String... keys) {
        Map<String, String> original = new LinkedHashMap<>();
        for (String key : keys) {
            original.put(key, JMeterUtils.getJMeterProperties().getProperty(key));
        }
        return original;
    }

    private static void restoreProperties(Map<String, String> original) {
        var properties = JMeterUtils.getJMeterProperties();
        for (Map.Entry<String, String> entry : original.entrySet()) {
            if (entry.getValue() == null) {
                properties.remove(entry.getKey());
            } else {
                JMeterUtils.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void restoreUiManagerValue(String key, Object original) {
        if (original == null) {
            UIManager.getDefaults().remove(key);
        } else {
            UIManager.put(key, original);
        }
    }
}
