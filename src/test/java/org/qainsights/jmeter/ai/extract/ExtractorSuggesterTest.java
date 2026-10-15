package org.qainsights.jmeter.ai.extract;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractorSuggesterTest {

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    private static SampleResult result(String body, String headers) {
        SampleResult r = new SampleResult();
        r.setSampleLabel("Login");
        r.setResponseData(body, StandardCharsets.UTF_8.name());
        r.setResponseHeaders(headers);
        return r;
    }

    private static ExtractorSuggestion first(List<ExtractorSuggestion> list, ExtractorSuggestion.Type type) {
        return list.stream().filter(s -> s.type() == type).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " in " + list.stream().map(ExtractorSuggestion::describe).toList()));
    }

    @Test
    void jsonValueGetsAJsonPathFirst() {
        SampleResult r = result("{\"data\":{\"user\":\"bob\",\"token\":\"eyJabc.def\"},\"items\":[{\"id\":7},{\"id\":42}]}", "");
        List<ExtractorSuggestion> s = ExtractorSuggester.suggest(r, r, "eyJabc.def");
        assertEquals(ExtractorSuggestion.Type.JSON, s.get(0).type());
        assertEquals("$.data.token", s.get(0).expression());
        assertEquals("token", s.get(0).variableName());
        first(s, ExtractorSuggestion.Type.REGEX);
        first(s, ExtractorSuggestion.Type.BOUNDARY);
    }

    @Test
    void jsonArrayElementUsesIndex() {
        SampleResult r = result("{\"items\":[{\"id\":7},{\"id\":42}]}", "");
        ExtractorSuggestion json = first(ExtractorSuggester.suggest(r, r, "42"), ExtractorSuggestion.Type.JSON);
        assertEquals("$.items[1].id", json.expression());
    }

    @Test
    void hiddenInputGetsCssSelectorOnValueAttribute() {
        SampleResult r = result("<html><body><form><input type=\"hidden\" name=\"csrf\" value=\"a1b2c3d4\"/>"
                + "</form></body></html>", "");
        List<ExtractorSuggestion> s = ExtractorSuggester.suggest(r, r, "a1b2c3d4");
        ExtractorSuggestion css = first(s, ExtractorSuggestion.Type.CSS);
        assertEquals("input[name=\"csrf\"]", css.expression());
        assertEquals("value", css.attribute());
        assertEquals("csrf", css.variableName());
        assertEquals("csrf", first(s, ExtractorSuggestion.Type.REGEX).variableName());
    }

    @Test
    void cookieInHeadersUsesHeaderRegex() {
        SampleResult r = result("ok", "HTTP/1.1 200 OK\r\nSet-Cookie: SESSION=9f8e7d6c; Path=/\r\nContent-Type: text/plain\r\n");
        List<ExtractorSuggestion> s = ExtractorSuggester.suggest(r, r, "9f8e7d6c");
        ExtractorSuggestion regex = first(s, ExtractorSuggestion.Type.REGEX);
        assertTrue(regex.usesHeaders());
        assertEquals("SESSION", regex.variableName());
        assertTrue(first(s, ExtractorSuggestion.Type.BOUNDARY).usesHeaders());
    }

    @Test
    void repeatedContextPicksTheRightMatchNumber() {
        SampleResult r = result("<a href=\"/item?id=111\">x</a><a href=\"/item?id=222\">y</a>", "");
        ExtractorSuggestion regex = first(ExtractorSuggester.suggest(r, r, "222"), ExtractorSuggestion.Type.REGEX);
        assertEquals("222", ExtractorSuggester.run(regex.toElement("id"), r, "id"));
    }

    @Test
    void valueAtEndOfBodyStillGetsARegex() {
        SampleResult r = result("token=abc123xyz", "");
        ExtractorSuggestion regex = first(ExtractorSuggester.suggest(r, r, "abc123xyz"), ExtractorSuggestion.Type.REGEX);
        assertEquals("token", regex.variableName());
    }

    @Test
    void subResultIsExtractedWithScopeAll() {
        SampleResult top = result("redirecting", "");
        SampleResult sub = result("{\"order\":\"ORD-991\"}", "");
        top.addSubResult(sub);
        ExtractorSuggestion json = first(ExtractorSuggester.suggest(top, sub, "ORD-991"), ExtractorSuggestion.Type.JSON);
        assertEquals("ORD-991", ExtractorSuggester.run(json.toElement("order"), top, "order"));
    }

    @Test
    void valueNotInResponseGivesNothing() {
        SampleResult r = result("{\"a\":\"b\"}", "");
        assertTrue(ExtractorSuggester.suggest(r, r, "zzz").isEmpty());
        assertTrue(ExtractorSuggester.suggest(r, r, "two\nlines").isEmpty());
    }

    @Test
    void normalizeStripsOneQuotePair() {
        assertEquals("abc", ExtractorSuggester.normalize("  \"abc\" "));
        assertEquals("'abc", ExtractorSuggester.normalize("'abc"));
        assertFalse(ExtractorSuggester.normalize(null).length() > 0);
    }

    @Test
    void variableNamesAreJMeterSafe() {
        assertEquals("access_token", ExtractorSuggester.variableName("access-token"));
        assertEquals("v_1st", ExtractorSuggester.variableName("1st"));
        assertEquals("value", ExtractorSuggester.variableName("--"));
    }
}
