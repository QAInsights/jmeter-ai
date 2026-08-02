package org.qainsights.jmeter.ai.service.reasoning;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Reasoning (thinking/effort) capabilities of a model, keyed by the prefixed
 * model id used in the model selector ({@code openai:}, {@code ollama:},
 * {@code deepseek:}, {@code google:}, {@code grok:}, {@code meta:},
 * {@code bedrock:}, bare = Anthropic).
 * <p>
 * Two layers, deliberately separate:
 * <ul>
 *   <li><b>Whether</b> a model reasons, which effort values it accepts and
 *   whether thinking can be switched off come from the vendored
 *   {@link ModelCapabilityCatalog} (models.dev data, refreshed at build time)
 *   - plus the live Ollama {@code /api/show} probe for local models.</li>
 *   <li><b>How</b> to ask for it - the request shape - is decided by the small
 *   provider-scoped rules here and in the per-provider helpers
 *   ({@code AnthropicThinking}, {@code OpenAiReasoning}, ...), which change far
 *   less often than the model list.</li>
 * </ul>
 * Models absent from the catalog report no capability, so the UI hides the
 * controls instead of offering switches the model would reject. Effort level
 * names are lowercase and come straight from the catalog ({@code none},
 * {@code minimal}, {@code low}, {@code medium}, {@code high}, {@code xhigh},
 * {@code max}); each service maps them onto its SDK values.
 */
public final class ReasoningCapabilities {

    /** Synthetic effort labels for budget-shaped models (mapped onto the budget range). */
    public static final List<String> LOW_MEDIUM_HIGH = List.of("low", "medium", "high");

    private static final long DEFAULT_ANTHROPIC_BUDGET_LOW = 2048;
    private static final long DEFAULT_ANTHROPIC_BUDGET_MEDIUM = 8192;
    private static final long DEFAULT_ANTHROPIC_BUDGET_HIGH = 16384;
    private static final long DEFAULT_ANTHROPIC_BUDGET_XHIGH = 24576;
    private static final long DEFAULT_ANTHROPIC_BUDGET_MAX = 32768;

    private ReasoningCapabilities() {
    }

    /**
     * Live capability probe for local Ollama models (backed by Ollama's
     * {@code /api/show}). Registered by the chat panel at startup. Empty means
     * "not probed yet" - Ollama controls stay optimistic (shown) until the
     * probe answers otherwise.
     */
    private static volatile java.util.function.Function<String, Optional<Boolean>>
            ollamaThinkingProbe;

    /** Registers the live Ollama thinking-capability probe (null clears it). */
    public static void setOllamaThinkingProbe(
            java.util.function.Function<String, Optional<Boolean>> probe) {
        ollamaThinkingProbe = probe;
    }

    /** Ollama thinking capability: probe result if known, otherwise optimistic true. */
    private static boolean ollamaSupportsThinking(String bareModel) {
        java.util.function.Function<String, Optional<Boolean>> probe = ollamaThinkingProbe;
        if (probe == null) {
            return true;
        }
        Optional<Boolean> probed = probe.apply(bareModel);
        return probed == null || probed.orElse(true);
    }

    /**
     * True when the model exposes an on/off thinking switch. Whether reasoning
     * exists at all comes from the catalog/probe; whether it can be switched
     * off is provider semantics: Anthropic thinking is opt-in by API design,
     * OpenAI models with a {@code none} effort are toggleable, Google models
     * carry an explicit toggle flag in the data (e.g. Gemini Flash but not
     * Pro), and always-reasoning models (o-series, gpt-5, Grok, deepseek-r1)
     * get no toggle.
     *
     * @param prefixedModel the model id from the selector (may be null)
     */
    public static boolean supportsThinkingToggle(String prefixedModel) {
        String provider = providerOf(prefixedModel);
        String model = bareModelId(prefixedModel);
        if (model.isEmpty()) {
            return false;
        }
        if ("ollama".equals(provider)) {
            return ollamaSupportsThinking(model);
        }
        Optional<ModelCapabilityCatalog.CapabilityInfo> caps =
                catalog().capabilities(prefixedModel);
        if (caps.isEmpty() || !caps.get().isReasoning()) {
            return false;
        }
        switch (provider) {
            case "anthropic":
                return true;
            case "bedrock":
                switch (bedrockFamily(model)) {
                    case "anthropic":
                        return isClaudeOnBedrock(model);
                    case "openai":
                        // gpt-5.x on Bedrock accepts "none"
                        return caps.get().getEffortLevels().contains("none");
                    case "amazon":
                        // Nova 2 carries an explicit toggle in the data
                        return caps.get().isToggleable();
                    default:
                        return false;
                }
            case "openai":
                return caps.get().getEffortLevels().contains("none");
            case "google":
                return caps.get().isToggleable();
            default:
                return false;
        }
    }

    /**
     * The ordered effort levels the model accepts, straight from the vendored
     * catalog; budget-shaped models (no named levels) get the synthetic
     * low/medium/high labels mapped onto their budget range. Empty list when
     * the model has no effort control.
     *
     * @param prefixedModel the model id from the selector (may be null)
     */
    public static List<String> effortLevels(String prefixedModel) {
        String provider = providerOf(prefixedModel);
        String model = bareModelId(prefixedModel);
        if (model.isEmpty()) {
            return Collections.emptyList();
        }
        if ("ollama".equals(provider)) {
            return ollamaSupportsThinking(model) ? LOW_MEDIUM_HIGH : Collections.emptyList();
        }
        Optional<ModelCapabilityCatalog.CapabilityInfo> caps =
                catalog().capabilities(prefixedModel);
        if (caps.isEmpty() || !caps.get().isReasoning()) {
            return Collections.emptyList();
        }
        if (!caps.get().getEffortLevels().isEmpty()) {
            return caps.get().getEffortLevels();
        }
        return caps.get().hasBudget() ? LOW_MEDIUM_HIGH : Collections.emptyList();
    }

    /**
     * Maps an effort level to the Anthropic thinking budget in tokens,
     * overridable via {@code anthropic.thinking.budget.<level>}.
     * Unknown levels fall back to the medium budget.
     */
    public static long anthropicBudgetTokens(String effort) {
        String level = effort == null ? "medium" : effort.trim().toLowerCase(Locale.ROOT);
        long fallback;
        switch (level) {
            case "low":
                fallback = DEFAULT_ANTHROPIC_BUDGET_LOW;
                break;
            case "high":
                fallback = DEFAULT_ANTHROPIC_BUDGET_HIGH;
                break;
            case "xhigh":
                fallback = DEFAULT_ANTHROPIC_BUDGET_XHIGH;
                break;
            case "max":
                fallback = DEFAULT_ANTHROPIC_BUDGET_MAX;
                break;
            default:
                level = "medium";
                fallback = DEFAULT_ANTHROPIC_BUDGET_MEDIUM;
                break;
        }
        return parseLong(
                AiConfig.getProperty("anthropic.thinking.budget." + level, String.valueOf(fallback)),
                fallback);
    }

    /**
     * Maps an effort level to a Gemini thinking budget. Flash models accept a
     * budget of 0 (thinking off); unknown levels fall back to medium.
     */
    public static int googleThinkingBudget(String effort) {
        String level = effort == null ? "medium" : effort.trim().toLowerCase(Locale.ROOT);
        switch (level) {
            case "low":
                return 1024;
            case "high":
                return 24576;
            default:
                return 8192;
        }
    }

    /** The provider segment of a prefixed model id (bare ids are Anthropic). */
    static String providerOf(String prefixedModel) {
        if (prefixedModel == null || prefixedModel.isEmpty()) {
            return "";
        }
        int colon = prefixedModel.indexOf(':');
        if (colon <= 0) {
            return "anthropic";
        }
        return prefixedModel.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    /** The bare model id without its provider prefix. */
    static String bareModelId(String prefixedModel) {
        if (prefixedModel == null) {
            return "";
        }
        int colon = prefixedModel.indexOf(':');
        String bare = colon >= 0 ? prefixedModel.substring(colon + 1) : prefixedModel;
        return bare.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * True for Claude models that use adaptive thinking (the claude 5 "fable"
     * family): the model decides when and how deeply to think, so the request
     * carries no budget - the catalog's effort values go via output_config.
     */
    static boolean isAdaptiveClaude(String model) {
        if (model == null || model.isEmpty()) {
            return false;
        }
        return model.contains("fable")
                || model.matches("claude-[a-z]+-5([.-].*)?");
    }

    /** Bedrock Claude ids carry an {@code anthropic.} namespace - drop it. */
    static String stripBedrockNamespace(String model) {
        return model != null && model.startsWith("anthropic.") ? model.substring(10) : model;
    }

    /** True when a Bedrock model id refers to a Claude model. */
    static boolean isClaudeOnBedrock(String model) {
        return stripBedrockNamespace(model).startsWith("claude-");
    }

    /**
     * Drops a leading Bedrock region/inference-profile segment
     * ({@code us.}, {@code eu.}, {@code global.}, {@code apac.},
     * {@code us-gov-east-1.}, ...) so the vendor family id remains
     * ({@code anthropic.claude-...}, {@code amazon.nova-...}).
     */
    static String stripBedrockRegion(String model) {
        if (model == null) {
            return "";
        }
        int dot = model.indexOf('.');
        if (dot < 0) {
            return model;
        }
        String first = model.substring(0, dot);
        if (first.length() == 2 || first.equals("global")
                || first.contains("gov") || first.equals("apac")) {
            return model.substring(dot + 1);
        }
        return model;
    }

    /**
     * The vendor family of a Bedrock model id after region stripping:
     * {@code anthropic}, {@code amazon}, {@code openai}, {@code deepseek}, ...
     */
    static String bedrockFamily(String model) {
        String stripped = stripBedrockRegion(model);
        int dot = stripped.indexOf('.');
        return dot < 0 ? stripped : stripped.substring(0, dot);
    }

    private static ModelCapabilityCatalog catalog() {
        return ModelCapabilityCatalog.getInstance();
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
