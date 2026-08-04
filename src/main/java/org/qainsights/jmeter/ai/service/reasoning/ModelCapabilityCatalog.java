package org.qainsights.jmeter.ai.service.reasoning;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-model capabilities loaded from the vendored models.dev file on the
 * classpath. The file is produced at build time by
 * {@code scripts/Update-ModelCapabilities.ps1} from https://models.dev/api.json
 * - nothing is fetched at runtime.
 * <p>
 * Beyond a plain reasoning boolean, models.dev carries the real per-model
 * reasoning options: the exact effort values a model accepts, whether thinking
 * can be toggled off, and the thinking-budget range - plus input modalities
 * (image/pdf), context window, and $/Mtok pricing. The trim keeps every model
 * of the supported providers (not just capable ones) so UI like the model
 * picker can show metadata for all of them; models absent from the file
 * report no capability. How reasoning options are put on the wire is decided
 * separately by the provider-scoped shapes in {@link ReasoningCapabilities}
 * and the service helpers.
 */
public final class ModelCapabilityCatalog {

    public static final String RESOURCE = "/org/qainsights/jmeter/ai/reasoning/model-capabilities.json";

    private static final Logger log = LoggerFactory.getLogger(ModelCapabilityCatalog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile ModelCapabilityCatalog instance;

    /** The capabilities of a single model. */
    public static final class CapabilityInfo {
        private final boolean reasoning;
        private final boolean toggleable;
        private final List<String> effortLevels;
        private final long budgetMin;
        private final long budgetMax;
        private final boolean vision;
        private final boolean pdf;
        private final long contextWindow;
        private final double costIn;
        private final double costOut;

        CapabilityInfo(boolean reasoning, boolean toggleable, List<String> effortLevels,
                       long budgetMin, long budgetMax, boolean vision, boolean pdf,
                       long contextWindow, double costIn, double costOut) {
            this.reasoning = reasoning;
            this.toggleable = toggleable;
            this.effortLevels = effortLevels;
            this.budgetMin = budgetMin;
            this.budgetMax = budgetMax;
            this.vision = vision;
            this.pdf = pdf;
            this.contextWindow = contextWindow;
            this.costIn = costIn;
            this.costOut = costOut;
        }

        public boolean isReasoning() {
            return reasoning;
        }

        /** True when the provider API offers an explicit thinking off-switch. */
        public boolean isToggleable() {
            return toggleable;
        }

        /** The exact effort values the model accepts (may be empty). */
        public List<String> getEffortLevels() {
            return effortLevels;
        }

        /** True when the model takes a numeric thinking budget instead of named levels. */
        public boolean hasBudget() {
            return budgetMin > 0 || budgetMax > 0;
        }

        public long getBudgetMin() {
            return budgetMin;
        }

        public long getBudgetMax() {
            return budgetMax;
        }

        public boolean isVision() {
            return vision;
        }

        public boolean isPdf() {
            return pdf;
        }

        /** Total context window in tokens (0 = unknown). */
        public long getContextWindow() {
            return contextWindow;
        }

        /** Input price in $ per million tokens (0 = unknown/free). */
        public double getCostIn() {
            return costIn;
        }

        /** Output price in $ per million tokens (0 = unknown/free). */
        public double getCostOut() {
            return costOut;
        }

        /** True when the vendored data carries pricing for this model. */
        public boolean hasCost() {
            return costIn > 0 || costOut > 0;
        }
    }

    private final Map<String, Map<String, CapabilityInfo>> providers;

    /** Visible for tests. */
    ModelCapabilityCatalog(Map<String, Map<String, CapabilityInfo>> providers) {
        this.providers = providers;
    }

    public static ModelCapabilityCatalog getInstance() {
        ModelCapabilityCatalog current = instance;
        if (current == null) {
            synchronized (ModelCapabilityCatalog.class) {
                current = instance;
                if (current == null) {
                    current = load();
                    instance = current;
                }
            }
        }
        return current;
    }

    /** Installs a synthetic catalog (tests only). */
    public static void setInstanceForTest(ModelCapabilityCatalog catalog) {
        instance = catalog;
    }

    /** Drops the cached instance so the next lookup reloads from the classpath. */
    public static void resetInstanceForTest() {
        instance = null;
    }

    /** The capabilities of a model, or empty when it is not in the vendored data. */
    public Optional<CapabilityInfo> capabilities(String prefixedModel) {
        String provider = catalogProviderOf(prefixedModel);
        String bare = ReasoningCapabilities.bareModelId(prefixedModel);
        if (provider == null || bare.isEmpty()) {
            return Optional.empty();
        }
        Map<String, CapabilityInfo> models = providers.get(provider);
        if (models == null) {
            return Optional.empty();
        }
        CapabilityInfo exact = models.get(bare);
        if (exact != null) {
            return Optional.of(exact);
        }
        List<String> forms = new ArrayList<>();
        forms.add(bare);
        // Selector ids and data keys may each be region-prefixed
        // (us.anthropic.claude-..., global.amazon.nova-...) - normalize.
        if ("amazon-bedrock".equals(provider)) {
            String regionStripped = ReasoningCapabilities.stripBedrockRegion(bare);
            if (!regionStripped.equals(bare)) {
                CapabilityInfo stripped = models.get(regionStripped);
                if (stripped != null) {
                    return Optional.of(stripped);
                }
                forms.add(regionStripped);
            }
            // Data keys may carry a region prefix instead: match on the suffix.
            String suffix = "." + regionStripped;
            for (Map.Entry<String, CapabilityInfo> entry : models.entrySet()) {
                if (entry.getKey().endsWith(suffix)) {
                    return Optional.of(entry.getValue());
                }
            }
        }
        // Live APIs often return dated/variant ids the data stores undated
        // (claude-opus-4-8-20251101 -> claude-opus-4-8): longest prefix wins.
        String longest = null;
        for (String key : models.keySet()) {
            for (String form : forms) {
                if (form.startsWith(key) && (longest == null || key.length() > longest.length())) {
                    longest = key;
                }
            }
        }
        if (longest != null) {
            return Optional.of(models.get(longest));
        }
        log.debug("Model '{}' not found in capability catalog - controls hidden", prefixedModel);
        return Optional.empty();
    }

    /** True when the vendored data marks the model as reasoning-capable. */
    public boolean supportsReasoning(String prefixedModel) {
        return capabilities(prefixedModel).map(CapabilityInfo::isReasoning).orElse(false);
    }

    /** True when the vendored data marks the model as vision-capable. */
    public boolean supportsVision(String prefixedModel) {
        return capabilities(prefixedModel).map(CapabilityInfo::isVision).orElse(false);
    }

    /** Number of models in the catalog (for tests). */
    int size() {
        int total = 0;
        for (Map<String, CapabilityInfo> models : providers.values()) {
            total += models.size();
        }
        return total;
    }

    /** Maps a selector prefix to the models.dev provider key (null = not covered). */
    static String catalogProviderOf(String prefixedModel) {
        switch (ReasoningCapabilities.providerOf(prefixedModel)) {
            case "anthropic":
                return "anthropic";
            case "openai":
                return "openai";
            case "google":
                return "google";
            case "grok":
                return "xai";
            case "deepseek":
                return "deepseek";
            case "bedrock":
                return "amazon-bedrock";
            case "meta":
                return "meta";
            default:
                // ollama uses the live /api/show probe
                return null;
        }
    }

    private static ModelCapabilityCatalog load() {
        try (InputStream in = ModelCapabilityCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("Model capability catalog not found on classpath: {}", RESOURCE);
                return new ModelCapabilityCatalog(Collections.emptyMap());
            }
            JsonNode providersNode = MAPPER.readTree(in).path("providers");
            Map<String, Map<String, CapabilityInfo>> providers = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> providerIt = providersNode.fields();
            while (providerIt.hasNext()) {
                Map.Entry<String, JsonNode> provider = providerIt.next();
                Map<String, CapabilityInfo> models = new HashMap<>();
                Iterator<Map.Entry<String, JsonNode>> modelIt = provider.getValue().fields();
                while (modelIt.hasNext()) {
                    Map.Entry<String, JsonNode> model = modelIt.next();
                    models.put(model.getKey(), parseCapability(model.getValue()));
                }
                providers.put(provider.getKey(), Collections.unmodifiableMap(models));
            }
            int total = providers.values().stream().mapToInt(Map::size).sum();
            log.info("Loaded model capability catalog: {} providers, {} models",
                    providers.size(), total);
            return new ModelCapabilityCatalog(Collections.unmodifiableMap(providers));
        } catch (Exception e) {
            log.error("Failed to load model capability catalog - capabilities disabled", e);
            return new ModelCapabilityCatalog(Collections.emptyMap());
        }
    }

    private static CapabilityInfo parseCapability(JsonNode node) {
        List<String> effort = new ArrayList<>();
        for (JsonNode value : node.path("effort")) {
            effort.add(value.asText());
        }
        return new CapabilityInfo(
                node.path("reasoning").asBoolean(false),
                node.path("toggle").asBoolean(false),
                Collections.unmodifiableList(effort),
                node.path("budgetMin").asLong(0),
                node.path("budgetMax").asLong(0),
                node.path("vision").asBoolean(false),
                node.path("pdf").asBoolean(false),
                node.path("contextWindow").asLong(0),
                node.path("costIn").asDouble(0),
                node.path("costOut").asDouble(0));
    }
}
