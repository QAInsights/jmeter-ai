package org.qainsights.jmeter.ai.record;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Default URL exclusion patterns for the recorder.
 * <p>
 * These are taken verbatim from JMeter's own {@code bin/templates/recording.jmx}
 * recorder template rather than invented here: the list is the product of years of
 * field reports about which requests pollute a recorded plan (static assets, browser
 * telemetry, safe-browsing and toolbar chatter). Reusing it keeps our output
 * consistent with what a JMeter user gets from the native recorder.
 */
public final class RecordingFilters {

    /** Static assets, both plain and with a query string or matrix parameter. */
    private static final String STATIC_ASSETS =
            "(?i).*\\.(bmp|css|js|gif|ico|jpe?g|png|swf|eot|otf|ttf|mp4|woff|woff2)";
    private static final String STATIC_ASSETS_WITH_QUERY =
            "(?i).*\\.(bmp|css|js|gif|ico|jpe?g|png|swf|eot|otf|ttf|mp4|woff|woff2)[\\?;].*";

    private static final List<String> DEFAULT_EXCLUDES = Collections.unmodifiableList(Arrays.asList(
            STATIC_ASSETS,
            STATIC_ASSETS_WITH_QUERY,
            "www\\.download\\.windowsupdate\\.com.*",
            "windowsupdate\\.microsoft\\.com.*",
            "sqm\\.microsoft\\.com.*",
            "g\\.msn.*",
            "toolbar\\.msn\\.com.*",
            "api\\.bing\\.com.*",
            "toolbarqueries\\.google\\..*",
            "toolbar\\.google\\.com.*",
            "clients.*\\.google.*",
            "safebrowsing.*\\.google\\.com.*",
            ".*\\.google\\.com.*/safebrowsing/.*",
            "www\\.google-analytics\\.com.*",
            "us\\.update\\.toolbar\\.yahoo\\.com.*",
            ".*toolbar\\.yahoo\\.com.*",
            ".*msg\\.yahoo\\.com.*",
            "geo\\.yahoo\\.com.*",
            "pgq\\.yahoo\\.com.*",
            ".*yimg\\.com.*",
            "tiles.*\\.mozilla\\.com.*",
            "http?://self-repair\\.mozilla\\.org.*",
            ".*detectportal\\.firefox\\.com.*",
            "toolbar\\.avg\\.com/.*"));

    private RecordingFilters() {
    }

    /**
     * The default exclusion patterns, in the order JMeter's template declares them.
     * <p>
     * Deliberately no local "would this URL be excluded?" helper: matching is done by
     * {@code ProxyControl} using ORO with its own semantics, and a second implementation
     * here would be a guess that could silently disagree with the recorder. The effect of
     * these patterns is covered end-to-end in {@code JMeterProxyRecorderTest} instead.
     *
     * @return an unmodifiable list of regular expressions in JMeter's declared order
     */
    public static List<String> defaultExcludes() {
        return DEFAULT_EXCLUDES;
    }

    /**
     * An include pattern restricting capture to {@code host} and its subdomains.
     * <p>
     * The shape is dictated by what {@code ProxyControl} actually matches against, which is
     * <strong>not</strong> a URL. {@code ProxyControl.generateMatchUrl} builds
     * {@code domain:port/path?query} - there is <strong>no scheme</strong>. A pattern
     * beginning {@code https?://} therefore matches nothing at all, and because
     * {@code ProxyControl} still notifies sample listeners for requests it excludes, the
     * failure is invisible: the sample count climbs into the thousands while every
     * Transaction Controller stays empty. That exact mistake shipped once already.
     * <p>
     * Two further {@code ProxyControl} behaviours matter. Matching uses ORO's
     * {@code Perl5Matcher.matches}, which requires the pattern to cover the <em>whole</em>
     * string, hence the trailing {@code (/.*)?} rather than an anchor at the host. And only
     * dots need escaping - every other character legal in a hostname is a regex literal.
     * <p>
     * The optional {@code ([^/]+\.)?} prefix admits {@code www.} and other subdomains while
     * still refusing a URL that merely mentions the host later on: a request to
     * {@code evil.com:80/?r=<host>} cannot match, because the prefix may not span the
     * {@code /}. The port group is optional purely for robustness; in practice
     * {@code generateMatchUrl} always emits one, falling back to the protocol default.
     *
     * @param host a hostname such as {@code petstore.octoperf.com}
     * @return a regular expression suitable for {@code addIncludedPattern}
     */
    public static String includeForHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        return "(?i)([^/]+\\.)?" + host.trim().replace(".", "\\.") + "(:\\d+)?(/.*)?";
    }

    /**
     * The host of an absolute URI, or null when it has none.
     * <p>
     * Returns null rather than throwing: a missing host means "record everything", which is
     * a safer outcome for the user than aborting a recording over an unparseable URI.
     *
     * @param uri an absolute URI such as {@code https://petstore.octoperf.com/actions/x}
     * @return the host, or null if {@code uri} is blank, relative or malformed
     */
    public static String hostOf(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            return null;
        }
        try {
            String host = new java.net.URI(uri.trim()).getHost();
            return host == null || host.isEmpty() ? null : host;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }
}
