package org.qainsights.jmeter.ai.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local smart routing: embeds the request and compares it with example requests for each
 * tool pack (nearest example wins). Scores are turned into probabilities with a softmax, so
 * the usual confidence threshold applies; low confidence or any embedding failure keeps all
 * tools, exactly like the remote Decisions backends.
 */
public final class EmbeddingAgentRequestRouter implements AgentRequestRouter {

    @FunctionalInterface
    public interface Embedder {
        List<float[]> embed(List<String> inputs);
    }

    private static final Logger log = LoggerFactory.getLogger(EmbeddingAgentRequestRouter.class);

    static final String PREFIX = "task: classification | query: ";
    static final double TEMPERATURE = 0.05;
    private static final Map<Route, List<String>> EXAMPLES = examples();

    private final Embedder embedder;
    private final double minConfidence;
    private List<Route> exampleRoutes;
    private List<float[]> exampleVectors;

    public EmbeddingAgentRequestRouter(Embedder embedder, double minConfidence) {
        this.embedder = embedder;
        this.minConfidence = Math.max(0, Math.min(1, minConfidence));
    }

    @Override
    public Decision route(String userMessage) {
        if (embedder == null) {
            return Decision.unavailable();
        }
        try {
            String message = TypeSafeAgentRequestRouter.sanitize(userMessage);
            if (message.isEmpty()) {
                return Decision.allTools(Route.COMPLEX_OR_UNCLEAR, Map.of(), 0);
            }
            List<float[]> examples = exampleVectors();
            List<float[]> query = embedder.embed(List.of(PREFIX + message));
            if (query == null || query.size() != 1) {
                throw new IllegalStateException("no embedding was returned for the request");
            }
            float[] q = normalize(query.get(0));
            Map<Route, Double> best = new EnumMap<>(Route.class);
            for (int i = 0; i < examples.size(); i++) {
                best.merge(exampleRoutes.get(i), dot(q, examples.get(i)), Math::max);
            }
            return decide(best);
        } catch (RuntimeException e) {
            log.warn("{} smart routing unavailable: {}", AgentJudgmentConfig.brand(), e.getMessage());
            return Decision.unavailable();
        }
    }

    private Decision decide(Map<Route, Double> scores) {
        double max = Collections.max(scores.values());
        double total = 0;
        Map<Route, Double> weights = new EnumMap<>(Route.class);
        for (Map.Entry<Route, Double> entry : scores.entrySet()) {
            double weight = Math.exp((entry.getValue() - max) / TEMPERATURE);
            weights.put(entry.getKey(), weight);
            total += weight;
        }
        Map<String, Double> probabilities = new LinkedHashMap<>();
        Route top = null;
        double confidence = -1;
        for (Map.Entry<Route, Double> entry : weights.entrySet()) {
            double p = entry.getValue() / total;
            probabilities.put(entry.getKey().name(), p);
            if (p > confidence) {
                confidence = p;
                top = entry.getKey();
            }
        }
        return confidence < minConfidence
                ? Decision.allTools(top, probabilities, confidence)
                : Decision.focused(top, probabilities, confidence);
    }

    /** Embeds the examples once; a failure is not cached, so the next request retries. */
    private synchronized List<float[]> exampleVectors() {
        if (exampleVectors != null) {
            return exampleVectors;
        }
        List<Route> routes = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        EXAMPLES.forEach((route, phrases) -> phrases.forEach(phrase -> {
            routes.add(route);
            texts.add(PREFIX + phrase);
        }));
        List<float[]> vectors = embedder.embed(texts);
        if (vectors == null || vectors.size() != texts.size()) {
            throw new IllegalStateException("expected " + texts.size() + " example embeddings, got "
                    + (vectors == null ? 0 : vectors.size()));
        }
        List<float[]> normalized = new ArrayList<>(vectors.size());
        vectors.forEach(v -> normalized.add(normalize(v)));
        exampleRoutes = List.copyOf(routes);
        exampleVectors = List.copyOf(normalized);
        return exampleVectors;
    }

    static Map<Route, List<String>> routeExamples() {
        return EXAMPLES;
    }

    private static float[] normalize(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("empty embedding");
        }
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        if (!(sum > 0) || !Double.isFinite(sum)) {
            throw new IllegalStateException("invalid embedding");
        }
        double norm = Math.sqrt(sum);
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / norm);
        }
        return out;
    }

    private static double dot(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalStateException("embedding sizes differ (" + a.length + " vs " + b.length
                    + "); was jmeter.ai.embeddings.model changed while JMeter was running?");
        }
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    private static Map<Route, List<String>> examples() {
        Map<String, String> criteria = TypeSafeAgentRequestRouter.routeCriteria();
        Map<Route, List<String>> values = new EnumMap<>(Route.class);
        values.put(Route.INSPECT_EXPLAIN, List.of("what does this plan do", "describe the thread group settings",
                "list all samplers in the plan", "what timeout is set on the http request",
                "explain the assertions in this plan", "show the configuration of the csv data set",
                "which elements are under the thread group", "what is the base url used"));
        values.put(Route.EDIT_TEST_PLAN, List.of("add a response assertion", "change the number of users to 100",
                "remove the view results tree listener", "move the timer above the sampler",
                "add a thread group with 10 users", "update the request body", "enable the login sampler",
                "insert a csv data set config", "set the ramp up to 60 seconds", "add a cookie manager"));
        values.put(Route.RUN_DIAGNOSE, List.of("run it", "start the load test", "kill the current run",
                "run once and show failures", "what errors did the test produce",
                "check the latency of the last execution", "why did the sampler fail", "run a single iteration"));
        values.put(Route.CORRELATE, List.of("handle the dynamic session id",
                "extract the access token and pass it to the next call", "find values that need correlation",
                "the viewstate changes every time", "apply a regex extractor for the order id",
                "auto correlate this recording"));
        values.put(Route.PLAN_FILES, List.of("save my work", "save to file", "open a jmx file",
                "load the plan from disk", "save as a new jmx", "open the recorded script"));
        Map<Route, List<String>> withCriteria = new EnumMap<>(Route.class);
        values.forEach((route, phrases) -> {
            List<String> all = new ArrayList<>(phrases);
            all.add(criteria.get(route.name()));
            withCriteria.put(route, List.copyOf(all));
        });
        return Collections.unmodifiableMap(withCriteria);
    }
}
