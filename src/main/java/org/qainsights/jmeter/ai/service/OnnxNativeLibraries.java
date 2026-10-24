package org.qainsights.jmeter.ai.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OnnxNativeLibraries {
    static final String VERSION = "1.24.3";
    static final String SHA256 = "ca7664d1bec1527606629cdae0803beb124ef47cadd90e2f0201b092141ec107";
    static final String NATIVE_PATH_PROPERTY = "onnxruntime.native.path";
    static final String URL_PROPERTY = "jmeter.ai.onnx.runtime.url";
    static final Map<String, Map<String, String>> NATIVE_SHA256 = Map.of(
            "linux-x64", Map.of(
                    "libonnxruntime4j_jni.so", "0a23ec69a8714fcdf6074fe8251f872639862e02386b6add668ccf87981c8b81",
                    "libonnxruntime.so", "bc33abfd6154771fe4cb4268d8a8323ce86ab5ee3e110f87f36d26eb1126079c"),
            "linux-aarch64", Map.of(
                    "libonnxruntime4j_jni.so", "a74ec281a55e7fce7401a16c3ad9fe16957c96846e1d61d6bf589ecdcb375454",
                    "libonnxruntime.so", "33032cb5b91c12edbf028d7c69fa40a31848d66c0095448758af237d5e0b07db"),
            "osx-aarch64", Map.of(
                    "libonnxruntime4j_jni.dylib", "a395a01e4ff7046ef81dcef677b0e92c43f04fd56b7ee073364fda3532df35de",
                    "libonnxruntime.dylib", "1b7be3237620da964940db54a00d2a5886105f1831afd1e9e62e20dff50325e7"),
            "win-x64", Map.of(
                    "onnxruntime4j_jni.dll", "22c101a9c74c5baa0e74ca33150980799f1a76d267ea77a996655c2b1f680b02",
                    "onnxruntime.dll", "8690b5024992a4e84c62df536da0f163576e81121499138a39d97412912529a7"));
    private static final String RESOURCE_ROOT = "/ai/onnxruntime/native/";
    private static final Set<String> SUPPORTED = Set.of(
            "linux-x64", "linux-aarch64", "osx-aarch64", "win-x64");
    private static final Duration RETRY_AFTER = Duration.ofMinutes(1);
    private static final Duration DOWNLOAD_STALL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DOWNLOAD_TOTAL_TIMEOUT = Duration.ofMinutes(30);
    private static final Object DOWNLOAD_COMPLETED = new Object();
    private static final Logger log = LoggerFactory.getLogger(OnnxNativeLibraries.class);
    private static volatile OnnxNativeLibraries configured;

    private final Path home;
    private final String checksum;
    private final String url;
    private final String platform;
    private final boolean classpathNativePresent;
    private final Map<String, Map<String, String>> nativeHashes;
    private final Duration stallTimeout;
    private final Duration totalTimeout;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private Thread downloader;
    private volatile long downloadedBytes;
    private volatile long totalBytes;
    private volatile String lastError;
    private volatile long lastFailureMillis;

    private OnnxNativeLibraries(Path home, String checksum, String url,
                                String platform, boolean classpathNativePresent,
                                Map<String, Map<String, String>> nativeHashes) {
        this(home, checksum, url, platform, classpathNativePresent, nativeHashes,
                DOWNLOAD_STALL_TIMEOUT, DOWNLOAD_TOTAL_TIMEOUT);
    }

    private OnnxNativeLibraries(Path home, String checksum, String url,
                                String platform, boolean classpathNativePresent,
                                Map<String, Map<String, String>> nativeHashes,
                                Duration stallTimeout, Duration totalTimeout) {
        this.home = home;
        this.checksum = checksum;
        this.url = url;
        this.platform = platform;
        this.classpathNativePresent = classpathNativePresent;
        this.nativeHashes = nativeHashes;
        if (stallTimeout.isZero() || stallTimeout.isNegative()
                || totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("ONNX Runtime download timeouts must be positive");
        }
        this.stallTimeout = stallTimeout;
        this.totalTimeout = totalTimeout;
    }

    OnnxNativeLibraries(Path home, String checksum, String url, String platform) {
        this(home, checksum, url, platform, false, NATIVE_SHA256);
    }

    OnnxNativeLibraries(Path home, String checksum, String url, String platform,
                        Map<String, String> platformHashes) {
        this(home, checksum, url, platform, platformHashes,
                DOWNLOAD_STALL_TIMEOUT, DOWNLOAD_TOTAL_TIMEOUT);
    }

    OnnxNativeLibraries(Path home, String checksum, String url, String platform,
                        Map<String, String> platformHashes,
                        Duration stallTimeout, Duration totalTimeout) {
        this(home, checksum, url, platform, false, Map.of(platform, platformHashes),
                stallTimeout, totalTimeout);
    }

    public static void ensureAvailable() {
        OnnxNativeLibraries loader = configured;
        if (loader == null) {
            synchronized (OnnxNativeLibraries.class) {
                loader = configured;
                if (loader == null) {
                    String platform = platform();
                    String defaultUrl = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/"
                            + VERSION + "/onnxruntime-" + VERSION + ".jar";
                    String url = AiConfig.getProperty(URL_PROPERTY,
                            System.getProperty(URL_PROPERTY, defaultUrl));
                    loader = new OnnxNativeLibraries(Path.of(System.getProperty("user.home"), ".jmeter-ai",
                            "onnxruntime"), SHA256, url, platform, classpathNativePresent(platform), NATIVE_SHA256);
                    configured = loader;
                }
            }
        }
        loader.ensureAvailableInternal();
    }

    synchronized void ensureAvailableInternal() {
        String configuredPath = System.getProperty(NATIVE_PATH_PROPERTY);
        if (configuredPath != null) {
            return;
        }
        if (classpathNativePresent) {
            return;
        }
        if (!SUPPORTED.contains(platform)) {
            throw new TypeSafeJudgmentProvider.JudgmentException(
                    "ONNX Runtime " + VERSION + " has no bundled native libraries for " + platform);
        }
        Path target = home.resolve(VERSION).resolve(platform);
        if (Files.exists(target)) {
            try {
                if (hasNativeLibraries(target)) {
                    System.setProperty(NATIVE_PATH_PROPERTY, target.toString());
                    return;
                }
                log.warn("Cached ONNX Runtime native libraries failed verification for {} in {}",
                        platform, target);
            } catch (IOException e) {
                log.warn("Cached ONNX Runtime native libraries failed verification for {} in {}: {}",
                        platform, target, e.getMessage());
            }
        }
        if (downloader != null && downloader.isAlive()) {
            throw new TypeSafeJudgmentProvider.JudgmentException(progress(target), true);
        }
        if (lastError != null && System.currentTimeMillis() - lastFailureMillis < RETRY_AFTER.toMillis()) {
            throw new TypeSafeJudgmentProvider.JudgmentException("downloading ONNX Runtime " + VERSION
                    + " failed: " + lastError + " (retrying in a minute; set " + URL_PROPERTY + " for a mirror)");
        }
        downloadedBytes = 0;
        totalBytes = 0;
        lastError = null;
        downloader = new Thread(() -> download(target), "feather-wand-onnx-runtime-download");
        downloader.setDaemon(true);
        downloader.start();
        throw new TypeSafeJudgmentProvider.JudgmentException(progress(target), true);
    }

    boolean awaitDownloadForTests(Duration timeout) throws InterruptedException {
        Thread thread;
        synchronized (this) {
            thread = downloader;
        }
        if (thread != null) {
            thread.join(timeout.toMillis());
        }
        return thread == null || !thread.isAlive();
    }

    static void awaitConfiguredDownloadForTests(Duration timeout) throws InterruptedException {
        OnnxNativeLibraries loader = configured;
        if (loader == null) {
            throw new IllegalStateException("ONNX Runtime loader has not been configured");
        }
        if (!loader.awaitDownloadForTests(timeout)) {
            throw new IllegalStateException("ONNX Runtime download did not finish within " + timeout);
        }
        if (loader.lastError != null) {
            throw new IllegalStateException("ONNX Runtime download failed: " + loader.lastError);
        }
    }

    String lastError() {
        return lastError;
    }

    Path targetDirectory() {
        return home.resolve(VERSION).resolve(platform);
    }

    private String progress(Path target) {
        long downloadedMegabytes = downloadedBytes / 1_000_000;
        String size = totalBytes > 0
                ? downloadedMegabytes + " of " + totalBytes / 1_000_000 + " MB"
                : downloadedMegabytes + " MB";
        return "downloading ONNX Runtime " + VERSION + " (" + size
                + ") into " + target + "; local routing will be available when it is ready";
    }

    private void download(Path target) {
        Path parent = target.getParent();
        Path archive = null;
        Path extracted = null;
        try {
            Files.createDirectories(parent);
            archive = Files.createTempFile(parent, ".onnxruntime-", ".jar");
            downloadArchive(archive);
            if (!checksum.equalsIgnoreCase(hashOf(archive))) {
                throw new IOException("downloaded ONNX Runtime JAR failed its SHA-256 check");
            }
            extracted = Files.createTempDirectory(parent, ".onnxruntime-");
            extractPlatform(archive, extracted);
            if (!hasNativeLibraries(extracted)) {
                throw new IOException("Maven artifact contains no native libraries for " + platform);
            }
            replaceDirectory(extracted, target);
            extracted = null;
            System.setProperty(NATIVE_PATH_PROPERTY, target.toString());
            log.info("Installed ONNX Runtime {} native libraries for {} in {}", VERSION, platform, target);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            lastFailureMillis = System.currentTimeMillis();
            log.warn("Downloading ONNX Runtime {} native libraries failed: {}", VERSION, lastError);
        } finally {
            deleteQuietly(archive);
            deleteTreeQuietly(extracted);
        }
    }

    private void downloadArchive(Path target) throws IOException, InterruptedException {
        long startedNanos = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(totalTimeout).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new IOException(totalTimeoutMessage(), e);
        }
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            totalBytes = response.headers().firstValueAsLong("content-length").orElse(0);
            long remainingTotalNanos = totalTimeout.toNanos() - (System.nanoTime() - startedNanos);
            if (remainingTotalNanos <= 0) {
                throw new IOException(totalTimeoutMessage());
            }
            ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "feather-wand-onnx-runtime-watchdog");
                thread.setDaemon(true);
                return thread;
            });
            AtomicReference<Object> outcome = new AtomicReference<>();
            AtomicLong lastByteNanos = new AtomicLong(System.nanoTime());
            ScheduledFuture<?> totalTask = null;
            ScheduledFuture<?> stallTask = null;
            try {
                totalTask = watchdog.schedule(
                        () -> abortDownload(in, outcome, totalTimeoutMessage()),
                        remainingTotalNanos, TimeUnit.NANOSECONDS);
                long checkIntervalNanos = Math.min(TimeUnit.SECONDS.toNanos(1),
                        Math.max(TimeUnit.MILLISECONDS.toNanos(10), stallTimeout.toNanos() / 4));
                stallTask = watchdog.scheduleAtFixedRate(() -> {
                    if (System.nanoTime() - lastByteNanos.get() >= stallTimeout.toNanos()) {
                        abortDownload(in, outcome, stalledDownloadMessage());
                    }
                }, checkIntervalNanos, checkIntervalNanos, TimeUnit.NANOSECONDS);
                try (var out = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[1 << 16];
                    for (;;) {
                        int read;
                        try {
                            read = in.read(buffer);
                        } catch (IOException e) {
                            String timeoutMessage = timeoutMessage(outcome);
                            if (timeoutMessage != null) {
                                throw new IOException(timeoutMessage, e);
                            }
                            throw e;
                        }
                        if (read == -1) {
                            if (!outcome.compareAndSet(null, DOWNLOAD_COMPLETED)) {
                                String timeoutMessage = timeoutMessage(outcome);
                                throw new IOException(timeoutMessage == null
                                        ? totalTimeoutMessage() : timeoutMessage);
                            }
                            break;
                        }
                        String timeoutMessage = timeoutMessage(outcome);
                        if (timeoutMessage != null) {
                            throw new IOException(timeoutMessage);
                        }
                        if (read == 0) {
                            continue;
                        }
                        lastByteNanos.set(System.nanoTime());
                        out.write(buffer, 0, read);
                        downloadedBytes += read;
                    }
                }
            } finally {
                if (totalTask != null) {
                    totalTask.cancel(true);
                }
                if (stallTask != null) {
                    stallTask.cancel(true);
                }
                watchdog.shutdownNow();
            }
        }
    }

    private static void abortDownload(InputStream in, AtomicReference<Object> outcome, String message) {
        if (outcome.compareAndSet(null, message)) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String timeoutMessage(AtomicReference<Object> outcome) {
        Object result = outcome.get();
        return result instanceof String message ? message : null;
    }

    private String stalledDownloadMessage() {
        return "ONNX Runtime download stalled for " + formatDuration(stallTimeout);
    }

    private String totalTimeoutMessage() {
        return "ONNX Runtime download exceeded " + formatDuration(totalTimeout);
    }

    private static String formatDuration(Duration duration) {
        long millis = duration.toMillis();
        if (millis > 0 && millis % TimeUnit.MINUTES.toMillis(1) == 0) {
            return millis / TimeUnit.MINUTES.toMillis(1) + " min";
        }
        if (millis > 0 && millis % TimeUnit.SECONDS.toMillis(1) == 0) {
            return millis / TimeUnit.SECONDS.toMillis(1) + " s";
        }
        return millis + " ms";
    }

    private void extractPlatform(Path archive, Path destination) throws IOException {
        String prefix = "ai/onnxruntime/native/" + platform + "/";
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                    continue;
                }
                String name = entry.getName().substring(prefix.length());
                if (name.isBlank() || name.contains("/")) {
                    continue;
                }
                Path file = destination.resolve(name).normalize();
                if (!file.startsWith(destination)) {
                    throw new IOException("invalid native library path in ONNX Runtime artifact");
                }
                Files.copy(zip, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void replaceDirectory(Path source, Path target) throws IOException {
        Path backup = null;
        if (Files.exists(target)) {
            backup = Files.createTempDirectory(target.getParent(), ".onnxruntime-old-");
            Files.delete(backup);
            move(target, backup);
        }
        try {
            move(source, target);
            if (backup != null) {
                deleteTreeQuietly(backup);
            }
        } catch (IOException e) {
            if (backup != null && Files.exists(backup) && !Files.exists(target)) {
                move(backup, target);
            }
            throw e;
        }
    }

    private boolean hasNativeLibraries(Path directory) throws IOException {
        Map<String, String> expected = nativeHashes.get(platform);
        if (expected == null || expected.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> library : expected.entrySet()) {
            Path file = directory.resolve(library.getKey());
            if (!Files.isRegularFile(file) || !library.getValue().equalsIgnoreCase(hashOf(file))) {
                return false;
            }
        }
        return true;
    }

    private static boolean classpathNativePresent(String platform) {
        try {
            return OnnxNativeLibraries.class.getResource(RESOURCE_ROOT + platform + "/" + jniLibraryName(platform))
                    != null
                    && OnnxNativeLibraries.class.getResource(
                            RESOURCE_ROOT + platform + "/" + runtimeLibraryName(platform)) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String runtimeLibraryName(String platform) {
        if (platform.startsWith("win-")) {
            return "onnxruntime.dll";
        }
        if (platform.startsWith("osx-")) {
            return "libonnxruntime.dylib";
        }
        return "libonnxruntime.so";
    }

    private static String jniLibraryName(String platform) {
        if (platform.startsWith("win-")) {
            return "onnxruntime4j_jni.dll";
        }
        if (platform.startsWith("osx-")) {
            return "libonnxruntime4j_jni.dylib";
        }
        return "libonnxruntime4j_jni.so";
    }

    static String platform() {
        String os = System.getProperty("os.name", "generic").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "generic").toLowerCase(Locale.ROOT);
        String osName = os.contains("mac") || os.contains("darwin") ? "osx"
                : os.contains("win") ? "win"
                : os.contains("nux") ? "linux"
                : os.contains("android") ? "android" : "unsupported";
        String archName = arch.startsWith("amd64") || arch.startsWith("x86_64") ? "x64"
                : arch.startsWith("x86") ? "x86"
                : arch.startsWith("aarch64") ? "aarch64"
                : arch.startsWith("ppc64") ? "ppc64"
                : arch.startsWith("loongarch64") ? "loongarch64" : "unsupported";
        return osName + "-" + archName;
    }

    private static String hashOf(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = in.read(buffer)) != -1; ) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(Path file) throws IOException {
        return hashOf(file);
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                log.debug("Could not remove temporary ONNX Runtime file {}", file);
            }
        }
    }

    private static void deleteTreeQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(OnnxNativeLibraries::deleteQuietly);
        } catch (IOException e) {
            log.debug("Could not remove temporary ONNX Runtime directory {}", directory);
        }
    }
}
