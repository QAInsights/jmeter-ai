package org.qainsights.jmeter.ai.correlation;

import java.util.Objects;

public final class CorrelationCandidate {
    private final String value;
    private final SampleRecord sourceResponse;
    private final SampleRecord targetRequest;
    private final String responseLocation;
    private final String requestLocation;
    private ExtractorSuggestion suggestion;
    private CandidateStatus status;

    public CorrelationCandidate(String value, SampleRecord sourceResponse, SampleRecord targetRequest,
                                String responseLocation, String requestLocation,
                                ExtractorSuggestion suggestion, CandidateStatus status) {
        this.value = Objects.requireNonNullElse(value, "");
        this.sourceResponse = Objects.requireNonNull(sourceResponse, "sourceResponse");
        this.targetRequest = Objects.requireNonNull(targetRequest, "targetRequest");
        this.responseLocation = Objects.requireNonNullElse(responseLocation, "");
        this.requestLocation = Objects.requireNonNullElse(requestLocation, "");
        this.suggestion = Objects.requireNonNull(suggestion, "suggestion");
        this.status = Objects.requireNonNull(status, "status");
    }

    public String getValue() {
        return value;
    }

    public String getVariableName() {
        return suggestion.getVariableName();
    }

    public SampleRecord getSourceResponse() {
        return sourceResponse;
    }

    public SampleRecord getTargetRequest() {
        return targetRequest;
    }

    public String getResponseLocation() {
        return responseLocation;
    }

    public String getRequestLocation() {
        return requestLocation;
    }

    public ExtractorSuggestion getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(ExtractorSuggestion suggestion) {
        this.suggestion = Objects.requireNonNull(suggestion, "suggestion");
    }

    public CandidateStatus getStatus() {
        return status;
    }

    public void setStatus(CandidateStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }
}
