package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Pure-Java reader for the Gemma {@code tokenizer.json} (BPE with byte fallback, spaces as {@code ▁},
 * {@code <bos>}/{@code <eos>} around the text). It produces the same ids as Hugging Face tokenizers for
 * EmbeddingGemma, without a native tokenizer library.
 */
public final class GemmaTokenizer {

    private static final char SPACE_MARKER = '\u2581';
    private static final long NO_MERGE = -1L;

    private final Map<String, Integer> vocab;
    private final LongLongMap merges;
    private final Map<Integer, String[]> addedByFirstCodePoint;
    private final Map<String, Integer> added;
    private final int[] byteIds;
    private final int bos;
    private final int eos;
    private final int unk;

    private GemmaTokenizer(Map<String, Integer> vocab, LongLongMap merges, Map<String, Integer> added) {
        this.vocab = vocab;
        this.merges = merges;
        this.added = added;
        this.bos = required(vocab, "<bos>");
        this.eos = required(vocab, "<eos>");
        this.unk = required(vocab, "<unk>");
        this.byteIds = new int[256];
        for (int b = 0; b < 256; b++) {
            byteIds[b] = vocab.getOrDefault(String.format("<0x%02X>", b), -1);
        }
        Map<Integer, List<String>> grouped = new HashMap<>();
        for (String content : added.keySet()) {
            if (!content.isEmpty()) {
                grouped.computeIfAbsent(content.codePointAt(0), k -> new ArrayList<>()).add(content);
            }
        }
        this.addedByFirstCodePoint = new HashMap<>();
        grouped.forEach((cp, list) -> {
            list.sort(Comparator.comparingInt(String::length).reversed());
            addedByFirstCodePoint.put(cp, list.toArray(new String[0]));
        });
    }

    public static GemmaTokenizer load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in);
        }
    }

    static GemmaTokenizer parse(InputStream in) throws IOException {
        Map<String, Integer> vocab = new HashMap<>(1 << 19);
        LongLongMap merges = new LongLongMap(1 << 16);
        List<String[]> pendingMerges = new ArrayList<>();
        Map<String, Integer> added = new HashMap<>();
        try (JsonParser p = new JsonFactory().createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("tokenizer.json is not a JSON object");
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.getCurrentName();
                p.nextToken();
                if ("added_tokens".equals(name)) {
                    readAddedTokens(p, added);
                } else if ("model".equals(name)) {
                    readModel(p, vocab, merges, pendingMerges);
                } else {
                    p.skipChildren();
                }
            }
        }
        if (vocab.isEmpty()) {
            throw new IOException("tokenizer.json has no BPE vocabulary");
        }
        for (String[] pending : pendingMerges) {
            addMerge(vocab, merges, Integer.parseInt(pending[2]), pending[0], pending[1]);
        }
        return new GemmaTokenizer(vocab, merges, added);
    }

    private static void addMerge(Map<String, Integer> vocab, LongLongMap merges, int rank, String leftToken,
            String rightToken) {
        Integer left = vocab.get(leftToken);
        Integer right = vocab.get(rightToken);
        Integer merged = vocab.get(leftToken + rightToken);
        if (left != null && right != null && merged != null) {
            merges.putIfAbsent(pairKey(left, right), ((long) rank << 32) | merged);
        }
    }

    /** Token ids for {@code text}, wrapped in {@code <bos>}/{@code <eos>} and cut to {@code maxTokens}. */
    public int[] encode(String text, int maxTokens) {
        if (maxTokens < 2) {
            throw new IllegalArgumentException("maxTokens must be at least 2");
        }
        IntList out = new IntList(Math.max(16, text.length()));
        out.add(bos);
        int segmentStart = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            String match = matchAdded(text, i, cp);
            if (match != null) {
                bpe(text.substring(segmentStart, i), out);
                out.add(added.get(match));
                i += match.length();
                segmentStart = i;
            } else {
                i += Character.charCount(cp);
            }
        }
        bpe(text.substring(segmentStart), out);
        if (out.size > maxTokens - 1) {
            out.size = maxTokens - 1;
        }
        out.add(eos);
        return out.toArray();
    }

    private String matchAdded(String text, int index, int cp) {
        String[] candidates = addedByFirstCodePoint.get(cp);
        if (candidates != null) {
            for (String candidate : candidates) {
                if (text.startsWith(candidate, index)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private void bpe(String segment, IntList out) {
        if (segment.isEmpty()) {
            return;
        }
        String s = segment.replace(' ', SPACE_MARKER);
        IntList symbols = new IntList(s.length());
        for (int i = 0; i < s.length(); ) {
            int len = Character.charCount(s.codePointAt(i));
            String ch = s.substring(i, i + len);
            Integer id = vocab.get(ch);
            if (id != null) {
                symbols.add(id);
            } else {
                byte[] bytes = ch.getBytes(StandardCharsets.UTF_8);
                boolean fallback = true;
                for (byte b : bytes) {
                    fallback &= byteIds[b & 0xff] >= 0;
                }
                if (fallback) {
                    for (byte b : bytes) {
                        symbols.add(byteIds[b & 0xff]);
                    }
                } else if (symbols.size == 0 || symbols.data[symbols.size - 1] != unk) {
                    symbols.add(unk);
                }
            }
            i += len;
        }
        merge(symbols, out);
    }

    /** Applies merges lowest rank first, leftmost on ties, as Hugging Face's BPE does. */
    private void merge(IntList symbols, IntList out) {
        int n = symbols.size;
        int[] ids = symbols.data;
        int[] next = new int[n];
        int[] prev = new int[n];
        boolean[] dead = new boolean[n];
        PriorityQueue<Long> queue = new PriorityQueue<>();
        for (int i = 0; i < n; i++) {
            prev[i] = i - 1;
            next[i] = i + 1 < n ? i + 1 : -1;
        }
        for (int i = 0; i + 1 < n; i++) {
            offer(queue, ids, next, i);
        }
        while (!queue.isEmpty()) {
            long entry = queue.poll();
            int pos = (int) entry;
            int rank = (int) (entry >>> 32);
            int right = next[pos];
            if (dead[pos] || right < 0) {
                continue;
            }
            long merge = merges.get(pairKey(ids[pos], ids[right]));
            if (merge == NO_MERGE || (int) (merge >>> 32) != rank) {
                continue;
            }
            ids[pos] = (int) merge;
            dead[right] = true;
            next[pos] = next[right];
            if (next[pos] >= 0) {
                prev[next[pos]] = pos;
                offer(queue, ids, next, pos);
            }
            if (prev[pos] >= 0) {
                offer(queue, ids, next, prev[pos]);
            }
        }
        for (int i = 0; i < n; i++) {
            if (!dead[i]) {
                out.add(ids[i]);
            }
        }
    }

    private void offer(PriorityQueue<Long> queue, int[] ids, int[] next, int pos) {
        long merge = merges.get(pairKey(ids[pos], ids[next[pos]]));
        if (merge != NO_MERGE) {
            queue.add(((merge >>> 32) << 32) | pos);
        }
    }

    private static long pairKey(int left, int right) {
        return ((long) left << 32) | (right & 0xffffffffL);
    }

    private static int required(Map<String, Integer> vocab, String token) {
        Integer id = vocab.get(token);
        if (id == null) {
            throw new IllegalArgumentException("tokenizer.json has no " + token + " token");
        }
        return id;
    }

    private static void readAddedTokens(JsonParser p, Map<String, Integer> added) throws IOException {
        if (p.currentToken() != JsonToken.START_ARRAY) {
            p.skipChildren();
            return;
        }
        while (p.nextToken() == JsonToken.START_OBJECT) {
            String content = null;
            int id = -1;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.getCurrentName();
                p.nextToken();
                if ("content".equals(field)) {
                    content = p.getValueAsString();
                } else if ("id".equals(field)) {
                    id = p.getIntValue();
                } else {
                    p.skipChildren();
                }
            }
            if (content != null && id >= 0) {
                added.put(content, id);
            }
        }
    }

    /**
     * Reads the BPE model. Merges are resolved to ids as they stream in when the vocabulary came first
     * (as in Gemma's file), so the 500k merge strings are never all held at once.
     */
    private static void readModel(JsonParser p, Map<String, Integer> vocab, LongLongMap merges,
            List<String[]> pendingMerges) throws IOException {
        int rank = 0;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String field = p.getCurrentName();
            JsonToken value = p.nextToken();
            if ("type".equals(field) && !"BPE".equals(p.getValueAsString())) {
                throw new IOException("only BPE tokenizers are supported, got " + p.getValueAsString());
            } else if ("vocab".equals(field) && value == JsonToken.START_OBJECT) {
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String token = p.getCurrentName();
                    p.nextToken();
                    vocab.put(token, p.getIntValue());
                }
            } else if ("merges".equals(field) && value == JsonToken.START_ARRAY) {
                JsonToken item;
                while ((item = p.nextToken()) != JsonToken.END_ARRAY) {
                    if (item == JsonToken.VALUE_STRING) {
                        String text = p.getText();
                        int space = text.indexOf(' ');
                        if (space > 0) {
                            rank = merge(vocab, merges, pendingMerges, rank, text.substring(0, space),
                                    text.substring(space + 1));
                        }
                    } else if (item == JsonToken.START_ARRAY) {
                        p.nextToken();
                        String left = p.getText();
                        p.nextToken();
                        String right = p.getText();
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            p.skipChildren();
                        }
                        rank = merge(vocab, merges, pendingMerges, rank, left, right);
                    } else {
                        p.skipChildren();
                    }
                }
            } else {
                p.skipChildren();
            }
        }
    }

    private static int merge(Map<String, Integer> vocab, LongLongMap merges, List<String[]> pendingMerges, int rank,
            String left, String right) {
        if (vocab.isEmpty() || !pendingMerges.isEmpty()) {
            pendingMerges.add(new String[] {left, right, Integer.toString(rank)});
        } else {
            addMerge(vocab, merges, rank, left, right);
        }
        return rank + 1;
    }

    private static final class IntList {
        int[] data;
        int size;

        IntList(int capacity) {
            data = new int[Math.max(4, capacity)];
        }

        void add(int value) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = value;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    /** Open-addressing long-to-long map, so 500k merges take about 16 MB instead of boxed entries. */
    private static final class LongLongMap {
        private static final long EMPTY = -1L;
        private long[] keys;
        private long[] values;
        private int mask;
        private int size;

        LongLongMap(int capacity) {
            allocate(Integer.highestOneBit(Math.max(4, capacity) - 1) << 1);
        }

        int size() {
            return size;
        }

        void putIfAbsent(long key, long value) {
            if ((size + 1) * 2 > keys.length) {
                grow();
            }
            int i = slot(key);
            while (keys[i] != EMPTY) {
                if (keys[i] == key) {
                    return;
                }
                i = (i + 1) & mask;
            }
            keys[i] = key;
            values[i] = value;
            size++;
        }

        long get(long key) {
            int i = slot(key);
            while (keys[i] != EMPTY) {
                if (keys[i] == key) {
                    return values[i];
                }
                i = (i + 1) & mask;
            }
            return NO_MERGE;
        }

        private void grow() {
            long[] oldKeys = keys;
            long[] oldValues = values;
            allocate(oldKeys.length * 2);
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != EMPTY) {
                    putIfAbsent(oldKeys[i], oldValues[i]);
                }
            }
        }

        private void allocate(int capacity) {
            keys = new long[capacity];
            values = new long[capacity];
            Arrays.fill(keys, EMPTY);
            mask = capacity - 1;
        }

        private int slot(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32)) & mask;
        }
    }
}
