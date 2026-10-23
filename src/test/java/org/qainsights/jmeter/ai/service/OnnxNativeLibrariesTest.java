package org.qainsights.jmeter.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.onnxruntime.OrtEnvironment;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OnnxNativeLibrariesTest {
    private static final String PLATFORM = "linux-x64";
    private HttpServer server;
    private byte[] archive;
    private AtomicInteger requests;
    private Path home;

    @BeforeEach
    void setUp() throws Exception {
        requests = new AtomicInteger();
        home = Files.createTempDirectory("onnx-native-test");
        archive = fakeArchive();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, archive.length);
            exchange.getResponseBody().write(archive);
            exchange.close();
        });
        server.start();
        System.clearProperty(OnnxNativeLibraries.NATIVE_PATH_PROPERTY);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.stop(0);
        System.clearProperty(OnnxNativeLibraries.NATIVE_PATH_PROPERTY);
        try (var files = Files.walk(home)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void pinnedChecksumMatchesOnnxRuntimeDependency() throws Exception {
        Path jar = Path.of(OrtEnvironment.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        assertTrue(jar.toString().contains(OnnxNativeLibraries.VERSION), jar.toString());
        assertEquals(OnnxNativeLibraries.SHA256, OnnxNativeLibraries.sha256(jar));
        try (ZipFile onnxRuntime = new ZipFile(jar.toFile())) {
            for (Map.Entry<String, Map<String, String>> platform : OnnxNativeLibraries.NATIVE_SHA256.entrySet()) {
                for (Map.Entry<String, String> library : platform.getValue().entrySet()) {
                    String entryName = "ai/onnxruntime/native/" + platform.getKey() + "/" + library.getKey();
                    ZipEntry entry = onnxRuntime.getEntry(entryName);
                    assertNotNull(entry, entryName);
                    try (InputStream content = onnxRuntime.getInputStream(entry)) {
                        assertEquals(library.getValue(), sha256(content), entryName);
                    }
                }
            }
        }
    }

    @Test
    void downloadsAndExtractsOnlyTheSelectedPlatform() throws Exception {
        OnnxNativeLibraries loader = loader(sha256(archive));
        assertThrows(TypeSafeJudgmentProvider.JudgmentException.class, loader::ensureAvailableInternal);
        loader.awaitDownloadForTests(Duration.ofSeconds(10));

        Path target = loader.targetDirectory();
        assertTrue(Files.isRegularFile(target.resolve("libonnxruntime4j_jni.so")));
        assertTrue(Files.isRegularFile(target.resolve("libonnxruntime.so")));
        assertFalse(Files.exists(target.resolve("libonnxruntime4j_jni.dylib")));
        assertFalse(Files.exists(target.resolve("some/other/file.txt")));
        assertEquals(target.toString(), System.getProperty(OnnxNativeLibraries.NATIVE_PATH_PROPERTY));
        assertEquals(1, requests.get());
    }

    @Test
    void checksumMismatchLeavesNoExtractedFiles() throws Exception {
        OnnxNativeLibraries loader = loader("0".repeat(64));
        assertThrows(TypeSafeJudgmentProvider.JudgmentException.class, loader::ensureAvailableInternal);
        loader.awaitDownloadForTests(Duration.ofSeconds(10));

        assertNotNull(loader.lastError());
        assertFalse(Files.exists(loader.targetDirectory()));
        try (var files = Files.list(loader.targetDirectory().getParent())) {
            assertTrue(files.findAny().isEmpty());
        }
        assertEquals(1, requests.get());
    }

    @Test
    void existingNativeLibrariesAvoidDownload() throws Exception {
        OnnxNativeLibraries loader = loader(sha256(archive));
        Files.createDirectories(loader.targetDirectory());
        Files.writeString(loader.targetDirectory().resolve("libonnxruntime4j_jni.so"), "jni");
        Files.writeString(loader.targetDirectory().resolve("libonnxruntime.so"), "runtime");

        loader.ensureAvailableInternal();

        assertEquals(0, requests.get());
        assertEquals(loader.targetDirectory().toString(),
                System.getProperty(OnnxNativeLibraries.NATIVE_PATH_PROPERTY));
    }

    @Test
    void incompleteNativeCacheIsDownloadedAgain() throws Exception {
        OnnxNativeLibraries loader = loader(sha256(archive));
        Files.createDirectories(loader.targetDirectory());
        Files.writeString(loader.targetDirectory().resolve("libonnxruntime4j_jni.so"), "jni");

        assertThrows(TypeSafeJudgmentProvider.JudgmentException.class, loader::ensureAvailableInternal);
        loader.awaitDownloadForTests(Duration.ofSeconds(10));

        assertTrue(Files.isRegularFile(loader.targetDirectory().resolve("libonnxruntime.so")));
        assertEquals(1, requests.get());
    }

    @Test
    void tamperedCachedLibraryIsRedownloadedAndReplaced() throws Exception {
        OnnxNativeLibraries loader = loader(sha256(archive));
        Files.createDirectories(loader.targetDirectory());
        Files.writeString(loader.targetDirectory().resolve("libonnxruntime4j_jni.so"), "tampered");
        Files.writeString(loader.targetDirectory().resolve("libonnxruntime.so"), "runtime");

        assertThrows(TypeSafeJudgmentProvider.JudgmentException.class, loader::ensureAvailableInternal);
        loader.awaitDownloadForTests(Duration.ofSeconds(10));

        assertEquals("jni", Files.readString(loader.targetDirectory().resolve("libonnxruntime4j_jni.so")));
        assertEquals("runtime", Files.readString(loader.targetDirectory().resolve("libonnxruntime.so")));
        assertEquals(1, requests.get());
    }

    @Test
    void userNativePathIsNotOverwrittenOrDownloaded() throws Exception {
        String configured = home.resolve("user-libraries").toString();
        System.setProperty(OnnxNativeLibraries.NATIVE_PATH_PROPERTY, configured);
        loader(OnnxNativeLibraries.SHA256).ensureAvailableInternal();

        assertEquals(configured, System.getProperty(OnnxNativeLibraries.NATIVE_PATH_PROPERTY));
        assertEquals(0, requests.get());
    }

    private OnnxNativeLibraries loader(String checksum) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/runtime.jar";
        Map<String, String> hashes = Map.of(
                "libonnxruntime4j_jni.so", sha256Unchecked("jni"),
                "libonnxruntime.so", sha256Unchecked("runtime"));
        return new OnnxNativeLibraries(home.resolve("cache"), checksum, url, PLATFORM, hashes);
    }

    private static byte[] fakeArchive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            add(zip, "ai/onnxruntime/native/linux-x64/libonnxruntime4j_jni.so", "jni");
            add(zip, "ai/onnxruntime/native/linux-x64/libonnxruntime.so", "runtime");
            add(zip, "ai/onnxruntime/native/osx-aarch64/libonnxruntime4j_jni.dylib", "other");
            add(zip, "some/other/file.txt", "ignore");
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1 << 16];
        for (int read; (read = input.read(buffer)) != -1; ) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256Unchecked(String value) {
        try {
            return sha256(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void add(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
