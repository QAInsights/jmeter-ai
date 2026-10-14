package org.qainsights.jmeter.ai.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalEmbeddingModelTest {

    private static final String REPO = "acme/tiny-embed";
    private static final String REV = "abc123";

    @TempDir
    Path root;

    private HttpServer server;
    private final Map<String, byte[]> served = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/redirect/")) {
                exchange.getResponseHeaders().add("Location", path.substring("/redirect".length()));
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            byte[] body = served.get(path);
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
        for (String file : List.of(LocalEmbeddingModel.MODEL_FILE, LocalEmbeddingModel.DATA_FILE,
                LocalEmbeddingModel.TOKENIZER_FILE)) {
            served.put("/" + REPO + "/resolve/" + REV + "/" + file, ("bytes of " + file).getBytes(StandardCharsets.UTF_8));
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private List<LocalEmbeddingModel.FileSpec> pinned() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return served.entrySet().stream().map(e -> new LocalEmbeddingModel.FileSpec(
                e.getKey().substring(("/" + REPO + "/resolve/" + REV + "/").length()), e.getValue().length,
                HexFormat.of().formatHex(md.digest(e.getValue())))).toList();
    }

    @Test
    void downloadsInTheBackgroundThenReportsReady() throws Exception {
        LocalEmbeddingModel model = new LocalEmbeddingModel(REPO, REV, pinned(), root, url(), true);
        assertFalse(model.ready());
        RuntimeException pending = assertThrows(RuntimeException.class, model::requireReady);
        assertTrue(pending.getMessage().contains("downloading the local embedding model acme/tiny-embed"),
                pending.getMessage());
        model.awaitDownloadForTests(Duration.ofSeconds(10));
        assertTrue(model.ready(), String.valueOf(model.lastError()));
        assertDoesNotThrow(model::requireReady);
        assertEquals("bytes of tokenizer.json", Files.readString(model.tokenizerFile()));
        assertEquals(root.resolve("acme_tiny-embed").resolve(REV).resolve("onnx/model_q4.onnx"), model.modelFile());
        int after = requests.get();
        new LocalEmbeddingModel(REPO, REV, pinned(), root, url(), true).requireReady();
        assertEquals(after, requests.get(), "a downloaded model is not fetched again");
    }

    @Test
    void followsRedirectsToTheCdn() throws Exception {
        LocalEmbeddingModel model = new LocalEmbeddingModel(REPO, REV, pinned(), root, url() + "/redirect", true);
        assertThrows(RuntimeException.class, model::requireReady);
        model.awaitDownloadForTests(Duration.ofSeconds(10));
        assertTrue(model.ready(), String.valueOf(model.lastError()));
    }

    @Test
    void checksumMismatchLeavesNoFileAndReportsTheError() throws Exception {
        List<LocalEmbeddingModel.FileSpec> specs = pinned().stream()
                .map(s -> new LocalEmbeddingModel.FileSpec(s.path(), s.size(), "00" + s.sha256().substring(2)))
                .toList();
        LocalEmbeddingModel model = new LocalEmbeddingModel(REPO, REV, specs, root, url(), true);
        assertThrows(RuntimeException.class, model::requireReady);
        model.awaitDownloadForTests(Duration.ofSeconds(10));
        assertFalse(model.ready());
        assertNotNull(model.lastError());
        assertTrue(model.lastError().contains("SHA-256"), model.lastError());
        try (var files = Files.walk(root)) {
            assertTrue(files.noneMatch(p -> Files.isRegularFile(p)), "no partial or bad file is kept");
        }
        RuntimeException retry = assertThrows(RuntimeException.class, model::requireReady);
        assertTrue(retry.getMessage().contains("failed") && retry.getMessage().contains("retrying"), retry.getMessage());
    }

    @Test
    void missingFileIsReported() throws Exception {
        served.remove("/" + REPO + "/resolve/" + REV + "/" + LocalEmbeddingModel.DATA_FILE);
        LocalEmbeddingModel model = new LocalEmbeddingModel(REPO, REV,
                List.of(new LocalEmbeddingModel.FileSpec(LocalEmbeddingModel.DATA_FILE, -1, null)), root, url(), true);
        assertThrows(RuntimeException.class, model::requireReady);
        model.awaitDownloadForTests(Duration.ofSeconds(10));
        assertTrue(model.lastError().contains("HTTP 404"), model.lastError());
    }

    @Test
    void downloadDisabledExplainsWhereToPutTheFiles() throws Exception {
        LocalEmbeddingModel model = new LocalEmbeddingModel(REPO, REV, pinned(), root, url(), false);
        RuntimeException e = assertThrows(RuntimeException.class, model::requireReady);
        assertTrue(e.getMessage().contains("jmeter.ai.embeddings.local.download=false"), e.getMessage());
        assertTrue(e.getMessage().contains(model.directory().toString()), e.getMessage());
        assertEquals(0, requests.get());
    }

    @Test
    void wrongSizeOnDiskIsNotReady() throws Exception {
        LocalEmbeddingModel model = new LocalEmbeddingModel(REPO, REV, pinned(), root, url(), true);
        for (LocalEmbeddingModel.FileSpec spec : pinned()) {
            Path file = model.directory().resolve(spec.path());
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[(int) spec.size()]);
        }
        assertTrue(model.ready());
        Files.write(model.tokenizerFile(), new byte[1]);
        assertFalse(model.ready());
    }

    @Test
    void sameSizeCorruptFileOnDiskIsDeletedAndDownloadedAgain() throws Exception {
        LocalEmbeddingModel model = new LocalEmbeddingModel(REPO, REV, pinned(), root, url(), true);
        for (LocalEmbeddingModel.FileSpec spec : pinned()) {
            Path file = model.directory().resolve(spec.path());
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[(int) spec.size()]);
        }
        assertThrows(RuntimeException.class, model::requireReady);
        model.awaitDownloadForTests(Duration.ofSeconds(10));
        assertDoesNotThrow(model::requireReady);
        assertEquals("bytes of tokenizer.json", Files.readString(model.tokenizerFile()));
        assertEquals("bytes of onnx/model_q4.onnx", Files.readString(model.modelFile()));
    }

    @Test
    void tildeResolvesUnderHomeWithoutMangling() {
        Path home = root.resolve("C:\\Users\\Alice");
        assertEquals(home.resolve(".jmeter-ai/models"), LocalEmbeddingModel.root("~/.jmeter-ai/models", home.toString()));
        assertEquals(home.resolve("models"), LocalEmbeddingModel.root("~\\models", home.toString()));
        assertEquals(home, LocalEmbeddingModel.root("~", home.toString()));
        assertEquals(Path.of("/opt/models"), LocalEmbeddingModel.root("/opt/models", home.toString()));
    }
}
