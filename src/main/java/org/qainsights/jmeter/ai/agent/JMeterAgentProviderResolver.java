package org.qainsights.jmeter.ai.agent;

import org.qainsights.jmeter.ai.agent.claude.ClaudeChatModel;
import org.qainsights.jmeter.ai.agent.google.GoogleChatModel;
import org.qainsights.jmeter.ai.agent.jmeter.SwingToolConfirmationGate;
import org.qainsights.jmeter.ai.agent.openai.OpenAiChatModel;
import org.qainsights.jmeter.ai.agent.tool.ToolConfirmationGate;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.CliSubscriptionAiService;
import org.qainsights.jmeter.ai.service.DeepseekAiService;
import org.qainsights.jmeter.ai.service.GoogleAiService;
import org.qainsights.jmeter.ai.service.GrokAiService;
import org.qainsights.jmeter.ai.service.MetaMuseAiService;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.qainsights.jmeter.ai.utils.AiConfig;

import com.anthropic.client.AnthropicClient;
import com.google.genai.Client;
import com.openai.client.OpenAIClient;

final class JMeterAgentProviderResolver {

    private JMeterAgentProviderResolver() {
    }

    static JMeterAgent forClaude(ClaudeService claude) {
        long maxTokens = maxTokens();
        AnthropicClient client = claude.getClient();
        ClaudeChatModel.MessageService service = params -> client.messages().create(params);
        return new JMeterAgent(JMeterAgent.claudeFactory(service, claude.getCurrentModel(), maxTokens,
                claude.getReasoningSettings()), JMeterAgent.maxIterations(), destructiveGate());
    }

    static JMeterAgent forOpenAi(OpenAiService openAi) {
        long maxTokens = maxTokens();
        OpenAIClient client = openAi.getClient();
        OpenAiChatModel.CompletionService service = params -> client.chat().completions().create(params);
        return new JMeterAgent(JMeterAgent.openAiFactory(service, openAi.getCurrentModel(), maxTokens,
                openAi.getReasoningSettings()), JMeterAgent.maxIterations(), destructiveGate());
    }

    static JMeterAgent forDeepseek(DeepseekAiService deepseek) {
        return new JMeterAgent(factoryFor(deepseek, maxTokens()),
                JMeterAgent.maxIterations(), destructiveGate());
    }

    static JMeterAgent forGrok(GrokAiService grok) {
        return new JMeterAgent(factoryFor(grok, maxTokens()),
                JMeterAgent.maxIterations(), destructiveGate());
    }

    static JMeterAgent forMetaMuse(MetaMuseAiService metaMuse) {
        return new JMeterAgent(factoryFor(metaMuse, maxTokens()),
                JMeterAgent.maxIterations(), destructiveGate());
    }

    static JMeterAgent forGoogle(GoogleAiService google) {
        long maxTokens = maxTokens();
        Client client = google.getClient();
        GoogleChatModel.GenerateService service = (model, contents, config) ->
                client.models.generateContent(model, contents, config);
        return new JMeterAgent(JMeterAgent.googleFactory(service, google.getCurrentModel(), maxTokens,
                google.getReasoningSettings()), JMeterAgent.maxIterations(), destructiveGate());
    }

    static JMeterAgent forService(AiService service) {
        if (service instanceof ClaudeService) {
            return forClaude((ClaudeService) service);
        }
        if (service instanceof OpenAiService) {
            return forOpenAi((OpenAiService) service);
        }
        if (service instanceof GoogleAiService) {
            return forGoogle((GoogleAiService) service);
        }
        if (service instanceof DeepseekAiService) {
            return forDeepseek((DeepseekAiService) service);
        }
        if (service instanceof GrokAiService) {
            return forGrok((GrokAiService) service);
        }
        if (service instanceof MetaMuseAiService) {
            return forMetaMuse((MetaMuseAiService) service);
        }
        if (service instanceof CliSubscriptionAiService) {
            return forCli((CliSubscriptionAiService) service);
        }
        return null;
    }

    static JMeterAgent forCli(CliSubscriptionAiService service) {
        return new JMeterAgent(JMeterAgent.cliFactory(service.getProvider()),
                JMeterAgent.maxIterations(), destructiveGate());
    }

    static AgentChatModelFactory chatModelFactoryFor(AiService service) {
        long maxTokens = maxTokens();
        AgentChatModelFactory standard = standardFactoryFor(service, maxTokens);
        return standard != null ? standard : compatibleFactoryFor(service, maxTokens);
    }

    private static AgentChatModelFactory standardFactoryFor(AiService service, long maxTokens) {
        if (service instanceof ClaudeService) {
            ClaudeService claude = (ClaudeService) service;
            AnthropicClient client = claude.getClient();
            return JMeterAgent.claudeFactory(params -> client.messages().create(params),
                    claude.getCurrentModel(), maxTokens, claude.getReasoningSettings());
        }
        if (service instanceof OpenAiService) {
            OpenAiService openAi = (OpenAiService) service;
            OpenAIClient client = openAi.getClient();
            return JMeterAgent.openAiFactory(params -> client.chat().completions().create(params),
                    openAi.getCurrentModel(), maxTokens, openAi.getReasoningSettings());
        }
        if (service instanceof GoogleAiService) {
            GoogleAiService google = (GoogleAiService) service;
            Client client = google.getClient();
            return JMeterAgent.googleFactory((model, contents, config) ->
                    client.models.generateContent(model, contents, config),
                    google.getCurrentModel(), maxTokens, google.getReasoningSettings());
        }
        return null;
    }

    private static AgentChatModelFactory compatibleFactoryFor(AiService service, long maxTokens) {
        if (service instanceof DeepseekAiService) {
            return factoryFor((DeepseekAiService) service, maxTokens);
        }
        if (service instanceof GrokAiService) {
            return factoryFor((GrokAiService) service, maxTokens);
        }
        if (service instanceof MetaMuseAiService) {
            return factoryFor((MetaMuseAiService) service, maxTokens);
        }
        if (service instanceof CliSubscriptionAiService) {
            return JMeterAgent.cliFactory(((CliSubscriptionAiService) service).getProvider());
        }
        return null;
    }

    private static AgentChatModelFactory factoryFor(DeepseekAiService deepseek, long maxTokens) {
        if (deepseek.isAnthropicFormat()) {
            AnthropicClient client = deepseek.getAnthropicClient();
            return JMeterAgent.claudeFactory(params -> client.messages().create(params),
                    deepseek.getCurrentModel(), maxTokens, null);
        }
        return JMeterAgent.openAiCompatibleFactory(deepseek.getClient(), deepseek.getCurrentModel(), maxTokens);
    }

    private static AgentChatModelFactory factoryFor(GrokAiService grok, long maxTokens) {
        return JMeterAgent.openAiCompatibleFactory(grok.getClient(), grok.getCurrentModel(), maxTokens);
    }

    private static AgentChatModelFactory factoryFor(MetaMuseAiService metaMuse, long maxTokens) {
        return JMeterAgent.openAiCompatibleFactory(metaMuse.getClient(), metaMuse.getCurrentModel(), maxTokens);
    }

    private static ToolConfirmationGate destructiveGate() {
        boolean confirm = Boolean.parseBoolean(AiConfig.getProperty(JMeterAgent.CONFIRM_DESTRUCTIVE_KEY, "true"));
        return confirm ? new SwingToolConfirmationGate() : null;
    }

    private static long maxTokens() {
        return JMeterAgent.parseLong(AiConfig.getProperty(JMeterAgent.MAX_TOKENS_KEY, "4096"), 4096L);
    }
}
