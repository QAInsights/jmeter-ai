package org.qainsights.jmeter.ai.record;

/**
 * Interface for converting HAR files to JMeter XML plans.
 */
public interface HarToJmxConverter {
    HarConversionResult convert(HarConversionRequest request) throws RecordingException;
}
