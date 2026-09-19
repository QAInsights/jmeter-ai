package org.qainsights.jmeter.ai.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.qainsights.jmeter.ai.service.JudgmentProvider;

public final class TypeSafeAgentRequestRouter implements AgentRequestRouter {

    static final int MAX_MESSAGE_CHARS = 4000;
    static final int MAX_SANITIZE_INPUT_CHARS = 50000;

    private static final Pattern ATTACHED_BLOCK = Pattern.compile(
            "(?s)<attached\\s+file=\"([^\"]*)\"[^>]*>.*?</attached>");
    private static final Pattern FILE_MARKER = Pattern.compile("\\[file:[A-Za-z0-9]+]");
    private static final String INSTRUCTIONS =
            "Choose the single best initial JMeter Agent Mode tool family for the current user request. "
                    + "Choose COMPLEX_OR_UNCLEAR when the request spans tool families, needs several stages, "
                    + "does not require JMeter tools, or lacks enough information for a safe focused route.";
    private static final Map<String, String> CRITERIA = criteria();

    private final JudgmentProvider provider;
    private final double minConfidence;

    public TypeSafeAgentRequestRouter(JudgmentProvider provider, double minConfidence) {
        this.provider = provider;
        this.minConfidence = Math.max(0, Math.min(1, minConfidence));
    }

    @Override
    public Decision route(String userMessage) {
        if (provider == null || !provider.capabilities().contains(JudgmentProvider.Capability.CHOICE)) {
            return Decision.unavailable();
        }
        try {
            JudgmentProvider.ChoiceAnswer answer = provider.choose(
                    Map.of("message", sanitize(userMessage), "agent_mode", "true"),
                    INSTRUCTIONS, CRITERIA);
            Route route = Route.valueOf(answer.choice());
            if (route == Route.COMPLEX_OR_UNCLEAR || answer.confidence() < minConfidence) {
                return Decision.allTools(route, answer.probabilities(), answer.confidence());
            }
            return Decision.focused(route, answer.probabilities(), answer.confidence());
        } catch (RuntimeException e) {
            return Decision.unavailable();
        }
    }

    static String sanitize(String message) {
        String value = coarseLimit(message);
        Matcher matcher = ATTACHED_BLOCK.matcher(value);
        StringBuffer safe = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(safe, Matcher.quoteReplacement(attachmentLabel(matcher.group(1))));
        }
        matcher.appendTail(safe);
        value = FILE_MARKER.matcher(safe.toString()).replaceAll("[file attached]");
        if (value.length() > MAX_MESSAGE_CHARS) {
            value = value.substring(0, MAX_MESSAGE_CHARS);
        }
        return value.trim();
    }

    static String coarseLimit(String message) {
        String value = message == null ? "" : message;
        if (value.length() <= MAX_SANITIZE_INPUT_CHARS) {
            return value;
        }
        value = value.substring(0, MAX_SANITIZE_INPUT_CHARS);
        int open = value.lastIndexOf("<attached");
        int close = value.lastIndexOf("</attached>");
        return open > close ? value.substring(0, open) + "[file attached]" : value;
    }

    static Map<String, String> routeCriteria() {
        return CRITERIA;
    }

    private static String attachmentLabel(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".jtl") || lower.endsWith(".csv")) {
            return "[JTL attached]";
        }
        if (lower.endsWith(".log")) {
            return "[log attached]";
        }
        return "[file attached]";
    }

    private static Map<String, String> criteria() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(Route.INSPECT_EXPLAIN.name(),
                "Read or explain the current test plan or element configuration without changing or running it.");
        values.put(Route.EDIT_TEST_PLAN.name(),
                "Create, update, toggle, move, duplicate, rename, reorder, or delete test-plan elements.");
        values.put(Route.RUN_DIAGNOSE.name(),
                "Run or stop a test and inspect its resulting pass, failure, or timing information.");
        values.put(Route.CORRELATE.name(),
                "Find dynamic values reused by later requests or apply correlation extractors.");
        values.put(Route.PLAN_FILES.name(), "Open an existing JMX plan or save the current test plan.");
        values.put(Route.COMPLEX_OR_UNCLEAR.name(),
                "The request spans multiple families, requires a multi-stage workflow, needs no JMeter tool, "
                        + "or is too ambiguous for a focused tool set.");
        return Collections.unmodifiableMap(values);
    }
}
