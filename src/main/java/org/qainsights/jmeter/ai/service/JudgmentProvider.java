package org.qainsights.jmeter.ai.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public interface JudgmentProvider {

    enum Capability {
        CHOICE,
        NOUL,
        SCORE
    }

    record ChoiceAnswer(String choice, Map<String, Double> probabilities, double confidence) {
        public ChoiceAnswer {
            probabilities = probabilities == null
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(probabilities));
        }

        public double probabilityOf(String option) {
            return probabilities.getOrDefault(option, 0.0);
        }
    }

    Set<Capability> capabilities();

    ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria);
}
