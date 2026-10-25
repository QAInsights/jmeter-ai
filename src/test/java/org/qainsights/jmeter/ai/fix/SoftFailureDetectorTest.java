package org.qainsights.jmeter.ai.fix;

import java.net.URL;

import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoftFailureDetectorTest {
    private static HTTPSampleResult html(String title, String body, String finalUrl, String requestedPath)
            throws Exception {
        HTTPSampleResult result = new HTTPSampleResult();
        result.setSuccessful(true);
        result.setResponseCode("200");
        result.setContentType("text/html");
        result.setURL(new URL(finalUrl));
        result.setSamplerData("GET " + requestedPath + " HTTP/1.1");
        result.setResponseData("<html><title>" + title + "</title>" + body + "</html>", "UTF-8");
        return result;
    }

    @Test
    void detectsErrorTitleAndServerErrorBody() throws Exception {
        assertNotNull(SoftFailureDetector.reason(html("Page Not Found", "", "https://shop.test/item", "/item")));
        assertNotNull(SoftFailureDetector.reason(
                html("Store", "HTTP Status 500", "https://shop.test/item", "/item")));
    }

    @Test
    void detectsAuthenticationRedirectButNotSignInLinkText() throws Exception {
        assertNotNull(SoftFailureDetector.reason(
                html("Sign in", "", "https://shop.test/login", "/catalog")));
        assertNull(SoftFailureDetector.reason(
                html("Catalog", "<a href='/login'>Sign In</a>", "https://shop.test/catalog", "/catalog")));
    }

    @Test
    void ignoresNonSuccessfulAndRequestedAuthenticationPages() throws Exception {
        HTTPSampleResult failure = html("Error", "", "https://shop.test/item", "/item");
        failure.setSuccessful(false);
        assertNull(SoftFailureDetector.reason(failure));
        assertNull(SoftFailureDetector.reason(
                html("Sign in", "", "https://shop.test/login", "/login")));
    }

    @Test
    void usesTitleWordBoundariesAndTextContentTypesForBodyFailures() throws Exception {
        assertNull(SoftFailureDetector.reason(
                html("Errorish", "", "https://shop.test/item", "/item")));

        HTTPSampleResult javascript = html("Catalog", "Exception: bundled code", "https://shop.test/app.js", "/app.js");
        javascript.setContentType("application/javascript");
        assertNull(SoftFailureDetector.reason(javascript));

        HTTPSampleResult plainText = html("Catalog", "Exception: server failure", "https://shop.test/status", "/status");
        plainText.setContentType("text/plain; charset=UTF-8");
        assertNotNull(SoftFailureDetector.reason(plainText));
    }

    @Test
    void summaryCountsSoftFailuresAndKeepsBoundedObservations() throws Exception {
        HTTPSampleResult result = html("Invalid request", "", "https://shop.test/item?token=secret", "/item");
        var summary = org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary.of(
                java.util.List.<SampleResult>of(result), false, 0);

        assertEquals(1, summary.getFailureCount());
        assertEquals("Soft failure: page title indicates an error: Invalid request",
                summary.getFailures().get(0).getMessage());
        assertEquals(1, summary.getObservations().size());
        assertFalse(summary.getObservations().get(0).getFinalUrl().contains("secret"));
        assertEquals("Invalid request", summary.getObservations().get(0).getTitle());
    }
}
