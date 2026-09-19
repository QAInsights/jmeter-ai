package org.qainsights.jmeter.ai.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface AgentRequestRouter {

    enum Route {
        INSPECT_EXPLAIN("Inspect and explain"),
        EDIT_TEST_PLAN("Edit test plan"),
        RUN_DIAGNOSE("Run and diagnose"),
        CORRELATE("Correlation workflow"),
        PLAN_FILES("Plan files"),
        COMPLEX_OR_UNCLEAR("Complex or unclear");

        private final String displayName;

        Route(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    enum Outcome {
        FOCUSED,
        ALL_TOOLS,
        UNAVAILABLE
    }

    record Decision(Route route, Map<String, Double> probabilities, double confidence, Outcome outcome) {
        public Decision {
            route = route == null ? Route.COMPLEX_OR_UNCLEAR : route;
            probabilities = probabilities == null
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(probabilities));
            outcome = outcome == null ? Outcome.UNAVAILABLE : outcome;
        }

        public static Decision focused(Route route, Map<String, Double> probabilities, double confidence) {
            return new Decision(route, probabilities, confidence, Outcome.FOCUSED);
        }

        public static Decision allTools(Route route, Map<String, Double> probabilities, double confidence) {
            return new Decision(route, probabilities, confidence, Outcome.ALL_TOOLS);
        }

        public static Decision unavailable() {
            return new Decision(Route.COMPLEX_OR_UNCLEAR, Map.of(), 0, Outcome.UNAVAILABLE);
        }

        public boolean isFocused() {
            return outcome == Outcome.FOCUSED;
        }
    }

    record Notice(Decision decision, List<String> toolNames, int totalToolCount,
                  List<String> addedToolNames) {
        public Notice {
            decision = decision == null ? Decision.unavailable() : decision;
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
            totalToolCount = Math.max(totalToolCount, toolNames.size());
            addedToolNames = addedToolNames == null ? List.of() : List.copyOf(addedToolNames);
        }

        public Notice(Decision decision, List<String> toolNames, int totalToolCount) {
            this(decision, toolNames, totalToolCount, List.of());
        }

        /** A notice for a mid-run tool-set expansion listing the newly added tools. */
        public static Notice expansion(Decision decision, List<String> toolNames,
                                       int totalToolCount, List<String> addedToolNames) {
            return new Notice(decision, toolNames, totalToolCount, addedToolNames);
        }

        /** True when this notice describes a mid-run expansion rather than the initial route. */
        public boolean isExpansion() {
            return !addedToolNames.isEmpty();
        }
    }

    Decision route(String userMessage);
}
