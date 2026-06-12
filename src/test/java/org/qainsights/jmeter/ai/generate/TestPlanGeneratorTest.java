package org.qainsights.jmeter.ai.generate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestPlanGeneratorTest {

    private static final String HAR =
            "{\"log\":{\"entries\":[{\"request\":{\"method\":\"GET\",\"url\":\"https://h/x\",\"headers\":[]}}]}}";
    private static final String OPENAPI =
            "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://h\"}],\"paths\":{\"/x\":{\"get\":{}}}}";

    @Test
    void detectsHarAndOpenApi() throws Exception {
        assertEquals(TestPlanGenerator.Format.HAR, TestPlanGenerator.detect(HAR));
        assertEquals(TestPlanGenerator.Format.OPENAPI, TestPlanGenerator.detect(OPENAPI));
        assertEquals(TestPlanGenerator.Format.UNKNOWN, TestPlanGenerator.detect("{\"foo\":1}"));
    }

    @Test
    void generatesJmxFromHar() throws Exception {
        String jmx = TestPlanGenerator.generate(HAR, "Plan");
        assertTrue(jmx.contains("<jmeterTestPlan"), jmx);
        assertTrue(jmx.contains("<HTTPSamplerProxy"), jmx);
        assertTrue(jmx.contains("testname=\"Plan\""), jmx);
    }

    @Test
    void generatesJmxFromOpenApi() throws Exception {
        String jmx = TestPlanGenerator.generate(OPENAPI, "Plan");
        assertTrue(jmx.contains("<HTTPSamplerProxy"), jmx);
        assertTrue(jmx.contains("HTTPSampler.path\">/x</stringProp>"), jmx);
    }

    @Test
    void unknownFormatThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> TestPlanGenerator.generate("{\"foo\":1}", "Plan"));
    }

    @Test
    void emptyInputThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> TestPlanGenerator.generate("{\"log\":{\"entries\":[]}}", "Plan"));
    }
}
