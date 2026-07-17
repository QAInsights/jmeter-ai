package org.qainsights.jmeter.ai.service;

import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.Constants;
import org.qainsights.jmeter.ai.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.FoundationModelSummary;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileModel;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileSummary;
import software.amazon.awssdk.services.bedrock.model.ListFoundationModelsResponse;
import software.amazon.awssdk.services.bedrock.model.ListInferenceProfilesResponse;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * AI service implementation for AWS Bedrock.
 * <p>
 * Uses the Bedrock Runtime {@code Converse} / {@code ConverseStream} APIs, which
 * provide a unified request/response schema across supported model providers
 * (Anthropic, Meta, Mistral, etc.) so no model-family-specific payload
 * formatting is required.
 *
 * @see <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference.html">Bedrock Converse API</a>
 */
public class BedrockAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(BedrockAiService.class);

    private final BedrockRuntimeClient runtimeClient;
    private final BedrockRuntimeAsyncClient asyncClient;
    private final BedrockClient bedrockClient;
    private final BedrockConverseClient converseClient;
    private final int maxHistorySize;
    private final String systemPrompt;
    private String model;
    private final float temperature;
    private final long maxTokens;
    private final List<String> modelProviders;

    public BedrockAiService() {
        String apiKey = AiConfig.getProperty("bedrock.api.key", "");
        String accessKey = AiConfig.getProperty("bedrock.aws.access.key", "");
        String secretKey = AiConfig.getProperty("bedrock.aws.secret.key", "");
        String regionStr = AiConfig.getProperty("bedrock.aws.region", "us-east-1");
        Region region = Region.of(regionStr);

        this.model = AiConfig.getProperty("bedrock.default.model",
                "anthropic.claude-3-5-sonnet-20241022-v2:0");
        this.temperature = ModelUtils.parseTemperature(
                AiConfig.getProperty("bedrock.temperature", "0.5"));
        this.maxHistorySize = Integer.parseInt(
                AiConfig.getProperty("bedrock.max.history.size", "10"));
        this.maxTokens = Long.parseLong(
                AiConfig.getProperty("bedrock.max.tokens", "4096"));

        String configuredPrompt = AiConfig.getProperty("bedrock.system.prompt", "");
        this.systemPrompt = (configuredPrompt != null && !configuredPrompt.isEmpty())
                ? configuredPrompt : Constants.DEFAULT_JMETER_SYSTEM_PROMPT;

        String providersStr = AiConfig.getProperty("bedrock.model.providers", "Anthropic");
        this.modelProviders = new ArrayList<>();
        for (String p : providersStr.split(",")) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                this.modelProviders.add(trimmed);
            }
        }
        log.info("Bedrock model providers filter: {}", this.modelProviders);

        BedrockRuntimeClient syncClient;
        BedrockRuntimeAsyncClient streamClient;
        BedrockClient listClient;

        if (apiKey != null && !apiKey.isEmpty()) {
            log.info("Using Bedrock API key (bearer token) authentication");
            System.setProperty("aws.bearerTokenBedrock", apiKey);
            syncClient = BedrockRuntimeClient.builder()
                    .region(region)
                    .httpClient(UrlConnectionHttpClient.builder().build())
                    .build();
            streamClient = BedrockRuntimeAsyncClient.builder()
                    .region(region)
                    .httpClient(NettyNioAsyncHttpClient.builder().build())
                    .build();
            listClient = BedrockClient.builder()
                    .region(region)
                    .httpClient(UrlConnectionHttpClient.builder().build())
                    .build();
        } else if (accessKey != null && !accessKey.isEmpty()
                && secretKey != null && !secretKey.isEmpty()) {
            log.info("Using Bedrock IAM access key authentication");
            StaticCredentialsProvider creds = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));
            syncClient = BedrockRuntimeClient.builder()
                    .credentialsProvider(creds).region(region)
                    .httpClient(UrlConnectionHttpClient.builder().build())
                    .build();
            streamClient = BedrockRuntimeAsyncClient.builder()
                    .credentialsProvider(creds).region(region)
                    .httpClient(NettyNioAsyncHttpClient.builder().build())
                    .build();
            listClient = BedrockClient.builder()
                    .credentialsProvider(creds).region(region)
                    .httpClient(UrlConnectionHttpClient.builder().build())
                    .build();
        } else {
            log.info("Bedrock credentials not explicitly set, using default credential chain");
            DefaultCredentialsProvider defaultCreds = DefaultCredentialsProvider.create();
            syncClient = BedrockRuntimeClient.builder()
                    .credentialsProvider(defaultCreds).region(region)
                    .httpClient(UrlConnectionHttpClient.builder().build())
                    .build();
            streamClient = BedrockRuntimeAsyncClient.builder()
                    .credentialsProvider(defaultCreds).region(region)
                    .httpClient(NettyNioAsyncHttpClient.builder().build())
                    .build();
            listClient = BedrockClient.builder()
                    .credentialsProvider(defaultCreds).region(region)
                    .httpClient(UrlConnectionHttpClient.builder().build())
                    .build();
        }

        this.runtimeClient = syncClient;
        this.asyncClient = streamClient;
        this.bedrockClient = listClient;
        this.converseClient = new BedrockConverseClient(syncClient, streamClient);

        log.info("Initialized Bedrock service with region: {}, model: {}, temperature: {}",
                regionStr, this.model, this.temperature);
    }

    /** Package-private constructor for testing. */
    BedrockAiService(BedrockRuntimeClient runtimeClient,
                     BedrockRuntimeAsyncClient asyncClient,
                     BedrockClient bedrockClient,
                     String model, float temperature, int maxHistorySize,
                     long maxTokens, String systemPrompt) {
        this(runtimeClient, asyncClient, bedrockClient, model, temperature,
                maxHistorySize, maxTokens, systemPrompt,
                List.of("Anthropic"));
    }

    /** Package-private constructor for testing. */
    BedrockAiService(BedrockRuntimeClient runtimeClient,
                     BedrockRuntimeAsyncClient asyncClient,
                     BedrockClient bedrockClient,
                     String model, float temperature, int maxHistorySize,
                     long maxTokens, String systemPrompt,
                     List<String> modelProviders) {
        this.runtimeClient = runtimeClient;
        this.asyncClient = asyncClient;
        this.bedrockClient = bedrockClient;
        this.converseClient = new BedrockConverseClient(runtimeClient, asyncClient);
        this.model = model;
        this.temperature = temperature;
        this.maxHistorySize = maxHistorySize;
        this.maxTokens = maxTokens;
        this.systemPrompt = systemPrompt;
        this.modelProviders = modelProviders;
    }

    public void setModel(String modelId) {
        this.model = modelId;
        log.info("Bedrock model set to: {}", modelId);
    }

    public String getCurrentModel() {
        return model;
    }

    @Override
    public String getName() {
        return "AWS Bedrock";
    }

    @Override
    public String generateResponse(List<String> conversation) {
        return generateResponse(conversation, this.model);
    }

    @Override
    public String generateResponse(List<String> conversation, String modelId) {
        if (runtimeClient == null) {
            return "Error: Bedrock client not initialized. Set bedrock.aws.access.key and bedrock.aws.secret.key in jmeter.properties.";
        }
        try {
            String modelToUse = (modelId != null && !modelId.isEmpty()) ? modelId : this.model;
            log.debug("Bedrock Converse request for model: {}", modelToUse);
            return converseClient.generateResponse(
                    buildLimitedHistory(conversation), modelToUse,
                    systemPrompt, temperature, maxTokens);
        } catch (Exception e) {
            log.error("Error generating response from Bedrock", e);
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public Runnable generateStreamResponse(List<String> conversation, String modelId,
                                           Consumer<String> tokenConsumer,
                                           Runnable onComplete,
                                           Consumer<Exception> onError) {
        if (asyncClient == null) {
            return () -> {};
        }
        String modelToUse = (modelId != null && !modelId.isEmpty()) ? modelId : this.model;
        return converseClient.generateStreamResponse(
                buildLimitedHistory(conversation), modelToUse,
                systemPrompt, temperature, maxTokens,
                tokenConsumer, onComplete, onError);
    }

    /**
     * Lists all available foundation models from Bedrock,
     * filtered to on-demand inference and TEXT output modality.
     */
    public List<String> listModels() {
        List<String> models = new ArrayList<>();
        if (bedrockClient == null) {
            log.warn("Bedrock client is null, returning default model only");
            models.add(this.model);
            return models;
        }
        try {
            ListFoundationModelsResponse response = bedrockClient.listFoundationModels(r -> r
                    .byInferenceType("ON_DEMAND")
                    .byOutputModality("TEXT"));

            for (FoundationModelSummary summary : response.modelSummaries()) {
                String providerName = summary.providerName();
                if (modelProviders.isEmpty() || modelProviders.stream()
                        .anyMatch(p -> p.equalsIgnoreCase(providerName))) {
                    models.add(summary.modelId());
                    log.debug("Added Bedrock model: {} (provider: {})",
                            summary.modelId(), providerName);
                } else {
                    log.debug("Skipping Bedrock model {} (provider: {} not in filter)",
                            summary.modelId(), providerName);
                }
            }
            models.addAll(listInferenceProfiles());
            log.info("Retrieved {} Bedrock models and inference profiles", models.size());
        } catch (Exception e) {
            log.warn("Could not list Bedrock models (check IAM permissions for bedrock:ListFoundationModels): {}", e.getMessage());
        }
        if (models.isEmpty()) {
            log.info("No Bedrock models listed, falling back to default model: {}", this.model);
            models.add(this.model);
        }
        return models;
    }

    private List<String> listInferenceProfiles() {
        List<String> profiles = new ArrayList<>();
        String nextToken = null;
        do {
            final String pageToken = nextToken;
            ListInferenceProfilesResponse response = pageToken == null
                    ? bedrockClient.listInferenceProfiles(r -> r.typeEquals("SYSTEM_DEFINED"))
                    : bedrockClient.listInferenceProfiles(r -> r
                            .typeEquals("SYSTEM_DEFINED").nextToken(pageToken));
            for (InferenceProfileSummary summary : response.inferenceProfileSummaries()) {
                String profileId = summary.inferenceProfileId();
                if ("ACTIVE".equals(summary.statusAsString())
                        && matchesConfiguredProvider(profileId)
                        && isTextInferenceProfile(summary)
                        && isInferenceProfileAvailable(summary)) {
                    profiles.add(profileId);
                    log.debug("Added Bedrock inference profile: {}", profileId);
                }
            }
            nextToken = response.nextToken();
        } while (nextToken != null && !nextToken.isEmpty());
        return profiles;
    }

    private boolean isInferenceProfileAvailable(InferenceProfileSummary summary) {
        if (!summary.hasModels()) {
            log.debug("Skipping inference profile without foundation model sources: {}",
                    summary.inferenceProfileId());
            return false;
        }
        InferenceProfileModel source = summary.models().get(0);
        String modelArn = source.modelArn();
        String modelId = modelArn.substring(modelArn.lastIndexOf('/') + 1);
        try {
            var availability = bedrockClient.getFoundationModelAvailability(r -> r.modelId(modelId));
            boolean available = "AVAILABLE".equals(availability.agreementAvailability().statusAsString())
                    && "AUTHORIZED".equals(availability.authorizationStatusAsString())
                    && "AVAILABLE".equals(availability.entitlementAvailabilityAsString())
                    && "AVAILABLE".equals(availability.regionAvailabilityAsString());
            if (!available) {
                log.debug("Skipping unavailable Bedrock inference profile: {} ({})",
                        summary.inferenceProfileId(), modelId);
            }
            return available;
        } catch (Exception e) {
            log.debug("Could not verify Bedrock inference profile availability: {}",
                    summary.inferenceProfileId(), e);
            return false;
        }
    }

    private boolean matchesConfiguredProvider(String modelId) {
        String normalizedId = modelId == null ? "" : modelId.toLowerCase();
        return modelProviders.isEmpty() || modelProviders.stream()
                .map(String::toLowerCase)
                .anyMatch(normalizedId::contains);
    }

    private static boolean isTextInferenceProfile(InferenceProfileSummary summary) {
        String value = (summary.inferenceProfileId() + " "
                + summary.inferenceProfileName()).toLowerCase();
        return !value.matches(".*(embed|image|stable|rerank|search|control|erase|inpaint|background|recolor|style|outpaint).*" );
    }

    private List<String> buildLimitedHistory(List<String> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> clean = new ArrayList<>();
        for (String msg : conversation) {
            if (msg != null && !msg.startsWith("Error:")) {
                clean.add(msg);
            }
        }
        if (clean.size() > maxHistorySize) {
            return clean.subList(clean.size() - maxHistorySize, clean.size());
        }
        return clean;
    }
}
