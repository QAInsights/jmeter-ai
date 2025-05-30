package org.nfe.jmeter.ai.usage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.Message;
import com.anthropic.models.ModelInfo;

import org.nfe.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Class to track and provide Anthropic token usage information.
 */
public class AnthropicUsage {
    private static final Logger log = LoggerFactory.getLogger(AnthropicUsage.class);

    // Singleton instance
    private static final AnthropicUsage INSTANCE = new AnthropicUsage();

    // Anthropic client for API calls
    private AnthropicClient client;

    // Store usage history
    private final List<UsageRecord> usageHistory = new ArrayList<>();

    // Private constructor for singleton
    private AnthropicUsage() {
        initializeClient();
    }

    /**
     * Initialize the Anthropic client
     */
    private void initializeClient() {
        try {
            String apiKey = AiConfig.getProperty("anthropic.api.key", "");
            if (apiKey.isEmpty()) {
                log.warn("Anthropic API key is empty. Token usage information may not be accurate.");
            }

            // Initialize the client using the correct builder pattern
            client = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();

            log.info("Anthropic client initialized for usage tracking");
        } catch (Exception e) {
            log.error("Failed to initialize Anthropic client for usage tracking", e);
        }
    }

    /**
     * Get the singleton instance of AnthropicUsage.
     *
     * @return The singleton instance
     */
    public static AnthropicUsage getInstance() {
        return INSTANCE;
    }

    /**
     * Record usage from a Message response.
     *
     * @param message          The Message response from Anthropic
     * @param model            The model used for the completion
     * @param promptTokens     The number of prompt tokens (input)
     * @param completionTokens The number of completion tokens (output)
     */
    public void recordUsage(Message message, String model, long promptTokens, long completionTokens) {
        if (message == null) {
            log.warn("Unable to record usage - message is null");
            return;
        }

        try {
            long totalTokens = promptTokens + completionTokens;

            // Record usage
            UsageRecord record = new UsageRecord(
                    new Date(),
                    model,
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
     * Set the Anthropic client for usage tracking
     * 
     * @param client The Anthropic client to use
     */
    public void setClient(AnthropicClient client) {
        this.client = client;
        log.info("Anthropic client set for usage tracking");
    }

    /**
     * Get usage summary as a formatted string.
     *
     * @return The usage summary
     */
    public String getUsageSummary() {
        if (usageHistory.isEmpty()) {
            return "No Anthropic usage data available. Try using the Claude service first.";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("# Anthropic Usage Summary\n\n");

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
        summary.append("For up-to-date pricing information, please visit Anthropic's official pricing page:\n");
        summary.append("https://www.anthropic.com/pricing\n\n");
        summary.append("Anthropic pricing varies by model and may change over time.\n\n");

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
