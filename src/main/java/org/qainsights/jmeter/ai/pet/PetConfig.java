package org.qainsights.jmeter.ai.pet;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable pet settings parsed from JMeter properties:
 * <ul>
 *   <li>{@code jmeter.pet.enable} - feature gate, default {@code false}</li>
 *   <li>{@code jmeter.pet.name} - one of {@link #ALLOWED_PETS}, default {@code quill}</li>
 *   <li>{@code jmeter.pet.scale} - render scale, default {@code 0.5}, clamped to [0.25, 2.0]</li>
 * </ul>
 * Invalid values never fail: they log a warning and fall back to defaults.
 */
public final class PetConfig {
    private static final Logger log = LoggerFactory.getLogger(PetConfig.class);

    public static final List<String> ALLOWED_PETS =
            Arrays.asList("quill", "feather", "monkey", "parrot", "peacock", "robot");
    public static final String DEFAULT_PET = "quill";
    public static final double DEFAULT_SCALE = 0.5;
    public static final double MIN_SCALE = 0.25;
    public static final double MAX_SCALE = 2.0;

    private final boolean enabled;
    private final String petName;
    private final double scale;

    private PetConfig(boolean enabled, String petName, double scale) {
        this.enabled = enabled;
        this.petName = petName;
        this.scale = scale;
    }

    /** Reads the pet configuration from the live JMeter properties. */
    public static PetConfig fromJMeterProperties() {
        return parse(
                AiConfig.getProperty("jmeter.pet.enable", "false"),
                AiConfig.getProperty("jmeter.pet.name", DEFAULT_PET),
                AiConfig.getProperty("jmeter.pet.scale", String.valueOf(DEFAULT_SCALE)));
    }

    /** Parses raw property values, falling back to safe defaults on invalid input. */
    static PetConfig parse(String enabledRaw, String nameRaw, String scaleRaw) {
        boolean enabled = Boolean.parseBoolean(enabledRaw == null ? "" : enabledRaw.trim());
        return new PetConfig(enabled, parseName(nameRaw), parseScale(scaleRaw));
    }

    private static String parseName(String raw) {
        String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (ALLOWED_PETS.contains(name)) {
            return name;
        }
        if (!name.isEmpty()) {
            log.warn("Unknown jmeter.pet.name '{}'; allowed values are {}. Falling back to '{}'.",
                    raw, ALLOWED_PETS, DEFAULT_PET);
        }
        return DEFAULT_PET;
    }

    private static double parseScale(String raw) {
        try {
            double scale = Double.parseDouble(raw == null ? "" : raw.trim());
            return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        } catch (NumberFormatException e) {
            log.warn("Invalid jmeter.pet.scale '{}'; falling back to {}.", raw, DEFAULT_SCALE);
            return DEFAULT_SCALE;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getPetName() {
        return petName;
    }

    public double getScale() {
        return scale;
    }
}
