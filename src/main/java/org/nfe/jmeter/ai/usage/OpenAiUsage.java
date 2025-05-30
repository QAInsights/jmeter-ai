package org.nfe.jmeter.ai.usage;

import org.nfe.jmeter.ai.utils.AiConfig;
// import com.openai.models.ChatCompletion; // OpenAI SDK removed
// import com.openai.models.CompletionUsage; // OpenAI SDK removed
// import com.openai.client.OpenAIClient; // OpenAI SDK removed
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // OpenAI client for API calls - Removed
    // private OpenAIClient client;

    // Store usage history - This will no longer be populated with OpenAI specific data
    private final List<UsageRecord> usageHistory = new ArrayList<>();

    // Private constructor for singleton
    private OpenAiUsage() {
        // initializeClient(); // OpenAI-specific client initialization removed
        log.info("OpenAiUsage instance created. OpenAI-specific client initialization removed.");
    }

    /**
     * Initialize the OpenAI client - Method Removed as OpenAI SDK is removed.
     */
    // private void initializeClient() { ... }

    /**
     * Get the singleton instance of OpenAiUsage.
     *
     * @return The singleton instance
     */
    public static OpenAiUsage getInstance() {
        return INSTANCE;
    }

    /**
     * Record usage. This method is now a no-op as OpenAI SDK has been removed.
     * It can be adapted for custom API usage if needed in the future.
     *
     * @param completion The completion response (now generic Object)
     * @param model      The model used for the completion
     */
    public void recordUsage(Object completion, String model) {
        log.warn("recordUsage called, but OpenAI SDK is removed. Usage tracking for the custom API is not implemented here. No usage recorded.");
        // This method previously processed com.openai.models.ChatCompletion.
        // As the SDK is removed, this is now a no-op or logs a warning.
        // If custom API provides usage data, this method could be refactored.
        // For now, it does nothing to avoid compilation errors.

        // Example of how it might be adapted if custom API provided similar data:
        /*
        if (completion instanceof CustomApiResponse) { // Assuming a hypothetical CustomApiResponse
            CustomApiResponse customResponse = (CustomApiResponse) completion;
            if (customResponse.getUsage() != null) {
                long promptTokens = customResponse.getUsage().getPromptTokens();
                long completionTokens = customResponse.getUsage().getCompletionTokens();
                long totalTokens = customResponse.getUsage().getTotalTokens();
                String cleanModelName = model; // Or derive from customResponse

                UsageRecord record = new UsageRecord(
                        new Date(),
                        cleanModelName,
                        promptTokens,
                        completionTokens,
                        totalTokens);
                usageHistory.add(record);
                log.info("Recorded usage for custom API: {}", record);
            }
        } else if (completion != null) {
            log.warn("Unsupported completion object type for usage recording: {}", completion.getClass().getName());
        }
        */
    }

    /**
     * Set the client for usage tracking. This method is now a no-op.
     * 
     * @param client The client object (now generic Object, formerly OpenAIClient)
     */
    public void setClient(Object client) {
        // this.client = client; // OpenAI client field removed
        log.warn("setClient called, but OpenAI SDK is removed. Client setting is no longer applicable here.");
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
