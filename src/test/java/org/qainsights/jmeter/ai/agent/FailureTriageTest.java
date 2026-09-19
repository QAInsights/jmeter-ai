package org.qainsights.jmeter.ai.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary;
import org.qainsights.jmeter.ai.service.JudgmentProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureTriageTest {

    /** Fake Jev provider: replays canned ChoiceAnswers and records the states it saw. */
    private static final class FakeProvider implements JudgmentProvider {
        private final Deque<JudgmentProvider.ChoiceAnswer> answers = new ArrayDeque<>();
        private final List<Object> states = new ArrayList<>();
        private RuntimeException toThrow;

        FakeProvider(JudgmentProvider.ChoiceAnswer... canned) {
            answers.addAll(List.of(canned));
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.CHOICE);
        }

        @Override
        public ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria) {
            states.add(state);
            if (toThrow != null) {
                throw toThrow;
            }
            return answers.isEmpty()
                    ? new ChoiceAnswer("OTHER", Map.of("OTHER", 1.0), 0.9)
                    : answers.removeFirst();
        }
    }

    private static JudgmentProvider.ChoiceAnswer answer(String category, double confidence) {
        return new JudgmentProvider.ChoiceAnswer(category, Map.of(category, confidence), confidence);
    }

    private static TestRunSummary.Failure failure(String label, String code, String message) {
        return new TestRunSummary.Failure(label, code, message);
    }

    @Test
    void classifiesEachFailureAndBuildsNoticeWithDominantCategory() {
        FakeProvider provider = new FakeProvider(
                answer("TIMEOUT", 0.87), answer("TIMEOUT", 0.82), answer("HTTP_SERVER", 0.78));
        FailureTriage triage = new FailureTriage(provider, 0.75, 5);
        List<TestRunSummary.Failure> failures = List.of(
                failure("Login POST", "", "Read timed out"),
                failure("Search GET", "", "Read timed out"),
                failure("Checkout POST", "500", "Internal Server Error"));

        FailureTriage.TriageOutcome outcome = triage.triage(failures, 3);

        assertEquals(3, provider.states.size());
        TriageNotice notice = outcome.notice();
        assertEquals(3, notice.totalFailures());
        assertEquals(3, notice.classifiedFailures());
        assertEquals("TIMEOUT", notice.dominantCategory());
        assertEquals("TIMEOUT", notice.rows().get(0).category());
        assertEquals("HTTP_SERVER", notice.rows().get(2).category());
        assertTrue(outcome.advisory().contains("Timeout×2"));
        assertTrue(outcome.advisory().contains("Login POST"));
        assertTrue(outcome.advisory().contains("87%"));
    }

    @Test
    void deduplicatesIdenticalSignaturesBeforeCallingJev() {
        FakeProvider provider = new FakeProvider(answer("TIMEOUT", 0.9), answer("HTTP_SERVER", 0.8));
        FailureTriage triage = new FailureTriage(provider, 0.75, 5);
        List<TestRunSummary.Failure> failures = List.of(
                failure("Login POST", "", "Read timed out"),
                failure("Login POST", "", "Read timed out"),
                failure("Checkout POST", "500", "boom"));

        TriageNotice notice = triage.triage(failures, 3).notice();

        assertEquals(2, provider.states.size());
        assertEquals(2, notice.rows().size());
        assertEquals(3, notice.totalFailures());
    }

    @Test
    void capsUniqueSignaturesAtMaxFailures() {
        FakeProvider provider = new FakeProvider();
        FailureTriage triage = new FailureTriage(provider, 0.75, 2);
        List<TestRunSummary.Failure> failures = List.of(
                failure("A", "500", "a"), failure("B", "500", "b"), failure("C", "500", "c"));

        triage.triage(failures, 3);

        assertEquals(2, provider.states.size());
    }

    @Test
    void lowConfidenceVerdictsBecomeUnclassified() {
        FakeProvider provider = new FakeProvider(answer("TIMEOUT", 0.30), answer("TIMEOUT", 0.90));
        FailureTriage triage = new FailureTriage(provider, 0.75, 5);
        List<TestRunSummary.Failure> failures = List.of(
                failure("Flaky GET", "", "maybe timeout"), failure("Login POST", "", "Read timed out"));

        TriageNotice notice = triage.triage(failures, 2).notice();

        assertEquals(FailureTriage.UNCLASSIFIED, notice.rows().get(0).category());
        assertEquals("TIMEOUT", notice.rows().get(1).category());
        assertEquals(1, notice.classifiedFailures());
        assertEquals("TIMEOUT", notice.dominantCategory());
    }

    @Test
    void providerExceptionsDegradeToUnclassifiedRows() {
        FakeProvider provider = new FakeProvider();
        provider.toThrow = new IllegalStateException("offline");
        FailureTriage triage = new FailureTriage(provider, 0.75, 5);

        FailureTriage.TriageOutcome outcome = triage.triage(
                List.of(failure("Login POST", "", "x")), 1);

        assertEquals(FailureTriage.UNCLASSIFIED, outcome.notice().rows().get(0).category());
        assertEquals(0, outcome.notice().classifiedFailures());
        assertEquals("", outcome.notice().dominantCategory());
    }

    @Test
    void unknownCategoryFromProviderBecomesUnclassified() {
        FakeProvider provider = new FakeProvider(answer("NOT_A_BUCKET", 0.99));
        FailureTriage triage = new FailureTriage(provider, 0.75, 5);

        TriageNotice notice = triage.triage(List.of(failure("X", "", "x")), 1).notice();

        assertEquals(FailureTriage.UNCLASSIFIED, notice.rows().get(0).category());
    }

    @Test
    void nullProviderOrNoFailuresOrZeroCapReturnNull() {
        FakeProvider provider = new FakeProvider();
        List<TestRunSummary.Failure> failures = List.of(failure("X", "", "x"));

        assertNull(new FailureTriage(null, 0.75, 5).triage(failures, 1));
        assertNull(new FailureTriage(provider, 0.75, 5).triage(List.of(), 0));
        assertNull(new FailureTriage(provider, 0.75, 0).triage(failures, 1));
    }

    @Test
    void stateSendsOnlyLabelCodeAndBoundedMessage() {
        FakeProvider provider = new FakeProvider(answer("HTTP_SERVER", 0.9));
        FailureTriage triage = new FailureTriage(provider, 0.75, 5);
        String longMessage = "x".repeat(FailureTriage.MAX_MESSAGE_CHARS + 100);

        triage.triage(List.of(failure("Login POST", "500", longMessage)), 1);

        @SuppressWarnings("unchecked")
        Map<String, String> state = (Map<String, String>) provider.states.get(0);
        assertEquals(Set.of("label", "response_code", "message"), state.keySet());
        assertEquals("Login POST", state.get("label"));
        assertEquals("500", state.get("response_code"));
        assertEquals(FailureTriage.MAX_MESSAGE_CHARS, state.get("message").length());
    }
}
