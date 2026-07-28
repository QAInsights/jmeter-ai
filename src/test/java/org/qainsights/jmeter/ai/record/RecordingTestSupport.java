package org.qainsights.jmeter.ai.record;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Shared JMeter bootstrap for the recorder tests.
 * <p>
 * This exists because of one specific JMeter behaviour: {@code ProxyControl} resolves its
 * certificate directory into a {@code static final} field the first time the class is
 * loaded, from whatever {@code JMeterUtils.getJMeterHome()} happens to be at that moment.
 * It is therefore frozen for the lifetime of the JVM, and every later
 * {@code setJMeterHome} call is ignored by the keystore code.
 * <p>
 * A per-class {@code @TempDir} is fatal here: JUnit deletes it when that class finishes,
 * and the next test class to start the proxy shells out to {@code keytool} with a working
 * directory that no longer exists ("CreateProcess error=267"). So all recorder tests share
 * one stable directory under {@code target/} that outlives the whole run.
 */
final class RecordingTestSupport {

    private static Path jmeterHome;

    private RecordingTestSupport() {
    }

    /**
     * Points JMeter at a stable home directory containing a {@code bin} folder, and loads
     * default properties. Idempotent and safe to call from every test class.
     *
     * @return the JMeter home directory in use for this JVM
     */
    static synchronized Path initJMeterHome() {
        if (jmeterHome == null) {
            Path home = Paths.get("target", "test-jmeter-home").toAbsolutePath();
            try {
                Files.createDirectories(home.resolve("bin"));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not create a test JMeter home at " + home, e);
            }
            jmeterHome = home;
        }
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        JMeterUtils.setJMeterHome(jmeterHome.toString());
        return jmeterHome;
    }

    /**
     * Creates a directory for a single test's output artifacts, e.g. a recording JTL.
     *
     * @param name unique-ish directory name, normally the test method name
     * @return an existing, writable directory
     */
    static Path artifactDir(String name) {
        Path dir = initJMeterHome().resolve("artifacts").resolve(name);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create artifact directory " + dir, e);
        }
        return dir;
    }
}
