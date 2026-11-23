package org.qainsights.jmeter.ai.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Usage and request sizes for one agent run. Null token counts denote unreported fields.
 */
public final class TokenUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageTracker.class);

    private final String provider;
    private final List<Turn> turns = new ArrayList<>();
    private Counts totals = Counts.UNREPORTED;
    private int unreportedTurns;

    public record Counts(Long fresh, Long cacheRead, Long cacheWrite, Long output, Long reasoning) {
        public static final Counts UNREPORTED = new Counts(null, null, null, null, null);

        public Counts plusReported(Counts other) {
            return new Counts(add(fresh, other.fresh), add(cacheRead, other.cacheRead),
                    add(cacheWrite, other.cacheWrite), add(output, other.output), add(reasoning, other.reasoning));
        }

        private Long prompt() {
            return add(add(fresh, cacheRead), cacheWrite);
        }

        private static Long add(Long left, Long right) {
            if (left == null) {
                return right;
            }
            return right == null ? left : Long.valueOf(left + right);
        }
    }

    public record RequestSize(int tools, Integer schemaChars, Integer requestChars) {
        public static RequestSize json(ObjectMapper mapper, Object body, int tools) {
            try {
                JsonNode request = mapper.valueToTree(body);
                Integer schema = tools == 0 ? Integer.valueOf(0) : request.has("tools")
                        ? Integer.valueOf(request.path("tools").toString().length()) : null;
                return new RequestSize(tools, schema,
                        request.toString().length());
            } catch (IllegalArgumentException e) {
                return new RequestSize(tools, null, null);
            }
        }
    }

    private record Turn(RequestSize size, Counts counts, boolean failed) { }

    public TokenUsageTracker(String provider) {
        this.provider = provider;
    }

    public void record(long prompt, long completion) {
        recordTurn(new RequestSize(0, null, null), new Counts(prompt, null, null, completion, null), null);
    }

    /** Records a turn whose response carried no usage block (some gateways strip it). */
    public void recordUnreported() {
        recordTurn(new RequestSize(0, null, null), Counts.UNREPORTED, null);
    }

    public <T> T track(RequestSize request, Supplier<T> send, Function<T, Counts> readUsage) {
        T response;
        try {
            response = send.get();
        } catch (RuntimeException | Error e) {
            recordTurn(request, Counts.UNREPORTED, e.getClass());
            throw e;
        }
        Counts counts = Counts.UNREPORTED;
        try {
            counts = readUsage.apply(response);
        } catch (IllegalArgumentException e) {
            // Usage decoding must not turn a successful model call into a failure.
        } finally {
            recordTurn(request, counts, null);
        }
        return response;
    }

    private void recordTurn(RequestSize request, Counts counts, Class<? extends Throwable> failure) {
        turns.add(new Turn(request, counts, failure != null));
        totals = totals.plusReported(counts);
        if (failure != null) {
            log.info("Agent token usage [{}] turn {}: call failed: {} | run total: prompt={} completion={} total={}",
                    provider, turns(), failure.getName(), promptTokens(), completionTokens(), totalTokens());
        } else if (Counts.UNREPORTED.equals(counts)) {
            unreportedTurns++;
            log.info("Agent token usage [{}] turn {}: not reported by provider | run total: prompt={} completion={} total={} ({} turn(s) unreported)",
                    provider, turns(), promptTokens(), completionTokens(), totalTokens(), unreportedTurns);
        } else {
            Long prompt = counts.prompt();
            Long completion = counts.output();
            log.info("Agent token usage [{}] turn {}: prompt={} completion={} | run total: prompt={} completion={} total={}",
                    provider, turns(), prompt == null ? "-" : prompt, completion == null ? "-" : completion,
                    promptTokens(), completionTokens(), totalTokens());
        }
    }

    public int unreportedTurns() {
        return unreportedTurns;
    }

    public int turns() {
        return turns.size();
    }

    public long promptTokens() {
        Long prompt = totals.prompt();
        return prompt == null ? 0 : prompt;
    }

    public long completionTokens() {
        return totals.output() == null ? 0 : totals.output();
    }

    public long totalTokens() {
        return promptTokens() + completionTokens();
    }

    public String summary() {
        if (turns.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("Agent token usage [").append(provider)
                .append("] (reported totals; - = unavailable; reasoning included in output)\n")
                .append("turn | fresh | cache read | cache write | output | reasoning | tools | schema chars | request chars | status\n");
        for (int i = 0; i < turns.size(); i++) {
            Turn turn = turns.get(i);
            Counts c = turn.counts();
            RequestSize s = turn.size();
            row(text, i + 1, c.fresh(), c.cacheRead(), c.cacheWrite(), c.output(), c.reasoning(),
                    s.tools(), s.schemaChars(), s.requestChars(), turn.failed() ? "failed" : "ok");
        }
        row(text, "total", totals.fresh(), totals.cacheRead(), totals.cacheWrite(),
                totals.output(), totals.reasoning(), turns.stream().mapToLong(t -> t.size().tools()).sum(),
                sizeTotal(RequestSize::schemaChars), sizeTotal(RequestSize::requestChars), null);
        return text.toString();
    }

    private Long sizeTotal(Function<RequestSize, Integer> field) {
        var sizes = turns.stream().map(t -> field.apply(t.size())).filter(java.util.Objects::nonNull).toList();
        return sizes.isEmpty() ? null : sizes.stream().mapToLong(Integer::longValue).sum();
    }

    private static void row(StringBuilder text, Object label, Object... fields) {
        text.append(label);
        for (Object field : fields) {
            text.append(" | ").append(field == null ? "-" : field);
        }
        text.append('\n');
    }
}
