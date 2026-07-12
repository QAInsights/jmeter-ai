package org.qainsights.jmeter.ai.agent.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link SchemaGrounding}. */
class SchemaGroundingTest {

    private SchemaGrounding schema;

    @BeforeEach
    void setUp() {
        schema = new SchemaGrounding();
    }

    @Test
    void get_isCaseInsensitive() {
        assertNotNull(schema.get("HTTPSamplerProxy"));
        assertNotNull(schema.get("httpsamplerproxy"));
        assertSame(schema.get("HTTPSamplerProxy"), schema.get("httpsamplerproxy"));
    }

    @Test
    void isKnown_falseForUnknownType() {
        assertFalse(schema.isKnown("NotARealElement"));
        assertFalse(schema.isKnown(null));
    }

    @Test
    void elementType_exposesCategoryAndAddAlias() {
        SchemaGrounding.ElementType http = schema.get("HTTPSamplerProxy");
        assertEquals(SchemaGrounding.Category.SAMPLER, http.getCategory());
        assertEquals("httpsampler", http.getAddAlias());
    }

    @Test
    void threadGroup_validParentsIsTestPlan() {
        assertEquals(Arrays.asList("TestPlan"), schema.validParentsFor("ThreadGroup"));
    }

    @Test
    void constantThroughputTimer_overridesParentsToThreadGroupOnly() {
        assertEquals(Arrays.asList("ThreadGroup"), schema.validParentsFor("ConstantThroughputTimer"));
    }

    @Test
    void constantTimer_usesDefaultTimerParents() {
        assertEquals(Arrays.asList("ThreadGroup", "Controller", "Sampler"),
                schema.validParentsFor("ConstantTimer"));
    }

    @Test
    void validParentsFor_unknownType_isEmpty() {
        assertTrue(schema.validParentsFor("Nope").isEmpty());
    }

    @Test
    void byCategory_returnsMembersOfThatCategory() {
        assertFalse(schema.byCategory(SchemaGrounding.Category.SAMPLER).isEmpty());
        for (SchemaGrounding.ElementType et : schema.byCategory(SchemaGrounding.Category.SAMPLER)) {
            assertEquals(SchemaGrounding.Category.SAMPLER, et.getCategory());
        }
    }

    @Test
    void hierarchySummary_containsCategoriesAndValidParents() {
        String summary = schema.hierarchySummary();
        assertTrue(summary.contains("\"samplers\""));
        assertTrue(summary.contains("\"timers\""));
        assertTrue(summary.contains("HTTPSamplerProxy"));
        assertTrue(summary.contains("valid_parents"));
    }

    @Test
    void schemaFor_knownType_includesKeyDetails() {
        String detail = schema.schemaFor("ConstantThroughputTimer");
        assertNotNull(detail);
        assertTrue(detail.contains("ConstantThroughputTimer"));
        assertTrue(detail.contains("ThreadGroup"));
    }

    @Test
    void schemaFor_unknownType_returnsNull() {
        assertNull(schema.schemaFor("Nope"));
    }

    @Test
    void schemaFor_typeWithCuratedProperties_listsPropertyKeys() {
        String detail = schema.schemaFor("HTTPSamplerProxy");
        assertNotNull(detail);
        assertTrue(detail.contains("Common properties"));
        assertTrue(detail.contains("HTTPSampler.domain"));
        assertTrue(detail.contains("HTTPSampler.method"));
    }

    @Test
    void schemaFor_typeWithoutCuratedProperties_keepsInspectLiveGuidance() {
        String detail = schema.schemaFor("FTPSampler");
        assertNotNull(detail);
        assertTrue(detail.contains("get_element_config"));
    }
}
