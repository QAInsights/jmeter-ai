package org.qainsights.jmeter.ai.correlation;

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the GUI-free parts of {@link CorrelationEngine}: JTL parsing,
 * candidate detection, cross-referencing against later samplers, and regex/variable
 * generation. No embedded engine run or {@code GuiPackage} is required.
 */
class CorrelationEngineTest {

    private final CorrelationEngine engine = new CorrelationEngine();

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static HTTPSamplerProxy http(String name, String path, String... nameValuePairs) {
        HTTPSamplerProxy s = new HTTPSamplerProxy();
        s.setName(name);
        s.setPath(path);
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            s.getArguments().addArgument(new HTTPArgument(nameValuePairs[i], nameValuePairs[i + 1]));
        }
        return s;
    }

    private static SampleResult result(String label, String body, String responseHeaders) {
        SampleResult r = new SampleResult();
        r.setSampleLabel(label);
        r.setResponseData(body, StandardCharsets.UTF_8.name());
        r.setResponseHeaders(responseHeaders);
        return r;
    }

    private static CorrelationCandidate candidate(String param, String value, int sourceIndex, String location) {
        CorrelationCandidate c = new CorrelationCandidate();
        c.setParameterName(param);
        c.setSampleValue(value);
        c.setSourceSamplerName("src");
        c.setSourceSamplerIndex(sourceIndex);
        c.setSourceLocation(location);
        return c;
    }

    private static CorrelationCandidate single(List<CorrelationCandidate> list) {
        assertEquals(1, list.size(), () -> "expected exactly one candidate, got " + list.size());
        return list.get(0);
    }

    // ---------------------------------------------------------------- parseJtl

    @Nested
    class ParseJtl {

        @TempDir
        Path tmp;

        private Path write(String name, String content) throws IOException {
            Path p = tmp.resolve(name);
            Files.write(p, content.getBytes(StandardCharsets.UTF_8));
            return p;
        }

        @Test
        void emptyFileThrows() throws IOException {
            Path p = write("empty.jtl", "   \n");
            IOException ex = assertThrows(IOException.class, () -> engine.parseJtl(p));
            assertEquals("JTL file is empty", ex.getMessage());
        }

        @Test
        void csvFileThrows() throws IOException {
            Path p = write("results.jtl", "timeStamp,elapsed,label,responseCode\n1,2,Login,200\n");
            IOException ex = assertThrows(IOException.class, () -> engine.parseJtl(p));
            assertEquals("JTL file is not XML. CSV JTL files are not supported.", ex.getMessage());
        }

        @Test
        void mapsElementsToSampleResultFieldsAndSortsByTimestamp() throws Exception {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<testResults version=\"1.2\">\n"
                    + "<httpSample ts=\"2000\" lb=\"Second\">\n"
                    + "  <responseHeader>HTTP/1.1 200 OK\nContent-Type: text/html</responseHeader>\n"
                    + "  <requestHeader>Cookie: a=b</requestHeader>\n"
                    + "  <responseData>second-body</responseData>\n"
                    + "  <samplerData>POST data: x=y</samplerData>\n"
                    + "  <java.net.URL>http://example.com/second</java.net.URL>\n"
                    + "</httpSample>\n"
                    + "<httpSample ts=\"1000\" lb=\"First\" url=\"http://example.com/first\">\n"
                    + "  <responseHeader>Set-Cookie: JSESSIONID=abc123</responseHeader>\n"
                    + "  <responseData>first-body</responseData>\n"
                    + "  <queryString>q=1</queryString>\n"
                    + "</httpSample>\n"
                    + "</testResults>\n";
            List<SampleResult> results = engine.parseJtl(write("ok.jtl", xml));

            assertEquals(2, results.size());
            SampleResult first = results.get(0);
            SampleResult second = results.get(1);

            assertEquals("First", first.getSampleLabel());
            assertEquals(1000L, first.getTimeStamp());
            assertEquals("first-body", first.getResponseDataAsString());
            assertEquals("Set-Cookie: JSESSIONID=abc123", first.getResponseHeaders());
            assertEquals("q=1", first.getSamplerData());
            assertEquals("http://example.com/first", first.getUrlAsString());
            assertTrue(first.isSuccessful());

            assertEquals("Second", second.getSampleLabel());
            assertEquals(2000L, second.getTimeStamp());
            assertEquals("second-body", second.getResponseDataAsString());
            assertEquals("HTTP/1.1 200 OK\nContent-Type: text/html", second.getResponseHeaders());
            assertEquals("Cookie: a=b", second.getRequestHeaders());
            assertEquals("POST data: x=y", second.getSamplerData());
            assertEquals("http://example.com/second", second.getUrlAsString());
        }

        @Test
        void nestedSubSamplesAreFoldedIntoParent() throws Exception {
            String xml = "<testResults version=\"1.2\">\n"
                    + "<httpSample ts=\"1\" lb=\"Parent\">\n"
                    + "  <httpSample ts=\"2\" lb=\"Child\">\n"
                    + "    <responseData>child-body</responseData>\n"
                    + "  </httpSample>\n"
                    + "  <responseData>parent-body</responseData>\n"
                    + "</httpSample>\n"
                    + "</testResults>";
            List<SampleResult> results = engine.parseJtl(write("nested.jtl", xml));

            assertEquals(1, results.size());
            assertEquals("Parent", results.get(0).getSampleLabel());
            assertEquals("parent-body", results.get(0).getResponseDataAsString());
        }

        @Test
        void nonNumericTimestampFallsBackToZero() throws Exception {
            String xml = "<testResults version=\"1.2\">\n"
                    + "<sample ts=\"later\" lb=\"Bad\"><responseData>b</responseData></sample>\n"
                    + "<sample ts=\"5\" lb=\"Good\"><responseData>g</responseData></sample>\n"
                    + "</testResults>";
            List<SampleResult> results = engine.parseJtl(write("ts.jtl", xml));

            assertEquals(2, results.size());
            assertEquals("Bad", results.get(0).getSampleLabel());
            assertEquals(0L, results.get(0).getTimeStamp());
            assertEquals("Good", results.get(1).getSampleLabel());
        }

        @Test
        void externalEntityIsNotResolved() throws Exception {
            Path secret = write("secret.txt", "TOP-SECRET");
            String xml = "<?xml version=\"1.0\"?>\n"
                    + "<!DOCTYPE testResults [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>\n"
                    + "<testResults version=\"1.2\">\n"
                    + "<httpSample ts=\"1\" lb=\"X\"><responseData>&xxe;</responseData></httpSample>\n"
                    + "</testResults>";
            Path p = write("xxe.jtl", xml);

            try {
                List<SampleResult> results = engine.parseJtl(p);
                for (SampleResult r : results) {
                    assertFalse(r.getResponseDataAsString().contains("TOP-SECRET"),
                            "external entity must not be expanded");
                }
            } catch (Exception rejected) {
                assertFalse(String.valueOf(rejected.getMessage()).contains("TOP-SECRET"));
            }
        }
    }

    // --------------------------------------------------------- detectCandidates

    @Nested
    class DetectCandidates {

        @Test
        void setCookieHeaderWithKnownTokenIsDetected() {
            List<AbstractSampler> samplers = Collections.singletonList(http("Login", "/login"));
            SampleResult r = result("Login", "", "HTTP/1.1 200 OK\nSet-Cookie: JSESSIONID=A1B2C3D4; Path=/\nContent-Type: text/html");

            CorrelationCandidate c = single(engine.detectCandidates(samplers, Collections.singletonList(r)));
            assertEquals("JSESSIONID", c.getParameterName());
            assertEquals("A1B2C3D4", c.getSampleValue());
            assertEquals("Login", c.getSourceSamplerName());
            assertEquals(0, c.getSourceSamplerIndex());
            assertEquals("Response Header: Set-Cookie", c.getSourceLocation());
        }

        @Test
        void setCookieWithUnknownTokenIsIgnored() {
            List<AbstractSampler> samplers = Collections.singletonList(http("Login", "/login"));
            SampleResult r = result("Login", "", "Set-Cookie: tracking_pref=abcdef; Path=/");

            assertTrue(engine.detectCandidates(samplers, Collections.singletonList(r)).isEmpty());
        }

        @Test
        void jsonBodyTokenIsDetected() {
            List<AbstractSampler> samplers = Collections.singletonList(http("Auth", "/auth"));
            SampleResult r = result("Auth", "{\"access_token\": \"tok-9f8e7d\", \"expires\": 3600}", null);

            CorrelationCandidate c = single(engine.detectCandidates(samplers, Collections.singletonList(r)));
            assertEquals("access_token", c.getParameterName());
            assertEquals("tok-9f8e7d", c.getSampleValue());
            assertEquals("Response Body (JSON)", c.getSourceLocation());
        }

        @Test
        void hiddenInputIsDetectedOnceEvenIfTokenMatchesMultiplePatterns() {
            List<AbstractSampler> samplers = Collections.singletonList(http("Form", "/form"));
            String body = "<form><input type=\"hidden\" name=\"_token\" value=\"xyz789\"/>"
                    + "<input type=\"hidden\" name=\"_token\" value=\"xyz789\"/></form>";
            SampleResult r = result("Form", body, null);

            List<CorrelationCandidate> found = engine.detectCandidates(samplers, Collections.singletonList(r));
            assertEquals(1, found.size(), "same token+location on same sampler must be deduplicated");
            CorrelationCandidate c = found.get(0);
            assertEquals("_token", c.getParameterName());
            assertEquals("xyz789", c.getSampleValue());
            assertEquals("Response Body (hidden input)", c.getSourceLocation());
        }

        @Test
        void urlPathAndFormValuesAreDetectedWithDistinctLocations() {
            List<AbstractSampler> samplers = Collections.singletonList(http("Home", "/"));
            String body = "<a href=\"/cart;jsessionid=PATHVAL1\">cart</a><a href=\"/next?state=FORMVAL2&amp;next=1\">";
            SampleResult r = result("Home", body, null);

            List<CorrelationCandidate> found = engine.detectCandidates(samplers, Collections.singletonList(r));
            CorrelationCandidate path = found.stream()
                    .filter(c -> "jsessionid".equals(c.getParameterName()) && c.getSourceLocation().contains("URL path"))
                    .findFirst().orElseThrow();
            CorrelationCandidate form = found.stream()
                    .filter(c -> "state".equals(c.getParameterName()) && c.getSourceLocation().contains("form"))
                    .findFirst().orElseThrow();
            assertEquals("PATHVAL1", path.getSampleValue());
            assertEquals("jsessionid", path.getParameterName());
            assertEquals("FORMVAL2", form.getSampleValue());
            assertEquals("state", form.getParameterName());
        }

        @Test
        void rejectsPureNumericShortAndExcludedValues() {
            List<AbstractSampler> samplers = Collections.singletonList(http("Api", "/api"));
            String body = "{\"code\": \"123456\", \"state\": \"ab\", \"nonce\": \"null\", \"csrf\": \"ok-value\"}";
            SampleResult r = result("Api", body, null);

            CorrelationCandidate c = single(engine.detectCandidates(samplers, Collections.singletonList(r)));
            assertEquals("csrf", c.getParameterName());
            assertEquals("ok-value", c.getSampleValue());
        }

        @Test
        void resultMatchedToSamplerByLabelNotPosition() {
            List<AbstractSampler> samplers = Arrays.asList(http("First", "/1"), http("Second", "/2"));
            SampleResult r = result("Second", "{\"csrf\": \"abcdef\"}", null);

            CorrelationCandidate c = single(engine.detectCandidates(samplers, Collections.singletonList(r)));
            assertEquals("Second", c.getSourceSamplerName());
            assertEquals(1, c.getSourceSamplerIndex());
        }

        @Test
        void unknownLabelFallsBackToResultIndexAndOutOfRangeIsSkipped() {
            List<AbstractSampler> samplers = Collections.singletonList(http("Only", "/only"));
            SampleResult inRange = result("does-not-exist", "{\"csrf\": \"abcdef\"}", null);
            SampleResult outOfRange = result("also-missing", "{\"csrf\": \"ghijkl\"}", null);

            List<CorrelationCandidate> found = engine.detectCandidates(samplers, Arrays.asList(inRange, outOfRange));
            CorrelationCandidate c = single(found);
            assertEquals("Only", c.getSourceSamplerName());
            assertEquals("abcdef", c.getSampleValue());
        }
    }

    // ----------------------------------------------------------- crossReference

    @Nested
    class CrossReference {

        @Test
        void matchesLaterSamplerByArgumentName() {
            List<AbstractSampler> samplers = Arrays.asList(
                    http("Login", "/login"),
                    http("Submit", "/submit", "CSRF", "placeholder"));
            CorrelationCandidate c = candidate("csrf", "abc", 0, "Response Body (JSON)");

            engine.crossReference(Collections.singletonList(c), samplers);

            assertEquals(Collections.singletonList("Submit"), c.getTargetSamplerNames());
            assertEquals(Collections.singletonList(1), c.getTargetSamplerIndices());
        }

        @Test
        void matchesArgumentValueContainingSampleValueOnlyWhenLongerThanFive() {
            List<AbstractSampler> samplers = Arrays.asList(
                    http("Src", "/src"),
                    http("Long", "/x", "other", "prefix-ABCDEF-suffix"),
                    http("Short", "/y", "other", "prefix-ABCDE-suffix"));
            CorrelationCandidate longValue = candidate("tok", "ABCDEF", 0, "Response Body (form)");
            CorrelationCandidate shortValue = candidate("tok", "ABCDE", 0, "Response Body (form)");

            engine.crossReference(Arrays.asList(longValue, shortValue), samplers);

            assertEquals(Collections.singletonList("Long"), longValue.getTargetSamplerNames());
            assertTrue(shortValue.getTargetSamplerNames().isEmpty(), "5-char values must not match by substring");
        }

        @Test
        void matchesSampleValueInPath() {
            List<AbstractSampler> samplers = Arrays.asList(
                    http("Src", "/src"),
                    http("Detail", "/items;jsessionid=SESS-42-XYZ/detail"));
            CorrelationCandidate c = candidate("jsessionid", "SESS-42-XYZ", 0, "Response Body (URL path)");

            engine.crossReference(Collections.singletonList(c), samplers);

            assertEquals(Collections.singletonList("Detail"), c.getTargetSamplerNames());
        }

        @Test
        void ignoresSamplersAtOrBeforeSourceAndUnrelatedSamplers() {
            List<AbstractSampler> samplers = Arrays.asList(
                    http("Earlier", "/e", "csrf", "abcdef"),
                    http("Source", "/s", "csrf", "abcdef"),
                    http("Unrelated", "/u", "foo", "bar"));
            CorrelationCandidate c = candidate("csrf", "abcdef", 1, "Response Body (JSON)");

            engine.crossReference(Collections.singletonList(c), samplers);

            assertTrue(c.getTargetSamplerNames().isEmpty());
            assertEquals(0, c.getUsageCount());
        }
    }

    // ---------------------------------------------------------- generatePattern

    @Nested
    class GeneratePattern {

        private String patternFor(String param, String location) {
            CorrelationCandidate c = candidate(param, "v", 0, location);
            engine.generatePattern(c);
            assertEquals("regex", c.getExtractorType());
            return c.getExtractionPattern();
        }

        private String firstGroup(String regex, String body) {
            Matcher m = Pattern.compile(regex).matcher(body);
            assertTrue(m.find(), () -> "pattern " + regex + " did not match " + body);
            return m.group(1);
        }

        @Test
        void setCookiePattern() {
            String p = patternFor("JSESSIONID", "Response Header: Set-Cookie");
            assertEquals("(?i)Set-Cookie:\\s*_*JSESSIONID=([^;]+)", p);
            assertEquals("A1B2", firstGroup(p, "HTTP/1.1 200\nSet-Cookie: jsessionid=A1B2; Path=/"));
        }

        @Test
        void hiddenInputPatternToleratesNewlinesAndLeadingUnderscores() {
            String p = patternFor("__token", "Response Body (hidden input)");
            assertEquals("(?is)<input[^>]*?name=[\"']_*token[\"'][^>]*?value=[\"']([^\"']+)[\"']", p);
            assertEquals("xyz789", firstGroup(p, "<input type='hidden'\n name=\"_TOKEN\"\n value=\"xyz789\">"));
        }

        @Test
        void jsonPattern() {
            String p = patternFor("access_token", "Response Body (JSON)");
            assertEquals("(?i)\"_*access_token\"\\s*:\\s*\"([^\"]+)\"", p);
            assertEquals("tok-1", firstGroup(p, "{\"Access_Token\" : \"tok-1\"}"));
        }

        @Test
        void urlPathPattern() {
            String p = patternFor("jsessionid", "Response Body (URL path)");
            assertEquals("(?i);_*jsessionid=([^;/?#\"'<>\\s]+)", p);
            assertEquals("S1", firstGroup(p, "<a href=\"/a;JSESSIONID=S1?x=1\">"));
        }

        @Test
        void defaultFormPatternAndNullLocation() {
            String form = patternFor("state", "Response Body (form)");
            assertEquals("(?i)_*state=([^&;\"'<>\\s]+)", form);
            assertEquals("FORMVAL2", firstGroup(form, "next=1&state=FORMVAL2&z=3"));

            assertEquals("(?i)_*state=([^&;\"'<>\\s]+)", patternFor("state", null));
        }

        @Test
        void regexMetacharactersInNameAreEscaped() {
            String p = patternFor("a.b[c]", "Response Body (JSON)");
            assertEquals("(?i)\"_*a\\.b\\[c\\]\"\\s*:\\s*\"([^\"]+)\"", p);
            assertEquals("val", firstGroup(p, "{\"a.b[c]\":\"val\"}"));
            assertFalse(Pattern.compile(p).matcher("{\"aXbc\":\"val\"}").find(), "dot must be literal");
        }

        @Test
        void variableNameIsDerivedFromParameterName() {
            CorrelationCandidate c = candidate("__Source.Page-ID__", "v", 0, "Response Body (form)");
            engine.generatePattern(c);
            assertEquals("source_page_id", c.getVariableName());
        }
    }

    // ------------------------------------------------ escapeRegex / toVarName

    @Test
    void escapeRegexEscapesMetacharactersOnly() {
        assertEquals("plain_name-1", CorrelationEngine.escapeRegex("plain_name-1"));
        assertEquals("\\\\\\[\\]\\(\\)\\{\\}\\.\\*\\+\\?\\^\\$\\|", CorrelationEngine.escapeRegex("\\[](){}.*+?^$|"));
    }

    @Test
    void toVarNameNormalisesToLowercaseSnakeCase() {
        assertEquals("jsessionid", CorrelationEngine.toVarName("JSESSIONID"));
        assertEquals("sourcepage", CorrelationEngine.toVarName("_sourcePage"));
        assertEquals("fp", CorrelationEngine.toVarName("__fp"));
        assertEquals("a_b_c", CorrelationEngine.toVarName("a.b-c"));
        assertEquals("x_9", CorrelationEngine.toVarName("  X 9  "));
    }

    // --------------------------------------------------------------- correlate

    @Test
    void correlateEndToEndReturnsOnlyReusedCandidatesWithPatterns() {
        List<AbstractSampler> samplers = Arrays.asList(
                http("Login", "/login"),
                http("Dashboard", "/dashboard", "csrf", "stale-token"),
                http("Logout", "/logout"));
        SampleResult login = result("Login",
                "{\"csrf\": \"fresh-token-1\", \"nonce\": \"unused-nonce\"}",
                "Set-Cookie: JSESSIONID=SESSION9; Path=/");

        List<CorrelationCandidate> reused = engine.correlate(samplers, Collections.singletonList(login));

        assertEquals(3, engine.getCandidatesFound());
        assertEquals(1, engine.getReusedFound());
        CorrelationCandidate c = single(reused);
        assertEquals("csrf", c.getParameterName());
        assertEquals("fresh-token-1", c.getSampleValue());
        assertEquals("csrf", c.getVariableName());
        assertEquals("regex", c.getExtractorType());
        assertEquals("(?i)\"_*csrf\"\\s*:\\s*\"([^\"]+)\"", c.getExtractionPattern());
        assertEquals(Collections.singletonList("Dashboard"), c.getTargetSamplerNames());
    }
}
