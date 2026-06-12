package org.qainsights.jmeter.ai.generate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HarParserTest {

    private static final String HAR = "{\"log\":{\"entries\":["
            + "{\"request\":{\"method\":\"GET\",\"url\":\"https://api.example.com/users?page=1\","
            + "  \"headers\":[{\"name\":\"Accept\",\"value\":\"application/json\"},"
            + "  {\"name\":\"Host\",\"value\":\"api.example.com\"},"
            + "  {\"name\":\":authority\",\"value\":\"api.example.com\"}]}},"
            + "{\"request\":{\"method\":\"POST\",\"url\":\"https://api.example.com/login\","
            + "  \"headers\":[{\"name\":\"Content-Type\",\"value\":\"application/json\"}],"
            + "  \"postData\":{\"text\":\"{\\\"u\\\":\\\"a\\\"}\"}}},"
            + "{\"request\":{\"method\":\"GET\",\"url\":\"https://api.example.com/users?page=1\",\"headers\":[]}}"
            + "]}}";

    @Test
    void parsesRequestsAndDeduplicates() throws Exception {
        TestPlanModel model = HarParser.parse(HAR, "My Plan");
        List<HttpRequestSpec> reqs = model.getRequests();
        assertEquals("My Plan", model.getName());
        assertEquals(2, reqs.size(), "third entry duplicates the first GET /users?page=1");

        HttpRequestSpec get = reqs.get(0);
        assertEquals("GET", get.getMethod());
        assertEquals("https", get.getProtocol());
        assertEquals("api.example.com", get.getDomain());
        assertEquals("/users?page=1", get.getPath());
        assertTrue(get.getHeaders().containsKey("Accept"));
        assertFalse(get.getHeaders().containsKey("Host"), "Host filtered");
        assertFalse(get.getHeaders().containsKey(":authority"), "pseudo-header filtered");
    }

    @Test
    void capturesPostBody() throws Exception {
        HttpRequestSpec post = HarParser.parse(HAR, null).getRequests().get(1);
        assertEquals("POST", post.getMethod());
        assertEquals("/login", post.getPath());
        assertTrue(post.hasBody());
        assertTrue(post.getBody().contains("\"u\""));
        assertTrue(post.getHeaders().containsKey("Content-Type"));
    }

    @Test
    void emptyHarYieldsEmptyModel() throws Exception {
        assertTrue(HarParser.parse("{\"log\":{\"entries\":[]}}", "x").isEmpty());
    }
}
