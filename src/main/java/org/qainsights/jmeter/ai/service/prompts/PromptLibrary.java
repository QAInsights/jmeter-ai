package org.qainsights.jmeter.ai.service.prompts;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The user's prompt library: six built-in starter prompts plus user-saved
 * prompts persisted as JSON at {@code ~/.jmeter-ai/prompts.json}, following
 * the one-file-per-owner pattern set by {@code ModelSelectorPreferences}.
 * Writes go through a temp file + atomic move (plain replace where atomic
 * moves are unsupported, e.g. Windows); a missing or corrupt file simply
 * yields the built-ins. Built-ins are read-only - saving under a built-in's
 * exact name is refused so the picker never shows ambiguous duplicates; the
 * "Save as copy" flow suggests a derived name instead. Views (intellisense
 * picker) subscribe via {@link #addChangeListener} so every surface stays in
 * sync.
 */
public final class PromptLibrary {

    /**
     * System property overriding the library file location (used by tests and
     * portable installs): {@code jmeter.ai.prompts.file}.
     */
    public static final String PATH_PROPERTY = "jmeter.ai.prompts.file";

    private static final Logger log = LoggerFactory.getLogger(PromptLibrary.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A named prompt: built-ins ship with the plugin, user prompts persist. */
    public record Prompt(String name, String body, boolean builtin) {

        /** First line of the body, for the picker's two-line renderer. */
        public String preview() {
            int newline = body.indexOf('\n');
            return newline < 0 ? body : body.substring(0, newline);
        }
    }

    /** Built-in starter prompts, in picker order. Read-only; keyed out of user saves. */
    private static final List<Prompt> BUILTINS = List.of(
            new Prompt("Analyze results", """
                    Analyze the attached JMeter results. Identify the slowest samplers, error patterns
                    (and whether errors correlate with load level), throughput and response-time trends
                    over the run, and any signs of saturation (ramp-up plateau, connection errors,
                    growing latency). Finish with a prioritized list of what to investigate first.

                    [Attach a .jtl results file via the paperclip before sending]""", true),
            new Prompt("Review plan vs best practices", """
                    Review my current test plan against JMeter best practices. Check: thread group
                    ramp-up and duration settings, think-time timers, connection/response timeouts,
                    listeners that hurt load generation, hardcoded hosts or ports that belong in
                    variables, and whether the load profile matches a realistic production pattern.
                    List issues by severity with a concrete fix for each.""", true),
            new Prompt("Explain errors in jmeter.log", """
                    Explain the errors and warnings in the attached jmeter.log. For each distinct
                    issue: what it means, the most likely root cause in my test plan or environment,
                    and how to fix it. Group repeated stack traces instead of listing each occurrence.

                    [Attach jmeter.log via the paperclip before sending]""", true),
            new Prompt("Suggest assertions & timers", """
                    Review my test plan and suggest which samplers are missing response assertions
                    (status, body content) and where think-time or pacing timers should be added for
                    realistic load. For each suggestion, name the sampler it applies to and the exact
                    assertion/timer to add. Skip samplers where an assertion would be meaningless
                    (e.g. fire-and-forget).""", true),
            new Prompt("Find correlation candidates", """
                    Run a correlation probe on my test plan: find dynamic values (session ids, CSRF
                    tokens, auth codes) returned by one sampler and reused by later requests. Show me
                    each candidate with its source sampler and where it's reused, then apply
                    correlation for the ones I confirm.""", true),
            new Prompt("Recording brief", """
                    Record this user journey: start at [base URL], log in as [user/role], then
                    [main flow, e.g. search for a product, add to cart, check out]. Skip [anything to
                    exclude, e.g. logout, third-party trackers]. Parameterize [credentials / search
                    terms] and note any steps where I need to pause for manual input.""", true));

    private final Path path;
    private final List<Prompt> userPrompts = new ArrayList<>();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    private PromptLibrary(Path path) {
        this.path = path;
    }

    /**
     * Loads the library from the default {@code ~/.jmeter-ai/prompts.json},
     * or from the {@link #PATH_PROPERTY} override when set.
     */
    public static PromptLibrary load() {
        String override = System.getProperty(PATH_PROPERTY);
        Path path = override != null && !override.isEmpty()
                ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".jmeter-ai", "prompts.json");
        return load(path);
    }

    /** Loads the library from an explicit file (mainly for tests). */
    public static PromptLibrary load(Path path) {
        PromptLibrary library = new PromptLibrary(path);
        if (!Files.exists(path)) {
            return library;
        }
        try {
            for (JsonNode node : MAPPER.readTree(path.toFile()).path("prompts")) {
                String name = node.path("name").asText("");
                String body = node.path("body").asText("");
                if (!name.isEmpty() && !body.isEmpty() && !isBuiltinName(name)
                        && findByName(library.userPrompts, name).isEmpty()) {
                    library.userPrompts.add(new Prompt(name, body, false));
                }
            }
        } catch (Exception e) {
            log.warn("Could not read prompt library at {} - using built-ins only", path);
        }
        return library;
    }

    /** Registers a listener notified after every persisted change (save or delete). */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /** Built-ins first, then user prompts in insertion order. */
    public synchronized List<Prompt> all() {
        List<Prompt> all = new ArrayList<>(BUILTINS);
        all.addAll(userPrompts);
        return Collections.unmodifiableList(all);
    }

    /** Prompts whose name contains the query, case-insensitively; empty query returns all. */
    public synchronized List<Prompt> filter(String query) {
        if (query == null || query.isBlank()) {
            return all();
        }
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        List<Prompt> matches = new ArrayList<>();
        for (Prompt prompt : all()) {
            if (prompt.name().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                matches.add(prompt);
            }
        }
        return matches;
    }

    /** Looks up a prompt by exact name, user prompts shadowing nothing (built-ins win ties). */
    public synchronized Optional<Prompt> find(String name) {
        return findByName(all(), name);
    }

    /** True when the name belongs to a built-in (which cannot be edited or deleted). */
    public static boolean isBuiltinName(String name) {
        return name != null && findByName(BUILTINS, name).isPresent();
    }

    /**
     * Saves a user prompt, replacing any existing user prompt with the same
     * name. Returns false (no-op) for blank names/bodies, for names owned by
     * a built-in, and when persisting to disk fails (the in-memory change is
     * rolled back so the library stays consistent with the file). Listeners
     * are notified only after a successful persist.
     */
    public synchronized boolean save(String name, String body) {
        if (name == null || name.isBlank() || body == null || body.isBlank() || isBuiltinName(name)) {
            return false;
        }
        List<Prompt> snapshot = new ArrayList<>(userPrompts);
        findByName(userPrompts, name).ifPresent(userPrompts::remove);
        userPrompts.add(new Prompt(name, body, false));
        if (!persist()) {
            userPrompts.clear();
            userPrompts.addAll(snapshot);
            return false;
        }
        notifyChange();
        return true;
    }

    /**
     * Deletes a user prompt by name. Returns false for unknown names, for
     * built-ins, and when persisting to disk fails (the prompt is kept in
     * that case). Listeners are notified only after a successful persist.
     */
    public synchronized boolean delete(String name) {
        if (name == null || isBuiltinName(name)) {
            return false;
        }
        Optional<Prompt> existing = findByName(userPrompts, name);
        if (existing.isEmpty()) {
            return false;
        }
        int index = userPrompts.indexOf(existing.get());
        userPrompts.remove(index);
        if (!persist()) {
            userPrompts.add(index, existing.get());
            return false;
        }
        notifyChange();
        return true;
    }

    private static Optional<Prompt> findByName(List<Prompt> prompts, String name) {
        return prompts.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    /** Writes the library to disk. Returns false when the write fails. */
    private boolean persist() {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode promptsNode = root.putArray("prompts");
            for (Prompt prompt : userPrompts) {
                ObjectNode node = promptsNode.addObject();
                node.put("name", prompt.name());
                node.put("body", prompt.body());
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            moveReplacing(tmp, path);
            return true;
        } catch (IOException e) {
            log.warn("Could not save prompt library to {}", path, e);
            return false;
        }
    }

    private void notifyChange() {
        for (Runnable listener : changeListeners) {
            listener.run();
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
}
