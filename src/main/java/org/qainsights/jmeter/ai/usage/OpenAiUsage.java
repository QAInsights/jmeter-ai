package org.qainsights.jmeter.ai.usage;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.completions.CompletionUsage;
import com.openai.client.OpenAIClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Optional;
import java.util.List;

/**
 * Class to track and provide OpenAI token usage information.
 */
public class OpenAiUsage {
    private static final Logger log = LoggerFactory.getLogger(OpenAiUsage.class);

    // Singleton instance
    private static final OpenAiUsage INSTANCE = new OpenAiUsage();

    // OpenAI client for API calls
    private OpenAIClient client;

    // Store usage history
    private final List<UsageRecord> usageHistory = new ArrayList<>();

    // Private constructor for singleton
    private OpenAiUsage() {
        initializeClient();
    }

    /**
     * Initialize the OpenAI client
     */
    private void initializeClient() {
        try {
            String apiKey = AiConfig.getProperty("openai.api.key", "");
            if (apiKey.isEmpty()) {
                log.warn("OpenAI API key is empty. Token usage information may not be accurate.");
            }

            // Initialize the client
            client = new com.openai.client.okhttp.OpenAIOkHttpClient.Builder()
                    .apiKey(apiKey)
                    .build();

            log.info("OpenAI client initialized for usage tracking");
        } catch (Exception e) {
            log.error("Failed to initialize OpenAI client for usage tracking", e);
        }
    }

    /**
     * Get the singleton instance of OpenAiUsage.
     *
     * @return The singleton instance
     */
    public static OpenAiUsage getInstance() {
        return INSTANCE;
    }

    /**
     * Record usage from a ChatCompletion response.
     *
     * @param completion The ChatCompletion response from OpenAI
     * @param model      The model used for the completion
     */
    public void recordUsage(ChatCompletion completion, String model) {
        if (completion == null || completion.usage() == null) {
            log.warn("Unable to record usage - completion or usage data is null");
            return;
        }

        try {
            // Get the CompletionUsage from the Optional
            Optional<CompletionUsage> usageOptional = completion.usage();
            if (!usageOptional.isPresent()) {
                log.warn("CompletionUsage is not present in the response");
                return;
            }

            CompletionUsage usage = usageOptional.get();
            long promptTokens = usage.promptTokens();
            long completionTokens = usage.completionTokens();
            long totalTokens = usage.totalTokens();

            // Clean up model name (remove potential "openai:" prefix)
            String cleanModelName = model.startsWith("openai:") ? model.substring(7) : model;

            // Record usage without cost calculation
            UsageRecord record = new UsageRecord(
                    new Date(),
                    cleanModelName,
                    promptTokens,
                    completionTokens,
                    totalTokens);

            usageHistory.add(record);
            log.info("Recorded usage: {}", record);
        } catch (Exception e) {
            log.error("Error recording usage", e);
        }
    }

    /**
     * Set the OpenAI client for usage tracking
     * 
     * @param client The OpenAI client to use
     */
    public void setClient(OpenAIClient client) {
        this.client = client;
        log.info("OpenAI client set for usage tracking");
    }

    /**
     * Get usage summary as a formatted string.
     *
     * @return The usage summary
     */
    public String getUsageSummary() {
        if (usageHistory.isEmpty()) {
            return "No OpenAI usage data available. Try using the OpenAI service first.";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("# OpenAI Usage Summary\n\n");

        // Summary totals
        long totalPromptTokens = 0;
        long totalCompletionTokens = 0;
        long totalTokens = 0;

        // Calculate totals
        for (UsageRecord record : usageHistory) {
            totalPromptTokens += record.promptTokens;
            totalCompletionTokens += record.completionTokens;
            totalTokens += record.totalTokens;
        }

        // Add summary information
        summary.append("## Overall Summary\n");
        summary.append("- **Total Conversations**: ").append(usageHistory.size()).append("\n");
        summary.append("- **Total Input Tokens**: ").append(totalPromptTokens).append("\n");
        summary.append("- **Total Output Tokens**: ").append(totalCompletionTokens).append("\n");
        summary.append("- **Total Tokens**: ").append(totalTokens).append("\n\n");

        // Add pricing note
        summary.append("## Pricing Information\n");
        summary.append("For up-to-date pricing information, please visit OpenAI's official pricing page:\n");
        summary.append("https://openai.com/api/pricing/\n\n");
        summary.append("OpenAI pricing varies by model and may change over time.\n\n");

        // Add detail for the last 10 conversations using a more readable format
        summary.append("## Recent Conversations\n");

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        // Get the most recent 10 records or fewer if less than 10 exist
        int startIndex = Math.max(0, usageHistory.size() - 10);
        for (int i = startIndex; i < usageHistory.size(); i++) {
            UsageRecord record = usageHistory.get(i);
            summary.append("### Conversation ").append(i + 1 - startIndex).append("\n");
            summary.append("- **Date**: ").append(dateFormat.format(record.timestamp)).append("\n");
            summary.append("- **Model**: ").append(record.model).append("\n");
            summary.append("- **Input Tokens**: ").append(record.promptTokens).append("\n");
            summary.append("- **Output Tokens**: ").append(record.completionTokens).append("\n");
            summary.append("- **Total Tokens**: ").append(record.totalTokens).append("\n\n");
        }

        return summary.toString();
    }

    /**
     * Class to store a single usage record.
     */
    private static class UsageRecord {
        private final Date timestamp;
        private final String model;
        private final long promptTokens;
        private final long completionTokens;
        private final long totalTokens;

        public UsageRecord(Date timestamp, String model, long promptTokens, long completionTokens,
                long totalTokens) {
            this.timestamp = timestamp;
            this.model = model;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        @Override
        public String toString() {
            return "UsageRecord{" +
                    "timestamp=" + timestamp +
                    ", model='" + model + '\'' +
                    ", promptTokens=" + promptTokens +
                    ", completionTokens=" + completionTokens +
                    ", totalTokens=" + totalTokens +
                    '}';
        }
    }
}
