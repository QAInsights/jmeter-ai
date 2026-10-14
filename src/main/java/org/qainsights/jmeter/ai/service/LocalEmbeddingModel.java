package org.qainsights.jmeter.ai.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ONNX embedding model files on disk ({@code model_q4.onnx}, its external weights and
 * {@code tokenizer.json}), downloaded from Hugging Face on first use in a background thread.
 * Known models are pinned to a revision and verified by size and SHA-256.
 */
public final class LocalEmbeddingModel {

    public static final String MODEL_KEY = "jmeter.ai.embeddings.local.model";
    public static final String DIR_KEY = "jmeter.ai.embeddings.local.dir";
    public static final String DOWNLOAD_KEY = "jmeter.ai.embeddings.local.download";
    public static final String BASE_URL_KEY = "jmeter.ai.embeddings.local.base.url";
    public static final String DEFAULT_MODEL = "onnx-community/embeddinggemma-300m-ONNX";
    public static final String DEFAULT_BASE_URL = "https://huggingface.co";
    static final String MODEL_FILE = "onnx/model_q4.onnx";
    static final String DATA_FILE = "onnx/model_q4.onnx_data";
    static final String TOKENIZER_FILE = "tokenizer.json";
    private static final Duration RETRY_AFTER = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingModel.class);

    /** A file in the model repo; {@code size} below zero and a null hash mean "not pinned". */
    record FileSpec(String path, long size, String sha256) {
    }

    private static final Map<String, String> PINNED_REVISIONS = Map.of(
            "onnx-community/embeddinggemma-300m-ONNX", "5090578d9565bb06545b4552f76e6bc2c93e4a66",
            "onnx-community/embeddinggemma-2-ONNX", "daa72c51243991dfcaf9f9137d2c573d8f7790c0");

    private static final Map<String, List<FileSpec>> PINNED_FILES = Map.of(
            "onnx-community/embeddinggemma-300m-ONNX", List.of(
                    new FileSpec(MODEL_FILE, 519_322L,
                            "ad1dfee81a70f7944b9b9d1cc6e48075b832881cf33fab2f2b248be78f3f0043"),
                    new FileSpec(DATA_FILE, 196_725_760L,
                            "599962c3143b040de2dd05e5975be3e9091dd067cacc6a8f7186e3203bab9e02"),
                    new FileSpec(TOKENIZER_FILE, 20_323_312L,
                            "4dda02faaf32bc91031dc8c88457ac272b00c1016cc679757d1c441b248b9c47")),
            "onnx-community/embeddinggemma-2-ONNX", List.of(
                    new FileSpec(MODEL_FILE, 490_742L,
                            "f9eeba97acddf139b8ee2ddf04bc30dceafa88de93fadf74d7644e0d61a477a9"),
                    new FileSpec(DATA_FILE, 174_028_800L,
                            "c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49"),
                    new FileSpec(TOKENIZER_FILE, 32_170_510L,
                            "4d777ef5bdc1aa36227abdfb77c3e49e7b9c892d16e1b6bda41c393504828be4")));

    private final String repo;
    private final String revision;
    private final List<FileSpec> files;
    private final Path directory;
    private final String baseUrl;
    private final boolean downloadAllowed;
    private final HttpClient http;

    private Thread downloader;
    private volatile boolean verified;
    private volatile long downloadedBytes;
    private volatile String lastError;
    private volatile long lastFailureMillis;

    LocalEmbeddingModel(String repo, String revision, List<FileSpec> files, Path root, String baseUrl,
            boolean downloadAllowed) {
        this.repo = repo;
        this.revision = revision;
        this.files = files;
        this.directory = root.resolve(repo.replace('/', '_')).resolve(revision);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.downloadAllowed = downloadAllowed;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public static LocalEmbeddingModel fromConfig() {
        String repo = model(AiConfig.getProperty(MODEL_KEY, DEFAULT_MODEL));
        String dir = AiConfig.getProperty(DIR_KEY, "");
        Path root = dir == null || dir.isBlank()
                ? Path.of(System.getProperty("user.home"), ".jmeter-ai", "models")
                : root(dir.trim(), System.getProperty("user.home"));
        String revision = PINNED_REVISIONS.getOrDefault(repo, "main");
        List<FileSpec> files = PINNED_FILES.getOrDefault(repo, List.of(
                new FileSpec(MODEL_FILE, -1, null), new FileSpec(DATA_FILE, -1, null),
                new FileSpec(TOKENIZER_FILE, -1, null)));
        String baseUrl = AiConfig.getProperty(BASE_URL_KEY, DEFAULT_BASE_URL);
        return new LocalEmbeddingModel(repo, revision, files,
                root, baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim(),
                !"false".equalsIgnoreCase(String.valueOf(AiConfig.getProperty(DOWNLOAD_KEY, "true")).trim()));
    }

    /** Resolves a leading {@code ~} against the home folder without touching Windows separators. */
    static Path root(String dir, String home) {
        if (dir.equals("~")) {
            return Path.of(home);
        }
        if (dir.startsWith("~/") || dir.startsWith("~\\")) {
            return Path.of(home).resolve(dir.substring(2));
        }
        return Path.of(dir);
    }

    /** The configured Hugging Face repo id, or the default when blank. */
    public static String model(String value) {
        return value == null || value.isBlank() ? DEFAULT_MODEL : value.trim();
    }

    public String repo() {
        return repo;
    }

    public Path directory() {
        return directory;
    }

    public Path modelFile() {
        return directory.resolve(MODEL_FILE);
    }

    public Path tokenizerFile() {
        return directory.resolve(TOKENIZER_FILE);
    }

    /** True when every file is on disk with its pinned size. */
    public boolean ready() {
        for (FileSpec spec : files) {
            Path file = directory.resolve(spec.path());
            try {
                if (!Files.isRegularFile(file) || (spec.size() >= 0 && Files.size(file) != spec.size())) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Hashes the files on disk once per model instance and deletes every one that does not match its
     * pinned SHA-256, so they are downloaded again instead of being loaded. Returns the first bad path.
     */
    private String firstCorruptFile() {
        String first = null;
        for (FileSpec spec : files) {
            if (spec.sha256() == null) {
                continue;
            }
            Path file = directory.resolve(spec.path());
            try (InputStream in = Files.newInputStream(file)) {
                MessageDigest digest = sha256();
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
                if (!spec.sha256().equalsIgnoreCase(HexFormat.of().formatHex(digest.digest()))) {
                    Files.deleteIfExists(file);
                    first = first == null ? spec.path() : first;
                }
            } catch (IOException e) {
                first = first == null ? spec.path() : first;
            }
        }
        return first;
    }

    /**
     * Returns normally when the model is on disk and matches its pinned hashes. Otherwise starts (or keeps) the background
     * download and throws a message saying what is happening, so the caller falls back for now.
     */
    public synchronized void requireReady() {
        if (verified) {
            return;
        }
        if (ready()) {
            String corrupt = firstCorruptFile();
            if (corrupt == null) {
                verified = true;
                return;
            }
            lastError = null;
        }
        if (!downloadAllowed) {
            throw new TypeSafeJudgmentProvider.JudgmentException("the local embedding model " + repo
                    + " is not in " + directory + " and " + DOWNLOAD_KEY + "=false; download "
                    + String.join(", ", files.stream().map(FileSpec::path).toList()) + " from " + baseUrl + "/"
                    + repo + " into that folder");
        }
        if (downloader != null && downloader.isAlive()) {
            throw new TypeSafeJudgmentProvider.JudgmentException(progress());
        }
        if (lastError != null && System.currentTimeMillis() - lastFailureMillis < RETRY_AFTER.toMillis()) {
            throw new TypeSafeJudgmentProvider.JudgmentException("downloading " + repo + " failed: " + lastError
                    + " (retrying in a minute; set " + BASE_URL_KEY + " for a mirror, or the JVM proxy settings)");
        }
        lastError = null;
        downloadedBytes = 0;
        downloader = new Thread(this::downloadAll, "feather-wand-embedding-download");
        downloader.setDaemon(true);
        downloader.start();
        throw new TypeSafeJudgmentProvider.JudgmentException(progress());
    }

    /** Waits for a download started by {@link #requireReady()}; for tests and benchmarks. */
    public void awaitDownloadForTests(Duration timeout) throws InterruptedException {
        Thread thread;
        synchronized (this) {
            thread = downloader;
        }
        if (thread != null) {
            thread.join(timeout.toMillis());
        }
    }

    String lastError() {
        return lastError;
    }

    private String progress() {
        long total = files.stream().mapToLong(FileSpec::size).filter(s -> s > 0).sum();
        String done = String.format(Locale.ROOT, "%d MB", downloadedBytes / 1_000_000);
        return "downloading the local embedding model " + repo + " (" + done
                + (total > 0 ? String.format(Locale.ROOT, " of %d MB", total / 1_000_000) : "")
                + ") into " + directory + "; Agent Mode uses all tools until it is ready";
    }

    private void downloadAll() {
        try {
            Files.createDirectories(directory);
            for (FileSpec spec : files) {
                Path target = directory.resolve(spec.path());
                if (Files.isRegularFile(target) && (spec.size() < 0 || Files.size(target) == spec.size())) {
                    downloadedBytes += Math.max(0, spec.size());
                    continue;
                }
                download(spec, target);
            }
            log.info("Local embedding model {} downloaded to {}", repo, directory);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            lastFailureMillis = System.currentTimeMillis();
            log.warn("Downloading local embedding model {} failed: {}", repo, lastError);
        }
    }

    private void download(FileSpec spec, Path target) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        URI uri = URI.create(baseUrl + "/" + repo + "/resolve/" + revision + "/" + spec.path());
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30)).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        MessageDigest digest = sha256();
        long written = 0;
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " for " + uri);
            }
            try (OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    written += read;
                    downloadedBytes += read;
                }
            }
            if (spec.size() >= 0 && written != spec.size()) {
                throw new IOException(spec.path() + " is " + written + " bytes, expected " + spec.size());
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (spec.sha256() != null && !spec.sha256().equalsIgnoreCase(actual)) {
                throw new IOException(spec.path() + " failed its SHA-256 check");
            }
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
