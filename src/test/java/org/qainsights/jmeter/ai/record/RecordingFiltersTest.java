package org.qainsights.jmeter.ai.record;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RecordingFilters}.
 * <p>
 * These check the pattern list itself rather than whether a given URL is excluded:
 * the matching is performed by {@code ProxyControl}, and the observable filtering
 * behaviour is asserted end-to-end in {@code JMeterProxyRecorderTest}. Duplicating the
 * match logic here would only test our guess at JMeter's semantics.
 */
class RecordingFiltersTest {

    @Test
    void should_provideNonEmptyImmutableDefaults() {
        assertFalse(RecordingFilters.defaultExcludes().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> RecordingFilters.defaultExcludes().add("nope"));
    }

    @Test
    void should_containOnlyCompilableRegexes() {
        for (String pattern : RecordingFilters.defaultExcludes()) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                fail("Not a valid regex: " + pattern + " (" + e.getDescription() + ")");
            }
        }
    }

    @Test
    void should_containNoDuplicates() {
        List<String> patterns = RecordingFilters.defaultExcludes();
        assertEquals(patterns.size(), patterns.stream().distinct().count(),
                "duplicate patterns make the recorder do redundant matching on every request");
    }

    @Test
    void should_coverStaticAssets_bothBareAndQueryStringed() {
        List<String> patterns = RecordingFilters.defaultExcludes();
        assertTrue(patterns.stream().anyMatch(p -> p.contains("woff2") && !p.contains("[\\?;]")),
                "expected a bare static-asset pattern");
        assertTrue(patterns.stream().anyMatch(p -> p.contains("woff2") && p.contains("[\\?;]")),
                "expected a static-asset pattern tolerating a query string or matrix parameter");
    }

    @Test
    void should_coverKnownTelemetryHosts() {
        String all = String.join("\n", RecordingFilters.defaultExcludes());
        assertTrue(all.contains("google-analytics"));
        assertTrue(all.contains("safebrowsing"));
        assertTrue(all.contains("detectportal"));
        assertTrue(all.contains("windowsupdate"));
    }

    @Test
    void should_notFilterApplicationPaths() {
        String all = String.join("\n", RecordingFilters.defaultExcludes());
        assertFalse(all.contains("/api"), "the defaults must never exclude application endpoints");
        assertFalse(all.contains("checkout"));
    }

    /**
     * ProxyControl does not match against a URL. {@code generateMatchUrl} produces
     * {@code domain:port/path?query} with NO scheme, and matching is a whole-string match.
     * These assertions use that exact shape - the earlier version of this test asserted
     * against {@code https://...} strings, which is why a pattern that matched nothing in
     * production passed its unit tests.
     */
    @Test
    void should_matchTargetHostAndSubdomains_when_includingByHost() {
        Pattern pattern = Pattern.compile(RecordingFilters.includeForHost("petstore.octoperf.com"));

        assertTrue(pattern.matcher(
                "petstore.octoperf.com:443/actions/Account.action?signonForm=").matches());
        assertTrue(pattern.matcher("petstore.octoperf.com:80/").matches());
        assertTrue(pattern.matcher("www.petstore.octoperf.com:443/x").matches(),
                "a www prefix is the same site");
        assertTrue(pattern.matcher("PETSTORE.OCTOPERF.COM:443/x").matches(),
                "hostnames are case-insensitive");
    }

    @Test
    void should_rejectSchemePrefixedUrls_when_includingByHost() {
        // Guards the regression directly: if someone reintroduces an "https?://" prefix, the
        // pattern stops matching what ProxyControl actually hands it.
        Pattern pattern = Pattern.compile(RecordingFilters.includeForHost("petstore.octoperf.com"));

        assertFalse(pattern.matcher("https://petstore.octoperf.com/x").matches(),
                "ProxyControl never presents a scheme, so this form is not what we match");
    }

    @Test
    void should_rejectOtherHosts_when_includingByHost() {
        Pattern pattern = Pattern.compile(RecordingFilters.includeForHost("petstore.octoperf.com"));

        assertFalse(pattern.matcher("www.google-analytics.com:443/collect").matches());
        assertFalse(pattern.matcher("cdn.jsdelivr.net:443/npm/x.js").matches());
        assertFalse(pattern.matcher("evil.test:443/?redirect=petstore.octoperf.com").matches(),
                "merely mentioning the host in a query string is not the target site");
        assertFalse(pattern.matcher("notpetstore.octoperf.com:443/x").matches(),
                "a longer hostname ending in the target is a different site");
    }

    @Test
    void should_escapeDots_when_includingByHost() {
        // An unescaped dot would let "aXbXc" match "a.b.c".
        Pattern pattern = Pattern.compile(RecordingFilters.includeForHost("a.b.c"));

        assertFalse(pattern.matcher("aXbXc:80/x").matches());
    }

    @Test
    void should_tolerateAMissingPort_when_includingByHost() {
        Pattern pattern = Pattern.compile(RecordingFilters.includeForHost("shop.test"));

        assertTrue(pattern.matcher("shop.test/basket").matches());
        assertTrue(pattern.matcher("shop.test").matches());
    }

    @Test
    void should_rejectBlankHost() {
        assertThrows(IllegalArgumentException.class, () -> RecordingFilters.includeForHost(null));
        assertThrows(IllegalArgumentException.class, () -> RecordingFilters.includeForHost("  "));
    }

    @Test
    void should_extractHost_when_uriIsAbsolute() {
        assertEquals("petstore.octoperf.com",
                RecordingFilters.hostOf("https://petstore.octoperf.com/actions/Account.action"));
        assertEquals("localhost", RecordingFilters.hostOf("http://localhost:8080/app"));
    }

    @Test
    void should_returnNullHost_when_uriIsUnusable() {
        // Null means "record everything", which beats aborting a recording over a bad URI.
        assertNull(RecordingFilters.hostOf(null));
        assertNull(RecordingFilters.hostOf(""));
        assertNull(RecordingFilters.hostOf("/relative/path"));
        assertNull(RecordingFilters.hostOf("h ttp://broken uri"));
    }
}
