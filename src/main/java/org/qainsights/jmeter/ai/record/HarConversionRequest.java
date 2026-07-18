package org.qainsights.jmeter.ai.record;

/**
 * Request details for converting a HAR file to JMeter XML format.
 */
public record HarConversionRequest(
    String harPath,
    String stepMarkersPath,
    String recordXmlPath,
    String jmxOutputPath,
    String baseUri
) {}
