package org.qainsights.jmeter.ai.load;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;

/** Pure classifier for enabled sampler classes in a Thread Group. */
public record SamplerMix(Map<Kind, Integer> counts) {
    public SamplerMix {
        EnumMap<Kind, Integer> copy = new EnumMap<>(Kind.class);
        if (counts != null) {
            copy.putAll(counts);
        }
        counts = Collections.unmodifiableMap(copy);
    }

    public static SamplerMix classify(List<Class<?>> samplerClasses) {
        EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);
        if (samplerClasses != null) {
            for (Class<?> type : samplerClasses) {
                if (type != null) {
                    counts.merge(classify(type), 1, Integer::sum);
                }
            }
        }
        return new SamplerMix(counts);
    }

    public boolean has(Kind kind) {
        return counts.getOrDefault(kind, 0) > 0;
    }

    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public String display() {
        StringJoiner joiner = new StringJoiner(", ");
        for (Kind kind : Kind.values()) {
            int count = counts.getOrDefault(kind, 0);
            if (count > 0) {
                joiner.add(count + " " + kind.label());
            }
        }
        return joiner.length() == 0 ? "Unknown" : joiner.toString();
    }

    private static Kind classify(Class<?> type) {
        if (HTTPSamplerBase.class.isAssignableFrom(type)) {
            return Kind.HTTP;
        }
        Kind nameKind = classifyClassName(type.getName());
        if (nameKind != Kind.OTHER) {
            return nameKind;
        }
        if (isJsr223(type)) {
            return Kind.JSR223;
        }
        return Kind.OTHER;
    }

    private static boolean isJsr223(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if ("org.apache.jmeter.protocol.java.sampler.JSR223Sampler".equals(current.getName())) {
                return true;
            }
        }
        return false;
    }

    static Kind classifyClassName(String name) {
        if (name == null) {
            return Kind.OTHER;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".jsr223sampler")) {
            return Kind.JSR223;
        }
        if (name.startsWith("org.apache.jmeter.protocol.jdbc.")) {
            return Kind.JDBC;
        }
        if (name.startsWith("org.apache.jmeter.protocol.jms.")) {
            return Kind.JMS;
        }
        if (name.startsWith("org.apache.jmeter.protocol.smtp.")) {
            return Kind.SMTP;
        }
        if (name.startsWith("org.apache.jmeter.protocol.tcp.")) {
            return Kind.TCP;
        }
        if (lower.contains("kafka")) {
            return Kind.KAFKA;
        }
        return Kind.OTHER;
    }

    public enum Kind {
        HTTP("HTTP"),
        JDBC("JDBC"),
        JMS("JMS"),
        SMTP("SMTP"),
        TCP("TCP"),
        KAFKA("Kafka"),
        JSR223("JSR223"),
        OTHER("Other");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
