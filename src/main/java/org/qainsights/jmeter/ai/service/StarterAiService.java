package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class StarterAiService implements AiService {
    public static final String MODEL_PREFIX = "free:";
    public static final String MODEL_ID = "free:feather-wand";
    public static final String DEFAULT_URL = "https://telemetry.jmeter.ai/v1/starter";
    public static final String PRIVACY_NOTICE = "Feather Wand Free sends messages through jmeter.ai to a hosted model "
            + "(NVIDIA Nemotron by default). Its free tier may retain prompts. Don't include secrets.";
    private static final String UNAVAILABLE = "Free messages are unavailable right now. Try later or connect a provider.";
    private final HttpClient client;
    private final String baseUrl;
    private final Path idPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean privacyShown = new AtomicBoolean();
    private volatile String remainingMessage = "";
    private org.qainsights.jmeter.ai.service.usage.UsageStats usageStats;

    public StarterAiService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                AiConfig.getProperty("jmeter.ai.starter.url", DEFAULT_URL),
                Path.of(System.getProperty("user.home"), ".jmeter-ai", "starter-id"));
    }

    StarterAiService(HttpClient client, String baseUrl, Path idPath) {
        this.client = client;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.idPath = idPath;
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(AiConfig.getProperty("jmeter.ai.starter.enabled", "true"));
    }

    public boolean showPrivacyNotice() {
        return privacyShown.compareAndSet(false, true);
    }

    public String getRemainingMessage() {
        return remainingMessage;
    }

    @Override public String getName() { return "Feather Wand Free"; }

    @Override public void setUsageStats(org.qainsights.jmeter.ai.service.usage.UsageStats stats) {
        usageStats = stats;
    }

    @Override public String generateResponse(List<String> conversation) {
        return generateResponse(conversation, null);
    }

    @Override public String generateResponse(List<String> conversation, String model) {
        StringBuilder answer = new StringBuilder();
        request(conversation, false, answer::append);
        return answer.toString();
    }

    @Override public Runnable generateStreamResponse(List<String> conversation, String model,
            Consumer<String> tokenConsumer, Runnable onComplete, Consumer<Exception> onError) {
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            request(conversation, true, token -> { if (!cancelled.get()) tokenConsumer.accept(token); });
            if (!cancelled.get()) onComplete.run();
        }, "starter-chat-stream");
        thread.setDaemon(true);
        thread.start();
        return () -> { cancelled.set(true); thread.interrupt(); };
    }

    private void request(List<String> conversation, boolean stream, Consumer<String> output) {
        remainingMessage = "";
        if (!isEnabled()) { output.accept(UNAVAILABLE); return; }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", "feather-wand");
            body.put("stream", stream);
            body.put("max_tokens", 1024);
            ArrayNode messages = body.putArray("messages");
            List<String> history = conversation == null ? List.of() : conversation;
            int start = Math.max(0, history.size() - 20);
            if (start % 2 != 0) start++;
            for (int i = start; i < history.size(); i++) {
                messages.addObject().put("role", i % 2 == 0 ? "user" : "assistant")
                        .put("content", history.get(i));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + StarterId.load(idPath))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            remainingMessage = remaining(response.headers().firstValue("X-Starter-Remaining").orElse(""),
                    response.headers().firstValue("X-Starter-Limit").orElse("10"));
            try (InputStream input = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    String code = "";
                    try { code = mapper.readTree(input).path("error").path("code").asText(); } catch (Exception ignored) { }
                    output.accept(errorMessage(response.statusCode(), code));
                    return;
                }
                if (stream) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (Thread.currentThread().isInterrupted()) return;
                            if (!line.startsWith("data:")) continue;
                            String data = line.substring(5).trim();
                            if ("[DONE]".equals(data)) break;
                            JsonNode event = mapper.readTree(data);
                            String content = event.path("choices").path(0).path("delta").path("content").asText("");
                            if (!content.isEmpty()) output.accept(content);
                            recordUsage(event);
                        }
                    }
                } else {
                    JsonNode completion = mapper.readTree(input);
                    output.accept(completion.path("choices").path(0).path("message").path("content").asText("No response received."));
                    recordUsage(completion);
                }
                if (!remainingMessage.isEmpty()) output.accept("\n\n" + remainingMessage);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            output.accept(UNAVAILABLE);
        }
    }

    private void recordUsage(JsonNode completion) {
        JsonNode usage = completion.path("usage");
        if (usageStats != null && usage.isObject()) {
            usageStats.record(MODEL_ID, usage.path("prompt_tokens").asLong(), usage.path("completion_tokens").asLong());
        }
    }

    static String remaining(String remaining, String limit) {
        try {
            int r = Integer.parseInt(remaining), l = Integer.parseInt(limit);
            if (r < 0 || l < 0 || r > l) return "";
            return r + " of " + l + " free messages left. Connect a provider for unlimited use.";
        } catch (NumberFormatException e) { return ""; }
    }

    static String errorMessage(int status, String code) {
        if (status == 402 && "starter_quota_exhausted".equals(code)) {
            return "Your free messages are used up. Connect a provider to keep chatting with Feather Wand.";
        }
        return UNAVAILABLE;
    }
}
