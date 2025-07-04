package org.qainsights.jmeter.ai.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Class to track and provide AWS Bedrock token usage information.
 */
public class BedrockUsage {
    private static final Logger log = LoggerFactory.getLogger(BedrockUsage.class);

    // Singleton instance
    private static final BedrockUsage INSTANCE = new BedrockUsage();

    // Store usage history
    private final List<UsageRecord> usageHistory = new ArrayList<>();

    // Private constructor for singleton
    private BedrockUsage() {
        log.info("BedrockUsage tracker initialized");
    }

    /**
     * Get the singleton instance of BedrockUsage.
     *
     * @return The singleton instance
     */
    public static BedrockUsage getInstance() {
        return INSTANCE;
    }

    /**
     * Record usage from a Bedrock API call.
     *
     * @param model            The model used for the completion
     * @param inputTokens      The number of input tokens
     * @param outputTokens     The number of output tokens
     */
    public void recordUsage(String model, long inputTokens, long outputTokens) {
        if (model == null || model.isEmpty()) {
            log.warn("Unable to record usage - model is null or empty");
            return;
        }

        try {
            long totalTokens = inputTokens + outputTokens;

            // Record usage
            UsageRecord record = new UsageRecord(
                    new Date(),
                    model,
                    inputTokens,
                    outputTokens,
                    totalTokens);

            usageHistory.add(record);
            log.info("Recorded usage: {}", record);
        } catch (Exception e) {
            log.error("Error recording usage", e);
        }
    }

    /**
     * Get usage summary as a formatted string.
     *
     * @return The usage summary
     */
    public String getUsageSummary() {
        if (usageHistory.isEmpty()) {
            return "No AWS Bedrock usage data available. Try using the Bedrock service first.";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("# AWS Bedrock Usage Summary\n\n");

        // Summary totals
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        long totalTokens = 0;

        // Calculate totals
        for (UsageRecord record : usageHistory) {
            totalInputTokens += record.inputTokens;
            totalOutputTokens += record.outputTokens;
            totalTokens += record.totalTokens;
        }

        // Add summary information
        summary.append("## Overall Summary\n");
        summary.append("- **Total Conversations**: ").append(usageHistory.size()).append("\n");
        summary.append("- **Total Input Tokens**: ").append(totalInputTokens).append("\n");
        summary.append("- **Total Output Tokens**: ").append(totalOutputTokens).append("\n");
        summary.append("- **Total Tokens**: ").append(totalTokens).append("\n\n");

        // Add pricing note
        summary.append("## Pricing Information\n");
        summary.append("For up-to-date pricing information, please visit AWS Bedrock's official pricing page:\n");
        summary.append("https://aws.amazon.com/bedrock/pricing/\n\n");
        summary.append("AWS Bedrock pricing varies by model and region and may change over time.\n");
        summary.append("Claude models on Bedrock typically charge per 1,000 tokens for input and output separately.\n\n");

        // Add cost estimation for common models
        summary.append("## Estimated Cost (USD)\n");
        summary.append("*Note: These are rough estimates based on typical Bedrock pricing. Actual costs may vary.*\n\n");
        
        // Estimate costs for Claude models (approximate pricing as of 2024)
        double estimatedCost = 0.0;
        for (UsageRecord record : usageHistory) {
            if (record.model.contains("claude-3-sonnet")) {
                // Claude 3 Sonnet: ~$0.003 per 1K input tokens, ~$0.015 per 1K output tokens
                estimatedCost += (record.inputTokens / 1000.0) * 0.003;
                estimatedCost += (record.outputTokens / 1000.0) * 0.015;
            } else if (record.model.contains("claude-3-haiku")) {
                // Claude 3 Haiku: ~$0.00025 per 1K input tokens, ~$0.00125 per 1K output tokens
                estimatedCost += (record.inputTokens / 1000.0) * 0.00025;
                estimatedCost += (record.outputTokens / 1000.0) * 0.00125;
            } else if (record.model.contains("claude-3-opus")) {
                // Claude 3 Opus: ~$0.015 per 1K input tokens, ~$0.075 per 1K output tokens
                estimatedCost += (record.inputTokens / 1000.0) * 0.015;
                estimatedCost += (record.outputTokens / 1000.0) * 0.075;
            } else {
                // Default estimation for unknown models
                estimatedCost += (record.inputTokens / 1000.0) * 0.003;
                estimatedCost += (record.outputTokens / 1000.0) * 0.015;
            }
        }
        
        summary.append("- **Estimated Total Cost**: $").append(String.format("%.4f", estimatedCost)).append("\n\n");

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
            summary.append("- **Input Tokens**: ").append(record.inputTokens).append("\n");
            summary.append("- **Output Tokens**: ").append(record.outputTokens).append("\n");
            summary.append("- **Total Tokens**: ").append(record.totalTokens).append("\n\n");
        }

        return summary.toString();
    }

    /**
     * Get the total number of conversations recorded.
     *
     * @return The total number of conversations
     */
    public int getTotalConversations() {
        return usageHistory.size();
    }

    /**
     * Get the total input tokens used across all conversations.
     *
     * @return The total input tokens
     */
    public long getTotalInputTokens() {
        return usageHistory.stream().mapToLong(record -> record.inputTokens).sum();
    }

    /**
     * Get the total output tokens used across all conversations.
     *
     * @return The total output tokens
     */
    public long getTotalOutputTokens() {
        return usageHistory.stream().mapToLong(record -> record.outputTokens).sum();
    }

    /**
     * Get the total tokens used across all conversations.
     *
     * @return The total tokens
     */
    public long getTotalTokens() {
        return usageHistory.stream().mapToLong(record -> record.totalTokens).sum();
    }

    /**
     * Clear all usage history.
     */
    public void clearHistory() {
        usageHistory.clear();
        log.info("Cleared all usage history");
    }

    /**
     * Class to store a single usage record.
     */
    private static class UsageRecord {
        private final Date timestamp;
        private final String model;
        private final long inputTokens;
        private final long outputTokens;
        private final long totalTokens;

        public UsageRecord(Date timestamp, String model, long inputTokens, long outputTokens, long totalTokens) {
            this.timestamp = timestamp;
            this.model = model;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalTokens = totalTokens;
        }

        @Override
        public String toString() {
            return "UsageRecord{" +
                    "timestamp=" + timestamp +
                    ", model='" + model + '\'' +
                    ", inputTokens=" + inputTokens +
                    ", outputTokens=" + outputTokens +
                    ", totalTokens=" + totalTokens +
                    '}';
        }
    }
}