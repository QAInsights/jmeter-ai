package org.qainsights.jmeter.ai.generate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiParserTest {

    private static final String V3 = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Pet API\"},"
            + "\"servers\":[{\"url\":\"https://petstore.example.com/v2\"}],"
            + "\"paths\":{\"/pets\":{\"get\":{\"summary\":\"List pets\"},\"post\":{\"operationId\":\"createPet\"}},"
            + "\"/pets/{id}\":{\"get\":{}}}}";

    private static final String V2 = "{\"swagger\":\"2.0\",\"host\":\"api.example.com:8080\","
            + "\"basePath\":\"/api\",\"schemes\":[\"http\"],\"paths\":{\"/health\":{\"get\":{}}}}";

    @Test
    void parsesOpenApi3WithServerBase() throws Exception {
        TestPlanModel model = OpenApiParser.parse(V3, null);
        List<HttpRequestSpec> reqs = model.getRequests();
        assertEquals("Pet API", model.getName(), "falls back to info.title");
        assertEquals(3, reqs.size());

        HttpRequestSpec first = reqs.get(0);
        assertEquals("GET", first.getMethod());
        assertEquals("https", first.getProtocol());
        assertEquals("petstore.example.com", first.getDomain());
        assertEquals("/v2/pets", first.getPath());
        assertTrue(first.getName().contains("List pets"));
    }

    @Test
    void parsesSwagger2WithHostBasePathAndScheme() throws Exception {
        HttpRequestSpec req = OpenApiParser.parse(V2, "Health").getRequests().get(0);
        assertEquals("http", req.getProtocol());
        assertEquals("api.example.com", req.getDomain());
        assertEquals(8080, req.getPort());
        assertEquals("/api/health", req.getPath());
    }

    @Test
    void joinNormalisesSlashes() {
        assertEquals("/v2/pets", OpenApiParser.join("/v2/", "/pets"));
        assertEquals("/pets", OpenApiParser.join("", "pets"));
    }
}
