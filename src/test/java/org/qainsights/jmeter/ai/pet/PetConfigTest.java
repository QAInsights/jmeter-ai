package org.qainsights.jmeter.ai.pet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PetConfig}.
 */
class PetConfigTest {

    @Test
    void should_useDefaults_when_valuesAreMissing() {
        PetConfig config = PetConfig.parse(null, null, null);
        assertFalse(config.isEnabled());
        assertEquals("quill", config.getPetName());
        assertEquals(0.5, config.getScale());
    }

    @Test
    void should_parseEnabledFlag_when_trueProvided() {
        assertTrue(PetConfig.parse("true", "glim", "0.5").isEnabled());
        assertTrue(PetConfig.parse(" TRUE ", "glim", "0.5").isEnabled());
        assertFalse(PetConfig.parse("false", "glim", "0.5").isEnabled());
        assertFalse(PetConfig.parse("yes", "glim", "0.5").isEnabled());
    }

    @Test
    void should_acceptAllAllowedPets_when_nameIsAllowed() {
        for (String name : PetConfig.ALLOWED_PETS) {
            assertEquals(name, PetConfig.parse("true", name, "0.5").getPetName());
        }
    }

    @Test
    void should_normalizeCaseAndWhitespace_when_parsingName() {
        assertEquals("monkey", PetConfig.parse("true", "  Monkey ", "0.5").getPetName());
        assertEquals("peacock", PetConfig.parse("true", "PEACOCK", "0.5").getPetName());
    }

    @Test
    void should_fallBackToDefaultPet_when_nameIsUnknown() {
        assertEquals(PetConfig.DEFAULT_PET, PetConfig.parse("true", "dragon", "0.5").getPetName());
        assertEquals(PetConfig.DEFAULT_PET, PetConfig.parse("true", "", "0.5").getPetName());
    }

    @Test
    void should_parseScale_when_validNumberProvided() {
        assertEquals(1.0, PetConfig.parse("true", "glim", "1.0").getScale());
        assertEquals(0.75, PetConfig.parse("true", "glim", " 0.75 ").getScale());
    }

    @Test
    void should_clampScale_when_outOfRange() {
        assertEquals(PetConfig.MIN_SCALE, PetConfig.parse("true", "glim", "0.01").getScale());
        assertEquals(PetConfig.MAX_SCALE, PetConfig.parse("true", "glim", "10").getScale());
    }

    @Test
    void should_fallBackToDefaultScale_when_scaleIsInvalid() {
        assertEquals(PetConfig.DEFAULT_SCALE, PetConfig.parse("true", "glim", "big").getScale());
        assertEquals(PetConfig.DEFAULT_SCALE, PetConfig.parse("true", "glim", null).getScale());
    }

    @Test
    void should_readFromJMeterProperties_when_noPropertiesSet() {
        PetConfig config = PetConfig.fromJMeterProperties();
        assertFalse(config.isEnabled());
        assertEquals("quill", config.getPetName());
    }
}
