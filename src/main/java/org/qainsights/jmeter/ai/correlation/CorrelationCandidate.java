package org.qainsights.jmeter.ai.correlation;

import org.apache.jmeter.samplers.AbstractSampler;

import java.util.ArrayList;
import java.util.List;

public class CorrelationCandidate {

    public enum Status { PENDING, APPROVED, REJECTED }

    private String parameterName;
    private String sampleValue;
    private String sourceSamplerName;
    private int sourceSamplerIndex;
    private String extractionPattern;
    private String variableName;
    private String extractorType; // "regex", "json", "boundary"
    private String sourceLocation;
    private final List<String> targetSamplerNames = new ArrayList<>();
    private final List<Integer> targetSamplerIndices = new ArrayList<>();
    private Status status = Status.PENDING;

    public String getParameterName() { return parameterName; }
    public void setParameterName(String v) { this.parameterName = v; }

    public String getSampleValue() { return sampleValue; }
    public void setSampleValue(String v) { this.sampleValue = v; }

    public String getSourceSamplerName() { return sourceSamplerName; }
    public void setSourceSamplerName(String v) { this.sourceSamplerName = v; }

    public int getSourceSamplerIndex() { return sourceSamplerIndex; }
    public void setSourceSamplerIndex(int v) { this.sourceSamplerIndex = v; }

    public String getExtractionPattern() { return extractionPattern; }
    public void setExtractionPattern(String v) { this.extractionPattern = v; }

    public String getVariableName() { return variableName; }
    public void setVariableName(String v) { this.variableName = v; }

    public String getExtractorType() { return extractorType; }
    public void setExtractorType(String v) { this.extractorType = v; }

    public String getSourceLocation() { return sourceLocation; }
    public void setSourceLocation(String v) { this.sourceLocation = v; }

    public List<String> getTargetSamplerNames() { return targetSamplerNames; }
    public void addTargetSamplerName(String v) { this.targetSamplerNames.add(v); }

    public List<Integer> getTargetSamplerIndices() { return targetSamplerIndices; }
    public void addTargetSamplerIndex(int v) { this.targetSamplerIndices.add(v); }

    public int getUsageCount() { return targetSamplerNames.size(); }

    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
}
