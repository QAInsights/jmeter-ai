package org.qainsights.jmeter.ai.fix;

import java.util.List;

import org.apache.jmeter.config.ConfigTestElement;
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

    @Test
    void ignoresDuplicateSamplerLabelsAndInconsistentObservedTitles() {
        HashTree duplicateTree = plan();
        duplicateTree.add(sampler("Catalog"));
        duplicateTree.add(sampler("Catalog"));
        assertTrue(FixAssertions.candidates(duplicateTree, List.of(
                observation("Catalog", "200", "text/html", "Catalog", null))).isEmpty());

        HashTree uniqueTree = plan().add(sampler("Catalog"));
        assertTrue(FixAssertions.candidates(uniqueTree, List.of(
                observation("Catalog", "200", "text/html", "Catalog", null),
                observation("Catalog", "200", "text/html", "Other", null))).isEmpty());
    }

    @Test
    void skipsParameterizedAndFunctionBasedTitles() {
        HashTree tree = plan();
        tree.add(sampler("Variable"));
        tree.add(sampler("Function"));

        assertTrue(FixAssertions.candidates(tree, List.of(
                observation("Variable", "200", "text/html", "${pageTitle}", null),
                observation("Function", "200", "text/html", "Page __UUID()", null))).isEmpty());
    }

    @Test
    void removalTargetsOnlyOurNamedPageChecksOnRequestedSamplerLabels() {
        ResponseAssertion ours = new ResponseAssertion();
        ours.setName("Make it pass: page check");
        ResponseAssertion other = new ResponseAssertion();
        other.setName("Existing check");

        assertTrue(FixAssertions.shouldRemoveAddedAssertion("Catalog", List.of("Catalog"), ours));
        assertFalse(FixAssertions.shouldRemoveAddedAssertion("Catalog", List.of("Other"), ours));
        assertFalse(FixAssertions.shouldRemoveAddedAssertion("Catalog", List.of("Catalog"), other));
        ConfigTestElement notAssertion = new ConfigTestElement();
        notAssertion.setName("Make it pass: page check");
        assertFalse(FixAssertions.shouldRemoveAddedAssertion("Catalog", List.of("Catalog"), notAssertion));
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
