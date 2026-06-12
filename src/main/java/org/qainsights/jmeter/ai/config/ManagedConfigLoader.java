package org.qainsights.jmeter.ai.config;

import org.apache.jmeter.util.JMeterUtils;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Loads a managed (org-wide) configuration from a URL or shared file and merges
 * it into JMeter's properties at startup, so a platform team can centrally
 * control models, tool-trust, redaction, audit, and MCP settings instead of
 * editing {@code user.properties} on every engineer's machine.
 *
 * <p>Driven by:
 * <ul>
 *   <li>{@code jmeter.ai.config.remote.url} — http(s) URL to a {@code .properties} file</li>
 *   <li>{@code jmeter.ai.config.remote.file} — path to a {@code .properties} file</li>
 *   <li>{@code jmeter.ai.config.remote.override} (default true) — when true the
 *       managed values win over local ones (so locked policy can't be undone);
 *       when false they only fill gaps.</li>
 * </ul>
 *
 * <p>Best-effort: a missing/unreachable source never blocks the plugin.
 */
public final class ManagedConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ManagedConfigLoader.class);

    public static final String URL_PROP = "jmeter.ai.config.remote.url";
    public static final String FILE_PROP = "jmeter.ai.config.remote.file";
    public static final String OVERRIDE_PROP = "jmeter.ai.config.remote.override";

    private static final int TIMEOUT_MS = 5000;
    private static volatile boolean applied;

    private ManagedConfigLoader() {
    }

    /** Apply managed config once per JVM; safe to call from multiple entry points. */
    public static synchronized void applyOnce() {
        if (applied) {
            return;
        }
        applied = true;
        try {
            apply();
        } catch (Exception e) {
            log.warn("Managed config not applied: {}", e.getMessage());
        }
    }

    static void apply() throws IOException {
        String url = AiConfig.getProperty(URL_PROP, "").trim();
        String file = AiConfig.getProperty(FILE_PROP, "").trim();

        String content = null;
        if (!url.isEmpty()) {
            content = fetchUrl(url);
        } else if (!file.isEmpty()) {
            content = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        }
        if (content == null) {
            return;
        }

        Properties remote = parse(content);
        boolean override = AiConfig.getProperty(OVERRIDE_PROP, "true").equalsIgnoreCase("true");
        int n = applyToJMeter(remote, override);
        log.info("Applied {} managed config propert{} (override={}) from {}",
                n, n == 1 ? "y" : "ies", override, url.isEmpty() ? file : url);
    }

    static Properties parse(String text) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(text));
        return p;
    }

    /**
     * Merge {@code remote} into JMeter's live properties.
     *
     * @param override when true, remote values replace existing ones; when false,
     *                 only keys absent locally are set.
     * @return the number of properties applied.
     */
    static int applyToJMeter(Properties remote, boolean override) {
        Properties jm = JMeterUtils.getJMeterProperties();
        int applied = 0;
        for (String key : remote.stringPropertyNames()) {
            boolean present = jm != null && jm.containsKey(key);
            if (override || !present) {
                JMeterUtils.setProperty(key, remote.getProperty(key));
                applied++;
            }
        }
        return applied;
    }

    static String fetchUrl(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "text/plain");
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            conn.disconnect();
        }
    }
}
