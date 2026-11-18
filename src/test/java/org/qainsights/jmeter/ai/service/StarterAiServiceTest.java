package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StarterAiServiceTest {
    @TempDir Path home;
    private HttpServer server;
    private String response;
    private int status;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private Properties saved;

    @BeforeEach void setUp() throws Exception {
        saved = (Properties) JMeterUtils.getJMeterProperties().clone();
        JMeterUtils.setProperty("jmeter.ai.starter.enabled", "true");
        status = 200;
        response = "{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/starter/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(new ObjectMapper().readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("X-Starter-Remaining", "7");
            exchange.getResponseHeaders().set("X-Starter-Limit", "10");
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.start();
    }

    @AfterEach void tearDown() {
        server.stop(0);
        JMeterUtils.getJMeterProperties().clear();
        JMeterUtils.getJMeterProperties().putAll(saved);
    }

    private StarterAiService service() {
        return new StarterAiService(HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/starter",
                home.resolve(".jmeter-ai/starter-id"));
    }

    @Test void starterIdPersistsIndependentlyOfTelemetry() throws Exception {
        Path path = home.resolve(".jmeter-ai/starter-id");
        String id = StarterId.load(path);
        assertEquals(id, UUID.fromString(id).toString());
        assertEquals(id, StarterId.load(path));
        assertEquals(id, Files.readString(path));
        assertFalse(Files.exists(home.resolve(".jmeter-ai/install-id")));
        JMeterUtils.setProperty("jmeter.ai.telemetry.enabled", "false");
        service().generateResponse(List.of("Hi"));
        assertEquals("Bearer " + id, authorization.get());
        service().generateResponse(List.of("Again"));
        assertEquals("Bearer " + id, authorization.get());
    }

    @Test void parsesRemainingAndUsesTextChatWireFormat() {
        StarterAiService service = service();
        assertEquals("Hello\n\n7 of 10 free messages left. Connect a provider for unlimited use.",
                service.generateResponse(List.of("Hi", "Hello", "How are you?")));
        assertEquals("user", request.get().path("messages").path(2).path("role").asText());
        assertFalse(request.get().has("tools"));
        assertTrue(service.getRemainingMessage().startsWith("7 of 10"));
    }

    @Test void malformedOrMissingHeadersDoNotInventAllowance() {
        assertEquals("", StarterAiService.remaining("", "10"));
        assertEquals("", StarterAiService.remaining("bad", "10"));
        assertEquals("", StarterAiService.remaining("11", "10"));
        assertEquals("", StarterAiService.remaining("-1", "10"));
        assertTrue(StarterAiService.remaining("0", "10").startsWith("0 of 10"));
    }

    @Test void mapsQuotaErrorsWithoutLeakingUpstreamDetails() {
        status = 402;
        response = "{\"error\":{\"code\":\"starter_quota_exhausted\",\"message\":\"private upstream detail\"}}";
        String answer = service().generateResponse(List.of("Hi"));
        assertTrue(answer.contains("free messages are used up"));
        assertTrue(answer.contains("Connect a provider"));
        assertFalse(answer.contains("private"));
    }

    @Test void mapsUnavailableBudgetAndRateLimits() {
        for (String code : List.of("starter_unavailable", "starter_budget_exhausted")) {
            status = 503;
            response = "{\"error\":{\"code\":\"" + code + "\"}}";
            assertTrue(service().generateResponse(List.of("Hi")).contains("unavailable right now"));
        }
        status = 429;
        response = "not json";
        assertTrue(service().generateResponse(List.of("Hi")).contains("Try later or connect a provider"));
    }

    @Test void streamsTokensAndAppendsAllowance() throws Exception {
        response = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: [DONE]\n\n";
        StringBuilder output = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        service().generateStreamResponse(List.of("Hi"), null, output::append, done::countDown, error -> fail(error));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(output.toString().startsWith("Hello\n\n7 of 10"));
        assertTrue(request.get().path("stream").asBoolean());
    }

    @Test void disabledProviderMakesNoRequestAndPrivacyShowsOnce() {
        StarterAiService service = service();
        assertTrue(service.showPrivacyNotice());
        assertFalse(service.showPrivacyNotice());
        JMeterUtils.setProperty("jmeter.ai.starter.enabled", "false");
        assertTrue(service.generateResponse(List.of("Hi")).contains("unavailable"));
        assertNull(request.get());
    }
}
