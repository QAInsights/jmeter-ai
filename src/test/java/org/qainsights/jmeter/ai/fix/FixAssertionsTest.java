package org.qainsights.jmeter.ai.fix;

import java.util.List;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary.Observation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FixAssertionsTest {
    @Test
    void selectsSuccessfulHtmlSamplersWithoutAssertions() {
        HTTPSamplerProxy sampler = sampler("Catalog");
        HashTree tree = plan();
        tree.add(sampler);

        List<FixAssertions.Candidate> candidates = FixAssertions.candidates(tree, List.of(
                observation("Catalog", "200", "text/html", "Catalog", null)));

        assertEquals(1, candidates.size());
        assertSame(sampler, candidates.get(0).sampler);
        assertEquals("Catalog", candidates.get(0).title);
    }

    @Test
    void ignoresSoftFailuresGenericTitlesNonHtmlAndNonSuccessResponses() {
        HashTree tree = plan();
        tree.add(sampler("Soft"));
        tree.add(sampler("Generic"));
        tree.add(sampler("Json"));
        tree.add(sampler("Missing"));

        List<FixAssertions.Candidate> candidates = FixAssertions.candidates(tree, List.of(
                observation("Soft", "200", "text/html", "Details", "HTTP error title"),
                observation("Generic", "200", "text/html", "Home", null),
                observation("Json", "200", "application/json", "Payload", null),
                observation("Missing", "404", "text/html", "Missing Page", null)));

        assertTrue(candidates.isEmpty());
    }

    @Test
    void ignoresSamplerThatAlreadyHasAnAssertionChild() {
        HTTPSamplerProxy sampler = sampler("Catalog");
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setName("Existing");
        HashTree tree = plan().add(sampler).add(assertion);

        assertTrue(FixAssertions.candidates(tree, List.of(
                observation("Catalog", "200", "text/html", "Catalog", null))).isEmpty());
    }

    private static HashTree plan() {
        return new ListedHashTree().add(new TestPlan("Test Plan"));
    }

    private static HTTPSamplerProxy sampler(String name) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName(name);
        return sampler;
    }

    private static Observation observation(String label, String code, String contentType,
                                           String title, String softFailure) {
        return new Observation(label, code, "https://example.test/", title, contentType, softFailure);
    }
}
