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
}
