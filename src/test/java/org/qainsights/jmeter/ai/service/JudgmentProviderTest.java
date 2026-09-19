package org.qainsights.jmeter.ai.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JudgmentProviderTest {

    @Test
    void choiceAnswerCopiesProbabilitiesAndReadsNamedProbability() {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("EDIT", 0.8);
        JudgmentProvider.ChoiceAnswer answer = new JudgmentProvider.ChoiceAnswer("EDIT", probabilities, 0.7);
        probabilities.put("EDIT", 0.1);

        assertEquals("EDIT", answer.choice());
        assertEquals(0.8, answer.probabilityOf("EDIT"));
        assertEquals(0.0, answer.probabilityOf("MISSING"));
        assertThrows(UnsupportedOperationException.class, () -> answer.probabilities().put("RUN", 0.2));
    }

    @Test
    void choiceAnswerTreatsNullProbabilitiesAsEmpty() {
        JudgmentProvider.ChoiceAnswer answer = new JudgmentProvider.ChoiceAnswer("EDIT", null, 0.5);

        assertEquals(Map.of(), answer.probabilities());
    }
}
