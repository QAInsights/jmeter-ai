package org.qainsights.jmeter.ai.service;

import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Answer to a score question: {@code score} is the probability-weighted position on the
     * ordered levels (0 = first level), {@code confidence} how concentrated that distribution is.
     */
    record ScoreAnswer(double score, double confidence) {
        /** Index of the nearest level, clamped to {@code [0, levelCount - 1]}. */
        public int level(int levelCount) {
            return (int) Math.max(0, Math.min(levelCount - 1, Math.round(score)));
        }
    }

    Set<Capability> capabilities();

    ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria);

    /**
     * Places {@code state} on an ordered scale. {@code levels} maps each level label to its
     * description, lowest first. Only called when {@link #capabilities()} contains SCORE.
     */
    default ScoreAnswer score(Object state, String instructions, Map<String, String> levels) {
        throw new UnsupportedOperationException("This judgment provider does not support score questions");
    }

    /** Shared validation for score answers: finite, on the scale, confidence in [0, 1]. */
    static boolean validScore(double score, double confidence, int levelCount) {
        return Double.isFinite(score) && score >= 0 && score <= levelCount - 1
                && Double.isFinite(confidence) && confidence >= 0 && confidence <= 1;
    }

    /** Level descriptions as an ordered list, lowest first. */
    static List<String> levelDescriptions(Map<String, String> levels) {
        return List.copyOf(levels.values());
    }
}
