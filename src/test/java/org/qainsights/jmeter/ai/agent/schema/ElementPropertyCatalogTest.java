package org.qainsights.jmeter.ai.agent.schema;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ElementPropertyCatalog}. */
class ElementPropertyCatalogTest {

    @Test
    void propertiesFor_httpSampler_includesCoreKeys() {
        List<ElementPropertyCatalog.Property> props = ElementPropertyCatalog.propertiesFor("HTTPSamplerProxy");
        assertFalse(props.isEmpty());
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("HTTPSampler.domain")));
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("HTTPSampler.path")));
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("HTTPSampler.method")));
    }

    @Test
    void propertiesFor_isCaseInsensitive() {
        assertEquals(
                ElementPropertyCatalog.propertiesFor("ThreadGroup").size(),
                ElementPropertyCatalog.propertiesFor("threadgroup").size());
        assertFalse(ElementPropertyCatalog.propertiesFor("threadgroup").isEmpty());
    }

    @Test
    void propertiesFor_unknownOrNull_isEmpty() {
        assertTrue(ElementPropertyCatalog.propertiesFor("NotARealType").isEmpty());
        assertTrue(ElementPropertyCatalog.propertiesFor(null).isEmpty());
    }

    @Test
    void describe_knownType_rendersKeysTypesAndDescriptions() {
        String text = ElementPropertyCatalog.describe("HTTPSamplerProxy");
        assertTrue(text.contains("Common properties"));
        assertTrue(text.contains("HTTPSampler.domain : string"));
        assertTrue(text.contains("update_element_property"));
    }

    @Test
    void describe_unknownType_isEmptyString() {
        assertEquals("", ElementPropertyCatalog.describe("NotARealType"));
    }

    @Test
    void properties_haveNonBlankKeyTypeAndDescription() {
        for (ElementPropertyCatalog.Property p : ElementPropertyCatalog.propertiesFor("CSVDataSet")) {
            assertNotNull(p.getKey());
            assertFalse(p.getKey().trim().isEmpty());
            assertFalse(p.getType().trim().isEmpty());
            assertFalse(p.getDescription().trim().isEmpty());
        }
    }

    @Test
    void propertiesFor_jsr223Sampler_includesScriptAndLanguage() {
        List<ElementPropertyCatalog.Property> props = ElementPropertyCatalog.propertiesFor("JSR223Sampler");
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("script")));
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("scriptLanguage")));
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("cacheKey")));
    }

    @Test
    void propertiesFor_jsr223PreAndPostProcessor_includeScriptAndLanguage() {
        for (String type : new String[] { "JSR223PreProcessor", "JSR223PostProcessor" }) {
            List<ElementPropertyCatalog.Property> props = ElementPropertyCatalog.propertiesFor(type);
            assertFalse(props.isEmpty(), type + " should have curated properties");
            assertTrue(props.stream().anyMatch(p -> p.getKey().equals("script")));
            assertTrue(props.stream().anyMatch(p -> p.getKey().equals("scriptLanguage")));
        }
    }

    @Test
    void propertiesFor_durationAssertion_includesDurationKey() {
        List<ElementPropertyCatalog.Property> props = ElementPropertyCatalog.propertiesFor("DurationAssertion");
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("DurationAssertion.duration")));
    }

    @Test
    void propertiesFor_sizeAssertion_includesSizeAndOperator() {
        List<ElementPropertyCatalog.Property> props = ElementPropertyCatalog.propertiesFor("SizeAssertion");
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("SizeAssertion.size")));
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("SizeAssertion.operator")));
    }

    @Test
    void propertiesFor_jsonPathAssertion_includesJsonPathAndExpectedValue() {
        List<ElementPropertyCatalog.Property> props = ElementPropertyCatalog.propertiesFor("JSONPathAssertion");
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("JSON_PATH")));
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("EXPECTED_VALUE")));
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("ISREGEX")));
    }

    @Test
    void propertiesFor_gaussianRandomTimer_includesDelayAndRange() {
        List<ElementPropertyCatalog.Property> props = ElementPropertyCatalog.propertiesFor("GaussianRandomTimer");
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("ConstantTimer.delay")));
        assertTrue(props.stream().anyMatch(p -> p.getKey().equals("RandomTimer.range")));
    }

    // ==================== B5: allowed values for enum-like keys ====================

    @Test
    void allowedValues_defaultsToEmptyForFreeFormProperties() {
        ElementPropertyCatalog.Property domain = findProperty("HTTPSamplerProxy", "HTTPSampler.domain");
        assertTrue(domain.getAllowedValues().isEmpty());
    }

    @Test
    void allowedValues_httpMethod_listsStandardVerbs() {
        ElementPropertyCatalog.Property method = findProperty("HTTPSamplerProxy", "HTTPSampler.method");
        assertEquals(
                java.util.Arrays.asList("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "TRACE", "PATCH"),
                method.getAllowedValues());
    }

    @Test
    void allowedValues_httpProtocol_isHttpOrHttps() {
        ElementPropertyCatalog.Property protocol = findProperty("HTTPSamplerProxy", "HTTPSampler.protocol");
        assertEquals(java.util.Arrays.asList("http", "https"), protocol.getAllowedValues());
    }

    @Test
    void allowedValues_csvShareMode_usesLiteralShareModeStrings() {
        ElementPropertyCatalog.Property shareMode = findProperty("CSVDataSet", "shareMode");
        assertEquals(
                java.util.Arrays.asList("shareMode.all", "shareMode.group", "shareMode.thread"),
                shareMode.getAllowedValues());
    }

    @Test
    void allowedValues_responseAssertionTestType_includesMatchAndOrModifier() {
        ElementPropertyCatalog.Property testType = findProperty("ResponseAssertion", "Assertion.test_type");
        assertTrue(testType.getAllowedValues().stream().anyMatch(v -> v.contains("1") && v.contains("Matches")));
        assertTrue(testType.getAllowedValues().stream().anyMatch(v -> v.contains("32") && v.contains("Or")));
    }

    @Test
    void allowedValues_responseAssertionTestField_includesAllEightFields() {
        ElementPropertyCatalog.Property testField = findProperty("ResponseAssertion", "Assertion.test_field");
        assertEquals(8, testField.getAllowedValues().size());
        assertTrue(testField.getAllowedValues().contains("Assertion.sample_label"));
    }

    @Test
    void allowedValues_constantThroughputTimerCalcMode_hasFiveModes() {
        ElementPropertyCatalog.Property calcMode = findProperty("ConstantThroughputTimer", "calcMode");
        assertEquals(5, calcMode.getAllowedValues().size());
    }

    @Test
    void describe_rendersAllowedValuesLine() {
        String text = ElementPropertyCatalog.describe("HTTPSamplerProxy");
        assertTrue(text.contains("Allowed: GET, POST, PUT, DELETE, HEAD, OPTIONS, TRACE, PATCH."));
    }

    @Test
    void describe_omitsAllowedValuesLineForFreeFormProperties() {
        String text = ElementPropertyCatalog.describe("DurationAssertion");
        assertFalse(text.contains("Allowed:"));
    }

    // ==================== isFlatStringListProperty ====================

    @Test
    void isFlatStringListProperty_responseAssertionTestStrings_isTrue() {
        assertTrue(ElementPropertyCatalog.isFlatStringListProperty("ResponseAssertion", "Asserion.test_strings"));
    }

    @Test
    void isFlatStringListProperty_isCaseInsensitiveOnType() {
        assertTrue(ElementPropertyCatalog.isFlatStringListProperty("responseassertion", "Asserion.test_strings"));
    }

    @Test
    void isFlatStringListProperty_unknownPropertyOrType_isFalse() {
        assertFalse(ElementPropertyCatalog.isFlatStringListProperty("ResponseAssertion", "Assertion.test_field"));
        assertFalse(ElementPropertyCatalog.isFlatStringListProperty("HeaderManager", "HeaderManager.headers"));
        assertFalse(ElementPropertyCatalog.isFlatStringListProperty(null, "Asserion.test_strings"));
        assertFalse(ElementPropertyCatalog.isFlatStringListProperty("ResponseAssertion", null));
    }

    // ==================== isStructuredListProperty ====================

    @Test
    void isStructuredListProperty_headerManagerHeaders_isTrue() {
        assertTrue(ElementPropertyCatalog.isStructuredListProperty("HeaderManager", "HeaderManager.headers"));
    }

    @Test
    void isStructuredListProperty_argumentsArguments_isTrue() {
        assertTrue(ElementPropertyCatalog.isStructuredListProperty("Arguments", "Arguments.arguments"));
    }

    @Test
    void isStructuredListProperty_authManagerAuthList_isTrue() {
        assertTrue(ElementPropertyCatalog.isStructuredListProperty("AuthManager", "AuthManager.auth_list"));
    }

    @Test
    void isStructuredListProperty_isCaseInsensitiveOnType() {
        assertTrue(ElementPropertyCatalog.isStructuredListProperty("headermanager", "HeaderManager.headers"));
    }

    @Test
    void isStructuredListProperty_unknownPropertyOrType_isFalse() {
        assertFalse(ElementPropertyCatalog.isStructuredListProperty("HeaderManager", "Arguments.arguments"));
        assertFalse(ElementPropertyCatalog.isStructuredListProperty("ResponseAssertion", "Asserion.test_strings"));
        assertFalse(ElementPropertyCatalog.isStructuredListProperty(null, "HeaderManager.headers"));
        assertFalse(ElementPropertyCatalog.isStructuredListProperty("HeaderManager", null));
    }

    @Test
    void propertiesFor_headerManagerAndArgumentsAndAuthManager_areCurated() {
        assertEquals("HeaderManager.headers", findProperty("HeaderManager", "HeaderManager.headers").getKey());
        assertEquals("Arguments.arguments", findProperty("Arguments", "Arguments.arguments").getKey());
        assertEquals("AuthManager.auth_list", findProperty("AuthManager", "AuthManager.auth_list").getKey());
    }

    private static ElementPropertyCatalog.Property findProperty(String type, String key) {
        return ElementPropertyCatalog.propertiesFor(type).stream()
                .filter(p -> p.getKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No property " + key + " for type " + type));
    }
}
