package org.qainsights.jmeter.ai.service.session;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists chat conversations as JSON files under
 * {@code ~/.jmeter-ai/sessions/} - one file per session, following the
 * one-file-per-owner pattern set by {@code ModelSelectorPreferences}. Writes
 * go through a temp file + atomic move; a missing or corrupt file is skipped
 * on read. The directory is pruned to {@link #MAX_SESSIONS} files (oldest
 * first) on every save so long-lived installs don't accumulate history
 * forever.
 * <p>
 * The panel autosaves after every turn, so "archive on new conversation" is
 * simply: the old session's file is already on disk and a fresh id starts.
 */
public final class ConversationStore {

    /**
     * System property overriding the sessions directory (used by tests and
     * portable installs): {@code jmeter.ai.session.dir}.
     */
    public static final String DIR_PROPERTY = "jmeter.ai.session.dir";

    /** Property enabling auto-restore of the last session on panel open. */
    public static final String RESTORE_PROPERTY = "jmeter.ai.session.restore";

    /** How many session files are kept; older ones are pruned on save. */
    static final int MAX_SESSIONS = 20;

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dir;

    public ConversationStore(Path dir) {
        this.dir = dir;
    }

    /** Opens the default store ({@code ~/.jmeter-ai/sessions} or the {@link #DIR_PROPERTY} override). */
    public static ConversationStore open() {
        String override = System.getProperty(DIR_PROPERTY);
        Path dir = override != null && !override.isEmpty()
                ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".jmeter-ai", "sessions");
        return new ConversationStore(dir);
    }

    /** Generates a unique, human-sortable session id. */
    public static String newSessionId() {
        return ID_FORMAT.format(LocalDateTime.now())
                + "-" + Integer.toHexString(new java.util.Random().nextInt());
    }

    /**
     * True when the id is safe to use as a file name segment. Session ids come
     * back from persisted JSON on restore, so a crafted id must never be able
     * to traverse out of the sessions directory.
     */
    public static boolean isValidSessionId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]+") && !id.equals(".") && !id.equals("..");
    }

    public Path directory() {
        return dir;
    }

    /** Persists the session atomically and prunes old sessions. No-op for empty sessions or unsafe ids. */
    public synchronized void save(ConversationSession session) {
        if (session == null || session.turns().isEmpty()) {
            return;
        }
        if (!isValidSessionId(session.id())) {
            log.warn("Refusing to save session with unsafe id {}", session.id());
            return;
        }
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(session.id() + ".json");
            Path tmp = dir.resolve(session.id() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), toJson(session));
            moveReplacing(tmp, target);
            prune();
        } catch (IOException e) {
            log.warn("Could not save conversation session {} to {}", session.id(), dir, e);
        }
    }

    /** Atomic move where supported, plain replace otherwise (Windows / cross-volume). */
    private static void moveReplacing(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The most recently updated readable session, or empty when none exists. */
    public synchronized Optional<ConversationSession> loadMostRecent() {
        return loadAll().stream().max(Comparator.comparingLong(SessionFile::updatedAt).thenComparing(f -> f.session.id()))
                .map(SessionFile::session);
    }

    /** All readable sessions, oldest first (corrupt files are skipped). */
    synchronized List<SessionFile> loadAll() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<SessionFile> sessions = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    sessions.add(new SessionFile(fromJson(MAPPER.readTree(file.toFile())),
                            Files.getLastModifiedTime(file).toMillis(), file));
                } catch (Exception e) {
                    log.warn("Skipping unreadable session file {}", file);
                }
            }
        } catch (IOException e) {
            log.warn("Could not list sessions in {}", dir, e);
        }
        sessions.sort(Comparator.comparingLong(SessionFile::updatedAt));
        return sessions;
    }

    /** Deletes the oldest sessions beyond {@link #MAX_SESSIONS} by their on-disk path (never by JSON content). */
    private void prune() {
        List<SessionFile> sessions = loadAll();
        for (int i = 0; i < sessions.size() - MAX_SESSIONS; i++) {
            try {
                Files.deleteIfExists(sessions.get(i).path());
            } catch (IOException e) {
                log.warn("Could not prune session {}", sessions.get(i).session().id());
            }
        }
    }

    record SessionFile(ConversationSession session, long updatedAt, Path path) {
    }

    private static ObjectNode toJson(ConversationSession session) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", session.id());
        root.put("createdAt", session.createdAt());
        root.put("updatedAt", System.currentTimeMillis());
        root.put("model", session.model());
        ArrayNode turns = root.putArray("turns");
        for (ConversationSession.Turn turn : session.turns()) {
            ObjectNode node = turns.addObject();
            node.put("role", turn.role());
            node.put("text", turn.text());
            node.put("ts", turn.timestamp());
        }
        ArrayNode attachments = root.putArray("attachments");
        for (ConversationSession.AttachmentSnapshot snapshot : session.attachments()) {
            ObjectNode node = attachments.addObject();
            node.put("id", snapshot.id());
            node.put("fileName", snapshot.fileName());
            node.put("mode", snapshot.mode());
            node.put("content", snapshot.content());
        }
        return root;
    }

    private static ConversationSession fromJson(JsonNode root) {
        List<ConversationSession.Turn> turns = new ArrayList<>();
        for (JsonNode node : root.path("turns")) {
            turns.add(new ConversationSession.Turn(
                    node.path("role").asText("user"),
                    node.path("text").asText(""),
                    node.path("ts").asLong(0L)));
        }
        List<ConversationSession.AttachmentSnapshot> attachments = new ArrayList<>();
        for (JsonNode node : root.path("attachments")) {
            attachments.add(new ConversationSession.AttachmentSnapshot(
                    node.path("id").asText(""),
                    node.path("fileName").asText("file"),
                    node.path("mode").asText("smart"),
                    node.path("content").asText("")));
        }
        String id = root.path("id").asText("");
        if (!isValidSessionId(id)) {
            // crafted or legacy id: neutralize it so the next autosave writes
            // a fresh file inside the sessions directory
            id = newSessionId();
        }
        return new ConversationSession(
                id,
                root.path("createdAt").asLong(0L),
                root.path("model").asText(""),
                turns, attachments);
    }
}
