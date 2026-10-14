package org.qainsights.jmeter.ai.service;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embeds text inside the plugin with ONNX Runtime (CPU) and an EmbeddingGemma ONNX model, so smart
 * routing needs neither an API key nor Ollama. The model loads on first use and stays loaded.
 */
public final class OnnxEmbeddingClient implements AutoCloseable {

    public static final String THREADS_KEY = "jmeter.ai.embeddings.local.threads";
    static final int MAX_TOKENS = 2048;
    private static final String SENTENCE_EMBEDDING = "sentence_embedding";
    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingClient.class);

    private final LocalEmbeddingModel model;
    private final int threads;
    private GemmaTokenizer tokenizer;
    private OrtSession session;
    private boolean closed;

    public OnnxEmbeddingClient(LocalEmbeddingModel model, int threads) {
        this.model = model;
        this.threads = threads;
    }

    public static OnnxEmbeddingClient fromConfig() {
        return new OnnxEmbeddingClient(LocalEmbeddingModel.fromConfig(), threads(AiConfig.getProperty(THREADS_KEY, "")));
    }

    /** Configured intra-op threads; by default half the cores, at most 4, so JMeter keeps headroom. */
    static int threads(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // fall through to the default
        }
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    }

    public LocalEmbeddingModel model() {
        return model;
    }

    public synchronized List<float[]> embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new TypeSafeJudgmentProvider.JudgmentException("nothing to embed");
        }
        load();
        List<float[]> vectors = new ArrayList<>(inputs.size());
        try {
            for (String input : inputs) {
                vectors.add(embedOne(input == null ? "" : input));
            }
        } catch (OrtException e) {
            throw new TypeSafeJudgmentProvider.JudgmentException("ONNX Runtime failed on " + model.repo() + ": "
                    + e.getMessage(), e);
        }
        return vectors;
    }

    private void load() {
        if (closed) {
            throw new TypeSafeJudgmentProvider.JudgmentException("the local embedding model was closed");
        }
        if (session != null) {
            return;
        }
        model.requireReady();
        long start = System.nanoTime();
        try {
            GemmaTokenizer loadedTokenizer = GemmaTokenizer.load(model.tokenizerFile());
            OrtSession created;
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(threads);
                options.setInterOpNumThreads(1);
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                created = OrtEnvironment.getEnvironment().createSession(model.modelFile().toString(), options);
            }
            if (!created.getOutputNames().contains(SENTENCE_EMBEDDING)) {
                created.close();
                throw new TypeSafeJudgmentProvider.JudgmentException(model.repo()
                        + " has no sentence_embedding output; use an EmbeddingGemma ONNX export");
            }
            tokenizer = loadedTokenizer;
            session = created;
            log.info("Loaded local embedding model {} in {} ms ({} threads)", model.repo(),
                    (System.nanoTime() - start) / 1_000_000, threads);
        } catch (TypeSafeJudgmentProvider.JudgmentException e) {
            throw e;
        } catch (Exception | UnsatisfiedLinkError e) {
            throw new TypeSafeJudgmentProvider.JudgmentException("could not load the local embedding model "
                    + model.repo() + " from " + model.directory() + ": " + e.getMessage(), e);
        }
    }

    private float[] embedOne(String text) throws OrtException {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        int[] ids = tokenizer.encode(text, MAX_TOKENS);
        long[][] inputIds = new long[1][ids.length];
        long[][] mask = new long[1][ids.length];
        for (int i = 0; i < ids.length; i++) {
            inputIds[0][i] = ids[i];
            mask[0][i] = 1;
        }
        Map<String, OnnxTensor> feeds = new HashMap<>();
        try {
            feeds.put("input_ids", OnnxTensor.createTensor(env, inputIds));
            feeds.put("attention_mask", OnnxTensor.createTensor(env, mask));
            for (Map.Entry<String, NodeInfo> input : session.getInputInfo().entrySet()) {
                if (!feeds.containsKey(input.getKey()) && input.getValue().getInfo() instanceof TensorInfo info) {
                    feeds.put(input.getKey(), emptyTensor(env, info));
                }
            }
            try (OrtSession.Result result = session.run(feeds)) {
                OnnxValue value = result.get(SENTENCE_EMBEDDING)
                        .orElseThrow(() -> new TypeSafeJudgmentProvider.JudgmentException("no sentence_embedding output"));
                float[][] vectors = (float[][]) value.getValue();
                return vectors[0];
            }
        } finally {
            feeds.values().forEach(OnnxTensor::close);
        }
    }

    /** Zero-length tensor for the image, video and audio inputs of multimodal exports. */
    private static OnnxTensor emptyTensor(OrtEnvironment env, TensorInfo info) throws OrtException {
        long[] shape = info.getShape().clone();
        for (int i = 0; i < shape.length; i++) {
            if (shape[i] < 0) {
                shape[i] = 0;
            }
        }
        return OnnxTensor.createTensor(env, FloatBuffer.allocate(0), shape);
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (session != null) {
            try {
                session.close();
            } catch (OrtException e) {
                log.debug("Closing the local embedding session failed: {}", e.getMessage());
            }
            session = null;
        }
    }
}
