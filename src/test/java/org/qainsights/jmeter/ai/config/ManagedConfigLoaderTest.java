package org.qainsights.jmeter.ai.config;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ManagedConfigLoaderTest {

    @BeforeAll
    static void initProps() throws IOException {
        if (JMeterUtils.getJMeterProperties() == null) {
            File tmp = File.createTempFile("jmeter-test", ".properties");
            tmp.deleteOnExit();
            JMeterUtils.loadJMeterProperties(tmp.getAbsolutePath());
        }
    }

    @Test
    void parse_readsKeyValues() throws IOException {
        Properties p = ManagedConfigLoader.parse("a=1\nb = two\n# comment\nc=three");
        assertEquals("1", p.getProperty("a"));
        assertEquals("two", p.getProperty("b"));
        assertEquals("three", p.getProperty("c"));
        assertNull(p.getProperty("# comment"));
    }

    @Test
    void applyToJMeter_overrideTrue_replacesExisting() {
        String key = "jmeter.ai.test.override_true";
        JMeterUtils.setProperty(key, "local");
        Properties remote = new Properties();
        remote.setProperty(key, "managed");
        try {
            int n = ManagedConfigLoader.applyToJMeter(remote, true);
            assertEquals(1, n);
            assertEquals("managed", JMeterUtils.getProperty(key));
        } finally {
            JMeterUtils.getJMeterProperties().remove(key);
        }
    }

    @Test
    void applyToJMeter_overrideFalse_keepsExisting() {
        String key = "jmeter.ai.test.override_false";
        JMeterUtils.setProperty(key, "local");
        Properties remote = new Properties();
        remote.setProperty(key, "managed");
        try {
            int n = ManagedConfigLoader.applyToJMeter(remote, false);
            assertEquals(0, n, "existing key must be left untouched when override=false");
            assertEquals("local", JMeterUtils.getProperty(key));
        } finally {
            JMeterUtils.getJMeterProperties().remove(key);
        }
    }

    @Test
    void applyToJMeter_overrideFalse_setsMissingKeys() {
        String key = "jmeter.ai.test.fills_gap";
        Properties remote = new Properties();
        remote.setProperty(key, "managed");
        try {
            assertEquals(1, ManagedConfigLoader.applyToJMeter(remote, false));
            assertEquals("managed", JMeterUtils.getProperty(key));
        } finally {
            JMeterUtils.getJMeterProperties().remove(key);
        }
    }

    @Test
    void apply_loadsFromFile() throws IOException {
        File cfg = File.createTempFile("managed", ".properties");
        cfg.deleteOnExit();
        java.nio.file.Files.write(cfg.toPath(),
                "jmeter.ai.test.from_file=yes\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String key = "jmeter.ai.test.from_file";
        try {
            JMeterUtils.setProperty(ManagedConfigLoader.FILE_PROP, cfg.getAbsolutePath());
            ManagedConfigLoader.apply();
            assertEquals("yes", JMeterUtils.getProperty(key));
        } finally {
            JMeterUtils.getJMeterProperties().remove(ManagedConfigLoader.FILE_PROP);
            JMeterUtils.getJMeterProperties().remove(key);
        }
    }
}
