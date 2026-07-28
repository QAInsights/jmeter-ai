package org.qainsights.jmeter.ai.record;

/**
 * Marker for transaction start/end boundaries in the step-markers.json file.
 */
public record StepMarker(
    String name,
    String type,
    long timestamp
) {}
