package org.qainsights.jmeter.ai.service;

public enum ProviderSetupOption {
    ANTHROPIC(
            "Anthropic Claude",
            "Chat and Agent Mode",
            "Connect Claude models for chat and Agent Mode.",
            "anthropic.api.key",
            "anthropic",
            "https://console.anthropic.com/settings/keys"),
    OPENAI(
            "OpenAI",
            "Chat and Agent Mode",
            "Connect GPT models for chat and Agent Mode.",
            "openai.api.key",
            "openai",
            "https://platform.openai.com/api-keys"),
    GEMINI(
            "Google Gemini",
            "Chat and Agent Mode, AI Studio key",
            "Connect Gemini models with an AI Studio key.",
            "google.api.key",
            "google",
            "https://aistudio.google.com/apikey"),
    DEEPSEEK(
            "DeepSeek",
            "Chat and Agent Mode",
            "Connect DeepSeek models through its OpenAI-compatible API.",
            "deepseek.api.key",
            "deepseek",
            "https://platform.deepseek.com/api_keys"),
    GROK(
            "Grok",
            "Chat and Agent Mode",
            "Connect xAI models through its OpenAI-compatible API.",
            "grok.api.key",
            "grok",
            "https://console.x.ai"),
    META_MUSE(
            "Meta Muse",
            "Chat and Agent Mode",
            "Connect Muse Spark through its OpenAI-compatible API.",
            "meta.api.key",
            "meta",
            null),
    OLLAMA(
            "Ollama",
            "Local models, no API key",
            "Run models locally with no API key.",
            null,
            "ollama",
            null);

    private final String displayName;
    private final String listHint;
    private final String description;
    private final String keyProperty;
    private final String serviceType;
    private final String consoleUrl;

    ProviderSetupOption(
            String displayName,
            String listHint,
            String description,
            String keyProperty,
            String serviceType,
            String consoleUrl) {
        this.displayName = displayName;
        this.listHint = listHint;
        this.description = description;
        this.keyProperty = keyProperty;
        this.serviceType = serviceType;
        this.consoleUrl = consoleUrl;
    }

    public String displayName() {
        return displayName;
    }

    public String listHint() {
        return listHint;
    }

    public String description() {
        return description;
    }

    public String keyProperty() {
        return keyProperty;
    }

    public String serviceType() {
        return serviceType;
    }

    public String consoleUrl() {
        return consoleUrl;
    }

    public boolean requiresKey() {
        return keyProperty != null;
    }
}
