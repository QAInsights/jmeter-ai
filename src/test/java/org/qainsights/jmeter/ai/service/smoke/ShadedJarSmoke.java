package org.qainsights.jmeter.ai.service;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.jmeter.util.JMeterUtils;
import org.qainsights.jmeter.ai.utils.Models;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

public final class ShadedJarSmoke {
    private static final String CHAT = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\","
            + "\"created\":1,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":"
            + "{\"role\":\"assistant\",\"content\":\"chat ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    private static HttpServer server;
    private static String origin;

    private ShadedJarSmoke() {
    }

    public static void main(String[] args) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ShadedJarSmoke::handle);
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            String jmeterHome = System.getenv("JMETER_HOME");
            JMeterUtils.loadJMeterProperties(Path.of(jmeterHome, "bin", "jmeter.properties").toString());
            JMeterUtils.setJMeterHome(jmeterHome);
            configure();
            openAi();
            responsesAndModels();
            decisions();
            anthropic();
            gemini();
            ollama();
            bedrock();
            System.out.println("Shaded JAR smoke passed: OpenAI chat/stream, Responses, models, Decisions, "
                    + "Anthropic messages/stream/models, Gemini, Ollama, Bedrock Converse");
        } finally {
            server.stop(0);
        }
    }

    private static void configure() {
        JMeterUtils.setProperty("openai.api.key", "smoke");
        JMeterUtils.setProperty("openai.base.url", origin + "/v1");
        JMeterUtils.setProperty("openai.max.retries", "0");
        JMeterUtils.setProperty("openai.default.model", "gpt-4o");
        JMeterUtils.setProperty("anthropic.api.key", "smoke");
        JMeterUtils.setProperty("anthropic.base.url", origin);
        JMeterUtils.setProperty("anthropic.max.retries", "0");
        JMeterUtils.setProperty("claude.default.model", "claude-sonnet-4-6");
        JMeterUtils.setProperty("ollama.host", "http://127.0.0.1");
        JMeterUtils.setProperty("ollama.port", Integer.toString(server.getAddress().getPort()));
        JMeterUtils.setProperty("ollama.default.model", "smoke-model");
        JMeterUtils.setProperty("ollama.request.timeout.seconds", "5");
    }

    private static void openAi() throws Exception {
        OpenAiService service = new OpenAiService();
        require(service.generateResponse(List.of("hello")).contains("chat ok"), "OpenAI chat completion");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> text = new AtomicReference<>("");
        AtomicReference<Exception> error = new AtomicReference<>();
        service.generateStreamResponse(List.of("hello"), null,
                token -> text.updateAndGet(old -> old + token), done::countDown, error::set);
        await(done, error, "OpenAI stream");
        require(text.get().contains("stream ok"), "OpenAI streaming content");
        System.out.println("PASS OpenAI chat completion and streaming");
    }

    private static void responsesAndModels() throws Exception {
        OpenAIClient client = OpenAIOkHttpClient.builder().apiKey("smoke")
                .baseUrl(origin + "/v1").maxRetries(0).build();
        MetaMuseAiService service = new MetaMuseAiService(client, origin + "/v1", "muse-spark-1.1",
                0.2f, 10, 256, "smoke");
        require(service.generateResponse(List.of("hello")).contains("response ok"), "OpenAI Responses");
        require(service.listModels().contains("muse-spark-1.1"), "OpenAI models list");
        System.out.println("PASS OpenAI Responses and models list");
    }

    private static void decisions() {
        OpenAiDecisionsJudgmentProvider provider = new OpenAiDecisionsJudgmentProvider(
                "smoke", "gpt-6-luna", Duration.ofSeconds(5));
        JudgmentProvider.ChoiceAnswer answer = provider.choose(Map.of("message", "test"), "Choose", Map.of(
                "EDIT", "Modify", "RUN", "Execute"));
        require("EDIT".equals(answer.choice()), "OpenAI Decisions");
        System.out.println("PASS OpenAI Decisions");
    }

    private static void anthropic() throws Exception {
        ClaudeService service = new ClaudeService();
        require(service.generateResponse(List.of("hello")).contains("anthropic ok"), "Anthropic message");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> text = new AtomicReference<>("");
        AtomicReference<Exception> error = new AtomicReference<>();
        service.generateStreamResponse(List.of("hello"), null,
                token -> text.updateAndGet(old -> old + token), done::countDown, error::set);
        await(done, error, "Anthropic stream");
        require(text.get().contains("anthropic stream"), "Anthropic streaming content");
        require(!Models.getAnthropicModels(service.getClient())
                .data().isEmpty(), "Anthropic models list");
        System.out.println("PASS Anthropic messages, streaming, and models list");
    }

    private static void gemini() {
        Client client = Client.builder().apiKey("smoke")
                .httpOptions(HttpOptions.builder().baseUrl(origin + "/").apiVersion("v1beta").build())
                .build();
        GoogleAiService service = new GoogleAiService(client);
        require(service.generateResponse(List.of("hello")).contains("gemini ok"), "Gemini generateContent");
        System.out.println("PASS Gemini generateContent");
    }

    private static void ollama() {
        require(new OllamaAiService().generateResponse(List.of("hello")).contains("ollama ok"), "Ollama chat");
        System.out.println("PASS Ollama chat");
    }

    private static void bedrock() {
        BedrockRuntimeClient client = BedrockRuntimeClient.builder().region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("smoke", "smoke")))
                .endpointOverride(URI.create(origin)).httpClient(UrlConnectionHttpClient.builder().build()).build();
        try {
            BedrockAiService service = new BedrockAiService(client, null, null,
                    "anthropic.claude-smoke", 0.2f, 10, 128, "smoke");
            require(service.generateResponse(List.of("hello")).contains("bedrock ok"), "Bedrock Converse");
        } finally {
            client.close();
        }
        System.out.println("PASS Bedrock Converse");
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response;
        String type = "application/json";
        if (path.endsWith("/chat/completions")) {
            if (body.contains("\"stream\":true")) {
                type = "text/event-stream";
                response = "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"stream ok\"},"
                        + "\"finish_reason\":null}]}\n\ndata: [DONE]\n\n";
            } else {
                response = CHAT;
            }
        } else if (path.endsWith("/responses")) {
            response = "{\"id\":\"resp_1\",\"object\":\"response\",\"model\":\"muse-spark-1.1\","
                    + "\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"id\":\"msg_1\","
                    + "\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"response ok\"}]}]}";
        } else if (path.endsWith("/models") && exchange.getRequestHeaders().getFirst("x-api-key") != null) {
            response = "{\"data\":[{\"id\":\"claude-sonnet-4-6\",\"type\":\"model\","
                    + "\"display_name\":\"Claude\",\"created_at\":\"2025-01-01T00:00:00Z\"}],"
                    + "\"has_more\":false,\"first_id\":\"claude-sonnet-4-6\",\"last_id\":\"claude-sonnet-4-6\"}";
        } else if (path.endsWith("/models")) {
            response = "{\"object\":\"list\",\"data\":[{\"id\":\"muse-spark-1.1\","
                    + "\"object\":\"model\",\"created\":1,\"owned_by\":\"meta\"}]}";
        } else if (path.endsWith("/decisions")) {
            response = "{\"id\":\"dec_1\",\"object\":\"decision\",\"model\":\"gpt-6-luna\","
                    + "\"answers\":[{\"type\":\"choice\",\"name\":\"route\",\"choice\":\"EDIT\","
                    + "\"confidence\":0.7,\"probabilities\":[{\"value\":\"EDIT\",\"probability\":0.85},"
                    + "{\"value\":\"RUN\",\"probability\":0.15}]}]}";
        } else if (path.endsWith("/messages")) {
            if (body.contains("\"stream\":true")) {
                type = "text/event-stream";
                response = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\","
                        + "\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-6\","
                        + "\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n"
                        + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"anthropic stream\"}}\n\n"
                        + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                        + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                        + "\"usage\":{\"output_tokens\":1}}\n\n"
                        + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
            } else {
                response = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-sonnet-4-6\",\"content\":[{\"type\":\"text\","
                        + "\"text\":\"anthropic ok\"}],\"stop_reason\":\"end_turn\","
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
            }
        } else if (path.contains("generateContent")) {
            response = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"gemini ok\"}]},"
                    + "\"index\":0,\"finishReason\":\"STOP\"}],\"modelVersion\":\"gemini-2.5-flash\"}";
        } else if (path.endsWith("/api/tags")) {
            response = "{\"models\":[{\"name\":\"smoke-model\",\"model\":\"smoke-model\",\"size\":1,"
                    + "\"digest\":\"x\",\"modified_at\":\"2025-01-01T00:00:00Z\",\"details\":{}}]}";
        } else if (path.endsWith("/api/chat")) {
            response = "{\"model\":\"smoke-model\",\"created_at\":\"2025-01-01T00:00:00Z\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"ollama ok\"},\"done\":true,"
                    + "\"prompt_eval_count\":1,\"eval_count\":1}";
        } else if (path.contains("/converse")) {
            response = "{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"bedrock ok\"}]}},"
                    + "\"stopReason\":\"end_turn\",\"usage\":{\"inputTokens\":1,\"outputTokens\":1,\"totalTokens\":2}}";
        } else {
            response = "{\"error\":{\"message\":\"unexpected path " + path + "\"}}";
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void await(CountDownLatch done, AtomicReference<Exception> error, String operation)
            throws Exception {
        if (!done.await(15, TimeUnit.SECONDS)) {
            throw new AssertionError(operation + " timed out");
        }
        if (error.get() != null) {
            throw new AssertionError(operation + " failed", error.get());
        }
    }

    private static void require(boolean value, String operation) {
        if (!value) {
            throw new AssertionError(operation + " failed");
        }
    }
}
