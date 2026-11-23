package org.qainsights.jmeter.ai.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.anthropic.models.messages.Message;
import com.google.genai.types.GenerateContentResponse;
import com.openai.models.chat.completions.ChatCompletion;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.claude.ClaudeChatModel;
import org.qainsights.jmeter.ai.agent.claude.ClaudeToolAdapter;
import org.qainsights.jmeter.ai.agent.google.GoogleChatModel;
import org.qainsights.jmeter.ai.agent.google.GoogleToolAdapter;
import org.qainsights.jmeter.ai.agent.openai.OpenAiChatModel;
import org.qainsights.jmeter.ai.agent.openai.OpenAiToolAdapter;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

class AgentProviderUsageTest {
    private static final List<ToolSpec> TOOLS = List.of(ToolSpec.builder("read").description("Read plan").build());

    @Test
    void anthropicReportsSeparateCacheBucketsAndThinking() throws Exception {
        var mapper = com.anthropic.core.ObjectMappers.jsonMapper();
        Message reply = mapper.readValue("""
                {"id":"m","role":"assistant","type":"message","model":"claude",
                 "content":[{"type":"text","text":"done"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":100,"cache_read_input_tokens":200,
                          "cache_creation_input_tokens":50,"output_tokens":40,
                          "output_tokens_details":{"thinking_tokens":12}}}
                """, Message.class);
        var model = new ClaudeChatModel(params -> reply, new ClaudeToolAdapter(), TOOLS, "system", "claude", 500);
        assertUsage(model, "100 | 200 | 50 | 40 | 12");
    }

    @Test
    void openAiSeparatesCachedInputAndReasoning() throws Exception {
        assertUsage(openAi("""
                ,"usage":{"prompt_tokens":300,"completion_tokens":60,"total_tokens":360,
                          "prompt_tokens_details":{"cached_tokens":100,"cache_write_tokens":20},
                          "completion_tokens_details":{"reasoning_tokens":15}}
                """), "180 | 100 | 20 | 60 | 15");
    }

    @Test
    void deepSeekUsesItsExplicitCacheHitsAndMisses() throws Exception {
        assertUsage(openAi("""
                ,"usage":{"prompt_tokens":300,"completion_tokens":40,
                          "prompt_cache_hit_tokens":200,"prompt_cache_miss_tokens":100}
                """), "100 | 200 | - | 40 | -");
    }

    @Test
    void gatewayMissingUsageStillRecordsRequestSizing() throws Exception {
        assertUsage(openAi(""), "- | - | - | - | -");
    }

    @Test
    void geminiSeparatesCachedPromptTokensAndIncludesThoughtsInOutput() {
        var reply = GenerateContentResponse.fromJson("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"done"}]}}],
                 "usageMetadata":{"promptTokenCount":300,"cachedContentTokenCount":100,
                                  "candidatesTokenCount":40,"thoughtsTokenCount":20}}
                """);
        var model = new GoogleChatModel((id, contents, config) -> reply,
                new GoogleToolAdapter(), TOOLS, "system", "gemini", 500);
        assertUsage(model, "200 | 100 | - | 60 | 20");
    }

    @Test
    void geminiCountsToolUsePromptTokensAsFreshInput() {
        var reply = GenerateContentResponse.fromJson("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"done"}]}}],
                 "usageMetadata":{"promptTokenCount":300,"cachedContentTokenCount":100,
                                  "toolUsePromptTokenCount":30,"candidatesTokenCount":40,
                                  "thoughtsTokenCount":20,"totalTokenCount":390}}
                """);
        var model = new GoogleChatModel((id, contents, config) -> reply,
                new GoogleToolAdapter(), TOOLS, "system", "gemini", 500);
        assertUsage(model, "230 | 100 | - | 60 | 20");
        assertEquals(390, model.usageTracker().totalTokens());
    }

    @Test
    void geminiKeepsReportedUsageWhenPromptIsBlocked() {
        var reply = GenerateContentResponse.fromJson("""
                {"promptFeedback":{"blockReason":"SAFETY"},
                 "usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":20,"totalTokenCount":100}}
                """);
        var model = new GoogleChatModel((id, contents, config) -> reply,
                new GoogleToolAdapter(), TOOLS, "system", "gemini", 500);
        assertRejectedUsage(model, "Gemini blocked the agent request", "80 | 20 | - | - | -", 100);
    }

    @Test
    void openAiKeepsReportedUsageWhenChoicesAreEmpty() throws Exception {
        var reply = com.openai.core.ObjectMappers.jsonMapper().readValue("""
                {"id":"c","object":"chat.completion","created":0,"model":"gpt-4o","choices":[],
                 "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105,
                          "prompt_tokens_details":{"cached_tokens":20}}}
                """, ChatCompletion.class);
        var model = new OpenAiChatModel(params -> reply, new OpenAiToolAdapter(), TOOLS, "system", "gpt-4o", 500);
        assertRejectedUsage(model, "OpenAI returned no choices", "80 | 20 | - | 5 | -", 105);
    }

    private static void assertRejectedUsage(ChatModel model, String message, String counts, long total) {
        var exception = assertThrows(IllegalStateException.class, () -> new AgentLoop(model, null, 1).run("request", null));
        assertTrue(exception.getMessage().contains(message), exception.getMessage());
        var usage = model.usageTracker();
        assertEquals(1, usage.turns());
        assertEquals(0, usage.unreportedTurns());
        assertEquals(total, usage.totalTokens());
        String summary = usage.summary();
        String row = summary.lines().filter(line -> line.startsWith("1 | ")).findFirst().orElseThrow();
        assertTrue(row.startsWith("1 | " + counts + " | 1 | "), summary);
        assertTrue(row.endsWith(" | failed"), summary);
        assertTrue(summary.contains("total | " + counts), summary);
    }

    private static ChatModel openAi(String usage) throws Exception {
        var reply = com.openai.core.ObjectMappers.jsonMapper().readValue("""
                {"id":"c","object":"chat.completion","created":0,"model":"gpt-4o",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"done"}}]
                """ + usage + "}", ChatCompletion.class);
        return new OpenAiChatModel(params -> reply, new OpenAiToolAdapter(), TOOLS, "system", "gpt-4o", 500);
    }

    private static void assertUsage(ChatModel model, String counts) {
        var result = new AgentLoop(model, null, 1).run("request", null);
        assertTrue(result.isCompleted());
        String summary = result.getUsageSummary();
        String row = summary.lines().filter(line -> line.startsWith("1 | ")).findFirst().orElseThrow();
        String prefix = "1 | " + counts + " | 1 | ";
        assertTrue(row.startsWith(prefix), summary);
        String[] sizes = row.substring(prefix.length()).split(" \\| ");
        assertTrue(Integer.parseInt(sizes[0]) > 0, summary);
        assertTrue(Integer.parseInt(sizes[1]) > Integer.parseInt(sizes[0]), summary);
        assertTrue(summary.contains("total | " + counts), summary);
        assertFalse(summary.contains("system"), summary);
    }
}
