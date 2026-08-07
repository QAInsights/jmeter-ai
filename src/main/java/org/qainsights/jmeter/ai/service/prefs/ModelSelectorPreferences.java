package org.qainsights.jmeter.ai.service.prefs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The user's model-selector curation: which models are pinned (favourites)
 * and which were used recently. Persisted as JSON at
 * {@code ~/.jmeter-ai/model-selector.json} so the curation survives JMeter
 * restarts. The file is wholly owned by this class (other residents of
 * {@code ~/.jmeter-ai/} get their own files) and written through on every
 * change via temp file + atomic move; a missing or corrupt file simply
 * yields empty preferences. Views (toolbar star, picker rows) subscribe via
 * {@link #addChangeListener} so every pin surface stays in sync.
 * <p>
 * This is the first resident of {@code ~/.jmeter-ai/} — the session store
 * (conversation persistence) follows the same one-file-per-owner pattern.
 */
public final class ModelSelectorPreferences {

    /** Maximum number of recently-used models kept (and shown in the combo). */
    public static final int MAX_RECENTS = 8;

    /**
     * System property overriding the preferences file location (used by tests
     * and portable installs): {@code jmeter.ai.preferences.file}.
     */
    public static final String PATH_PROPERTY = "jmeter.ai.preferences.file";

    private static final Logger log = LoggerFactory.getLogger(ModelSelectorPreferences.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path path;
    private final List<String> pinned = new ArrayList<>();
    private final List<String> recents = new ArrayList<>();
    private final List<Runnable> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private ModelSelectorPreferences(Path path) {
        this.path = path;
    }

    /**
     * Loads preferences from the default {@code ~/.jmeter-ai/model-selector.json},
     * or from the {@link #PATH_PROPERTY} override when set.
     */
    public static ModelSelectorPreferences load() {
        String override = System.getProperty(PATH_PROPERTY);
        Path path = override != null && !override.isEmpty()
                ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".jmeter-ai", "model-selector.json");
        return load(path);
    }

    /** Registers a listener notified after every persisted change (pin or recents). */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /** Loads preferences from an explicit file (mainly for tests). */
    public static ModelSelectorPreferences load(Path path) {
        ModelSelectorPreferences prefs = new ModelSelectorPreferences(path);
        if (!Files.exists(path)) {
            return prefs;
        }
        try {
            JsonNode root = MAPPER.readTree(path.toFile());
            readIds(root.path("pinned"), prefs.pinned);
            readIds(root.path("recents"), prefs.recents);
            trimRecents(prefs.recents);
        } catch (Exception e) {
            log.warn("Could not read model selector preferences at {} - starting fresh", path);
        }
        return prefs;
    }

    private static void readIds(JsonNode node, List<String> into) {
        for (JsonNode value : node) {
            String id = value.asText("");
            if (!id.isEmpty() && !into.contains(id)) {
                into.add(id);
            }
        }
    }

    /** Pinned model ids in the user's own order. */
    public synchronized List<String> pinned() {
        return Collections.unmodifiableList(new ArrayList<>(pinned));
    }

    /** Recently used model ids, most-recent first (capped at {@link #MAX_RECENTS}). */
    public synchronized List<String> recents() {
        return Collections.unmodifiableList(new ArrayList<>(recents));
    }

    public synchronized boolean isPinned(String modelId) {
        return pinned.contains(modelId);
    }

    /** Pins the model when unpinned, unpins it when pinned. Persists immediately. */
    public synchronized void togglePinned(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return;
        }
        if (!pinned.remove(modelId)) {
            pinned.add(modelId);
        }
        save();
    }

    /**
     * Records a selection: the model becomes the most-recent entry (duplicates
     * collapse), and the list is trimmed to {@link #MAX_RECENTS}. Persists
     * immediately.
     */
    public synchronized void recordUse(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return;
        }
        recents.remove(modelId);
        recents.add(0, modelId);
        trimRecents(recents);
        save();
    }

    private static void trimRecents(List<String> list) {
        while (list.size() > MAX_RECENTS) {
            list.remove(list.size() - 1);
        }
    }

    private void save() {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode pinnedNode = root.putArray("pinned");
            pinned.forEach(pinnedNode::add);
            ArrayNode recentsNode = root.putArray("recents");
            recents.forEach(recentsNode::add);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not save model selector preferences to {}", path, e);
        }
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }
}
