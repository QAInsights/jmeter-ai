package org.qainsights.jmeter.ai.service;

/**
 * Data holder class that bundles all AI service implementations.
 * This keeps constructor parameter lists short (<= 5) and simplifies wiring.
 */
public class AiServiceHolder {
    private ClaudeService claudeService;
    private OpenAiService openAiService;
    private OllamaAiService ollamaService;
    private DeepseekAiService deepseekService;
    private GoogleAiService googleService;
    private GrokAiService grokService;
    private MetaMuseAiService metaMuseService;

    public ClaudeService getClaudeService() {
        return claudeService;
    }

    public void setClaudeService(ClaudeService claudeService) {
        this.claudeService = claudeService;
    }

    public OpenAiService getOpenAiService() {
        return openAiService;
    }

    public void setOpenAiService(OpenAiService openAiService) {
        this.openAiService = openAiService;
    }

    public OllamaAiService getOllamaService() {
        return ollamaService;
    }

    public void setOllamaService(OllamaAiService ollamaService) {
        this.ollamaService = ollamaService;
    }

    public DeepseekAiService getDeepseekService() {
        return deepseekService;
    }

    public void setDeepseekService(DeepseekAiService deepseekService) {
        this.deepseekService = deepseekService;
    }

    public GoogleAiService getGoogleService() {
        return googleService;
    }

    public void setGoogleService(GoogleAiService googleService) {
        this.googleService = googleService;
    }

    public GrokAiService getGrokService() {
        return grokService;
    }

    public void setGrokService(GrokAiService grokService) {
        this.grokService = grokService;
    }

    public MetaMuseAiService getMetaMuseService() {
        return metaMuseService;
    }

    public void setMetaMuseService(MetaMuseAiService metaMuseService) {
        this.metaMuseService = metaMuseService;
    }
}
