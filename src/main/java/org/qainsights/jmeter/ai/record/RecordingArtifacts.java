package org.qainsights.jmeter.ai.record;

/**
 * Paths to the various files produced during a recording session.
 */
public record RecordingArtifacts(
    String harPath,
    String stepMarkersPath,
    String recordXmlPath,
    String generatedJmxPath,
    String correlatedJmxPath
) {}
