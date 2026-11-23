package org.qainsights.jmeter.ai.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Usage and request sizes for one agent run. Null token counts denote unreported fields.
 */
public final class TokenUsageTracker {

    private final String provider;
    private final List<Turn> turns = new ArrayList<>();

    public record Counts(Long fresh, Long cacheRead, Long cacheWrite, Long output, Long reasoning) {
        public static final Counts UNREPORTED = new Counts(null, null, null, null, null);

        public Counts plusReported(Counts other) {
            return new Counts(add(fresh, other.fresh), add(cacheRead, other.cacheRead),
                    add(cacheWrite, other.cacheWrite), add(output, other.output), add(reasoning, other.reasoning));
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

    private record Turn(RequestSize size, Counts counts) { }

    public TokenUsageTracker(String provider) {
        this.provider = provider;
    }

    public void record(long prompt, long completion) {
        turns.add(new Turn(new RequestSize(0, null, null), new Counts(prompt, null, null, completion, null)));
    }

    /** Records a turn whose response carried no usage block (some gateways strip it). */
    public void recordUnreported() {
        turns.add(new Turn(new RequestSize(0, null, null), Counts.UNREPORTED));
    }

    public <T> T track(RequestSize request, Supplier<T> send, Function<T, Counts> readUsage) {
        Counts counts = Counts.UNREPORTED;
        try {
            T response = send.get();
            try {
                counts = readUsage.apply(response);
            } catch (IllegalArgumentException e) {
                // Usage decoding must not turn a successful model call into a failure.
            }
            return response;
        } finally {
            turns.add(new Turn(request, counts));
        }
    }

    public int unreportedTurns() {
        return (int) turns.stream().filter(turn -> Counts.UNREPORTED.equals(turn.counts())).count();
    }

    public int turns() {
        return turns.size();
    }

    public long promptTokens() {
        return sum(Counts::fresh) + sum(Counts::cacheRead) + sum(Counts::cacheWrite);
    }

    public long completionTokens() {
        return sum(Counts::output);
    }

    public long totalTokens() {
        return promptTokens() + completionTokens();
    }

    private long sum(Function<Counts, Long> field) {
        return turns.stream().map(turn -> field.apply(turn.counts())).filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue).sum();
    }

    public String summary() {
        if (turns.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("Agent token usage [").append(provider)
                .append("] (reported totals; - = unavailable; reasoning included in output)\n")
                .append("turn | fresh | cache read | cache write | output | reasoning | tools | schema chars | request chars\n");
        for (int i = 0; i < turns.size(); i++) {
            Turn turn = turns.get(i);
            Counts c = turn.counts();
            RequestSize s = turn.size();
            row(text, i + 1, c.fresh(), c.cacheRead(), c.cacheWrite(), c.output(), c.reasoning(),
                    s.tools(), s.schemaChars(), s.requestChars());
        }
        row(text, "total", total(Counts::fresh), total(Counts::cacheRead), total(Counts::cacheWrite),
                total(Counts::output), total(Counts::reasoning), turns.stream().mapToLong(t -> t.size().tools()).sum(),
                sizeTotal(RequestSize::schemaChars), sizeTotal(RequestSize::requestChars));
        return text.toString();
    }

    private Long total(Function<Counts, Long> field) {
        return turns.stream().anyMatch(t -> field.apply(t.counts()) != null) ? sum(field) : null;
    }

    private Long sizeTotal(Function<RequestSize, Integer> field) {
        var sizes = turns.stream().map(t -> field.apply(t.size())).filter(java.util.Objects::nonNull).toList();
        return sizes.isEmpty() ? null : sizes.stream().mapToLong(Integer::longValue).sum();
    }

    private static void row(StringBuilder text, Object label, Number... fields) {
        text.append(label);
        for (Number field : fields) {
            text.append(" | ").append(field == null ? "-" : field);
        }
        text.append('\n');
    }
}
