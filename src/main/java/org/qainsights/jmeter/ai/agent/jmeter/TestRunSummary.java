package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.qainsights.jmeter.ai.fix.SoftFailureDetector;
import org.qainsights.jmeter.ai.utils.SafeText;

/**
 * Immutable summary of a {@link TestPlanRunner} run: aggregate pass/fail counts plus a
 * capped list of failure details, so {@code get_test_results} can report something
 * directly useful to the agent without dumping every raw {@link SampleResult}.
 */
public final class TestRunSummary {

    /** Max number of individual failures kept in {@link #getFailures()}; later ones are only counted. */
    public static final int MAX_FAILURES_KEPT = 20;
    public static final int MAX_OBSERVATIONS_KEPT = 500;
    public static final int MAX_RESPONSE_EXCERPT_CHARS = 2048;
    private static final int MAX_RESPONSE_REDACTION_CHARS = 8192;

    private final int totalSamples;
    private final int successCount;
    private final int failureCount;
    private final boolean timedOut;
    private final long elapsedMillis;
    private final List<Failure> failures;
    private final List<Observation> observations;

    private TestRunSummary(int totalSamples, int successCount, int failureCount, boolean timedOut,
                            long elapsedMillis, List<Failure> failures, List<Observation> observations) {
        this.totalSamples = totalSamples;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.timedOut = timedOut;
        this.elapsedMillis = elapsedMillis;
        this.failures = Collections.unmodifiableList(failures);
        this.observations = Collections.unmodifiableList(observations);
    }

    /** Builds a summary by aggregating the raw sample results collected during a run. */
    public static TestRunSummary of(List<SampleResult> results, boolean timedOut, long elapsedMillis) {
        int success = 0;
        int failure = 0;
        for (SampleResult result : results) {
            if (result.isSuccessful() && SoftFailureDetector.reason(result) == null) {
                success++;
            } else {
                failure++;
            }
        }
        return of(results, results.size(), success, failure, timedOut, elapsedMillis);
    }

    /** Builds a summary with aggregate counts from every sample and details from retained samples. */
    public static TestRunSummary of(List<SampleResult> retainedResults, int totalSamples, int successCount,
                                    int failureCount, boolean timedOut, long elapsedMillis) {
        List<Failure> failures = new ArrayList<>();
        List<Observation> observations = new ArrayList<>();
        for (SampleResult result : retainedResults) {
            String softFailure = SoftFailureDetector.reason(result);
            if (observations.size() < MAX_OBSERVATIONS_KEPT) {
                String url = result instanceof HTTPSampleResult && result.getURL() != null
                        ? SafeText.redactUrls(result.getURL().toExternalForm()) : "";
                observations.add(new Observation(result.getSampleLabel(), result.getResponseCode(), url,
                        SoftFailureDetector.titleText(result), result.getContentType(), softFailure,
                        !result.isSuccessful() || softFailure != null));
            }
            if (!result.isSuccessful() || softFailure != null) {
                if (failures.size() < MAX_FAILURES_KEPT) {
                    String method = "";
                    String url = "";
                    if (result instanceof HTTPSampleResult) {
                        method = ((HTTPSampleResult) result).getHTTPMethod();
                        if (result.getURL() != null) {
                            url = SafeText.redactUrls(result.getURL().toExternalForm());
                        }
                    }
                    String response = result.getResponseDataAsString();
                    if (response != null && response.length() > MAX_RESPONSE_REDACTION_CHARS) {
                        response = response.substring(0, MAX_RESPONSE_REDACTION_CHARS);
                    }
                    String excerpt = SafeText.redactSensitiveValues(response);
                    if (excerpt == null) {
                        excerpt = "";
                    } else if (excerpt.length() > MAX_RESPONSE_EXCERPT_CHARS) {
                        excerpt = excerpt.substring(0, MAX_RESPONSE_EXCERPT_CHARS);
                    }
                    String message = softFailure == null ? SafeText.redactUrls(result.getResponseMessage())
                            : "Soft failure: " + softFailure;
                    failures.add(new Failure(result.getSampleLabel(), result.getResponseCode(),
                            message, method, url, excerpt));
                }
            }
        }
        return new TestRunSummary(totalSamples, successCount, failureCount, timedOut, elapsedMillis,
                failures, observations);
    }

    public int getTotalSamples() {
        return totalSamples;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    /** Up to {@link #MAX_FAILURES_KEPT} failed samples; see {@link #hasMoreFailuresThanShown()}. */
    public List<Failure> getFailures() {
        return failures;
    }

    public List<Observation> getObservations() {
        return observations;
    }

    /** True when there were more failures than fit in {@link #getFailures()}. */
    public boolean hasMoreFailuresThanShown() {
        return failureCount > failures.size();
    }

    /** A single failed sample's label, response code and message. */
    public static final class Failure {
        private final String label;
        private final String responseCode;
        private final String message;
        private final String method;
        private final String url;
        private final String responseExcerpt;

        public Failure(String label, String responseCode, String message) {
            this(label, responseCode, message, "", "", "");
        }

        public Failure(String label, String responseCode, String message, String method, String url,
                       String responseExcerpt) {
            this.label = label == null ? "" : label;
            this.responseCode = responseCode == null ? "" : responseCode;
            this.message = message == null ? "" : message;
            this.method = method == null ? "" : method;
            this.url = url == null ? "" : url;
            this.responseExcerpt = responseExcerpt == null ? "" : responseExcerpt;
        }

        public String getLabel() {
            return label;
        }

        public String getResponseCode() {
            return responseCode;
        }

        public String getMessage() {
            return message;
        }

        public String getMethod() {
            return method;
        }

        public String getUrl() {
            return url;
        }

        public String getResponseExcerpt() {
            return responseExcerpt;
        }
    }

    public static final class Observation {
        private final String label;
        private final String responseCode;
        private final String finalUrl;
        private final String title;
        private final String contentType;
        private final String softFailureReason;
        private final boolean failure;

        public Observation(String label, String responseCode, String finalUrl, String title,
                           String contentType, String softFailureReason) {
            this(label, responseCode, finalUrl, title, contentType, softFailureReason,
                    softFailureReason != null || isErrorCode(responseCode));
        }

        public Observation(String label, String responseCode, String finalUrl, String title,
                           String contentType, String softFailureReason, boolean failure) {
            this.label = label == null ? "" : label;
            this.responseCode = responseCode == null ? "" : responseCode;
            this.finalUrl = finalUrl == null ? "" : finalUrl;
            this.title = title == null ? "" : title;
            this.contentType = contentType == null ? "" : contentType;
            this.softFailureReason = softFailureReason;
            this.failure = failure;
        }

        public String getLabel() { return label; }
        public String getResponseCode() { return responseCode; }
        public String getFinalUrl() { return finalUrl; }
        public String getTitle() { return title; }
        public String getContentType() { return contentType; }
        public String getSoftFailureReason() { return softFailureReason; }
        public boolean isFailure() { return failure; }

        private static boolean isErrorCode(String responseCode) {
            try {
                int code = Integer.parseInt(responseCode);
                return code < 200 || code >= 400;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }
}
