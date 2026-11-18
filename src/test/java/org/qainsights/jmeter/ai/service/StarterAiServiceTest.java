package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qainsights.jmeter.ai.load.WorkloadAiAssistant;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.qainsights.jmeter.ai.utils.ProviderErrors;

import static org.junit.jupiter.api.Assertions.*;

class StarterAiServiceTest {
    @TempDir Path home;
    private HttpServer server;
    private volatile String response;
    private volatile int status;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private Properties saved;
    private java.util.concurrent.ExecutorService serverExecutor;

    @BeforeAll static void initJMeter() {
        if (JMeterUtils.getJMeterProperties() == null) JMeterUtils.loadJMeterProperties("nonexistent.properties");
    }

    @BeforeEach void setUp() throws Exception {
        saved = (Properties) JMeterUtils.getJMeterProperties().clone();
        JMeterUtils.setProperty("jmeter.ai.starter.enabled", "true");
        status = 200;
        response = "{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = java.util.concurrent.Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task); thread.setDaemon(true); return thread;
        });
        server.setExecutor(serverExecutor);
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
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
        if (saved == null) return;
        JMeterUtils.getJMeterProperties().clear();
        JMeterUtils.getJMeterProperties().putAll(saved);
    }

    private StarterAiService service() {
        return new StarterAiService(HttpClient.newHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/starter",
                home.resolve(".jmeter-ai/starter-id"));
    }

    @Test void boundsSerializedHistoryByBytesWithoutTruncatingTheCurrentMessage() {
        service().generateResponse(List.of("🤖".repeat(20_000), "Old answer", "New question"));
        assertEquals(1, request.get().path("messages").size());
        assertEquals("New question", request.get().path("messages").path(0).path("content").asText());
    }

    @Test void reportsOversizeCurrentMessagesAndWorkerSizeRejections() {
        var failure = assertThrows(StarterAiService.Failure.class,
                () -> service().generateResponse(List.of("x".repeat(70_000))));
        assertTrue(failure.getMessage().contains("too long"));
        assertNull(request.get());
        status = 400;
        response = "{\"error\":{\"code\":\"invalid_request\"}}";
        failure = assertThrows(StarterAiService.Failure.class, () -> service().generateResponse(List.of("Hello")));
        assertTrue(failure.getMessage().contains("Start a new chat"));
    }

    @Test void doesNotChangePermissionsWhenTheIdPathIsADirectory() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(home).supportsFileAttributeView("posix"));
        Path path = Files.createDirectory(home.resolve("starter-id"));
        var before = Files.getPosixFilePermissions(path);
        assertThrows(java.io.IOException.class, () -> StarterId.load(path));
        assertEquals(before, Files.getPosixFilePermissions(path));
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
        assertEquals("Hello",
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
        String answer = assertThrows(StarterAiService.Failure.class, () -> service().generateResponse(List.of("Hi"))).getMessage();
        assertTrue(answer.contains("free messages are used up"));
        assertTrue(answer.contains("Connect a provider"));
        assertFalse(answer.contains("private"));
    }

    @Test void mapsUnavailableBudgetAndRateLimits() {
        for (String code : List.of("starter_unavailable", "starter_budget_exhausted")) {
            status = 503;
            response = "{\"error\":{\"code\":\"" + code + "\"}}";
            assertTrue(assertThrows(StarterAiService.Failure.class, () -> service().generateResponse(List.of("Hi"))).getMessage().contains("unavailable right now"));
        }
        status = 429;
        response = "not json";
        assertTrue(assertThrows(StarterAiService.Failure.class, () -> service().generateResponse(List.of("Hi"))).getMessage().contains("Try later or connect a provider"));
    }

    @Test void structuredResponsesRemainParseableWithoutAllowanceText() throws Exception {
        String suggestion = "{\"target_type\":\"users\",\"target_value\":10,\"summary\":\"Ten users\"}";
        response = new ObjectMapper().writeValueAsString(java.util.Map.of("choices",
                List.of(java.util.Map.of("message", java.util.Map.of("content", suggestion)))));
        StarterAiService service = service();
        String answer = service.generateResponse(List.of("Suggest a workload"));
        assertEquals(suggestion, answer);
        assertEquals(10, WorkloadAiAssistant.parse(answer).targetValue());
        assertTrue(service.getRemainingMessage().startsWith("7 of 10"));
    }

    @Test void streamsOnlyModelTokensAndExposesAllowanceSeparately() throws Exception {
        response = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: [DONE]\n\n";
        StringBuilder output = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        StarterAiService service = service();
        service.generateStreamResponse(List.of("Hi"), null, output::append, done::countDown, error -> fail(error));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("Hello", output.toString());
        assertTrue(service.getRemainingMessage().startsWith("7 of 10"));
        assertTrue(request.get().path("stream").asBoolean());
    }

    @Test void disabledProviderMakesNoRequestAndPrivacyShowsOnce() {
        StarterAiService service = service();
        assertTrue(service.showPrivacyNotice());
        assertFalse(service.showPrivacyNotice());
        JMeterUtils.setProperty("jmeter.ai.starter.enabled", "false");
        assertTrue(assertThrows(StarterAiService.Failure.class, () -> service.generateResponse(List.of("Hi"))).getMessage().contains("unavailable"));
        assertNull(request.get());
    }

    @Test void repairsAnIncompleteIdAndRestrictsExistingAndNewFiles() throws Exception {
        Path path = home.resolve("starter-id");
        Files.writeString(path, "truncated");
        String id = StarterId.load(path);
        assertEquals(id, UUID.fromString(id).toString());
        assertEquals(id, StarterId.load(path));
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
            assertEquals(id, StarterId.load(path));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
        }
        assertEquals(id, Files.readString(path));
        assertThrows(java.io.IOException.class, () -> StarterId.load(path.resolve("child")));
    }

    @Test void concurrentIdLoadsUseOneIdentity() throws Exception {
        Path path = home.resolve("starter-id");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var calls = executor.invokeAll(java.util.Collections.nCopies(8, (java.util.concurrent.Callable<String>) () -> StarterId.load(path)));
            String id = calls.get(0).get();
            for (var call : calls) assertEquals(id, call.get());
        } finally { executor.shutdownNow(); }
    }

    @Test void reusesAReadableIdWhenTheDirectoryIsReadOnly() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(home).supportsFileAttributeView("posix"));
        Path directory = Files.createDirectory(home.resolve("read-only"));
        Path path = Files.writeString(directory.resolve("starter-id"), UUID.randomUUID().toString());
        String id = Files.readString(path);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(directory));
            assertEquals(id, StarterId.load(path));
            assertEquals(id, StarterId.load(path));
            assertFalse(Files.exists(directory.resolve("starter-id.lock")));
            assertThrows(java.io.IOException.class, () -> StarterId.load(directory.resolve("missing-id")));
        } finally { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); }
    }

    @Test void reusesAReadableIdWhenTheLockCannotBeOpened() throws Exception {
        Path path = Files.writeString(home.resolve("starter-id"), UUID.randomUUID().toString());
        Files.createDirectory(home.resolve("starter-id.lock"));
        assertEquals(Files.readString(path), StarterId.load(path));
        Files.writeString(path, "incomplete");
        assertThrows(java.io.IOException.class, () -> StarterId.load(path));
    }

    @Test void streamFailuresInvokeErrorWithoutCompletingOrSavingErrorTokens() throws Exception {
        for (String body : List.of("not json", "data: {broken}\n\n", "data: {\"error\":{\"message\":\"private\"}}\n\n", "data: [DONE]\n\n")) {
            status = body.equals("not json") ? 503 : 200;
            response = body;
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Exception> failure = new AtomicReference<>();
            AtomicInteger completions = new AtomicInteger();
            StringBuilder tokens = new StringBuilder();
            StarterAiService service = service();
            service.generateStreamResponse(List.of("Hi"), null, tokens::append,
                    completions::incrementAndGet, error -> { failure.set(error); done.countDown(); });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertInstanceOf(StarterAiService.Failure.class, failure.get());
            assertEquals(0, completions.get());
            assertEquals("", tokens.toString());
            assertEquals("", service.getRemainingMessage());
            assertEquals("Free messages are unavailable right now. Try later or connect a provider.",
                    ProviderErrors.describe(new java.util.concurrent.ExecutionException(failure.get())));
        }
    }

    @Test void partialStreamParseFailureStillUsesTheErrorPath() throws Exception {
        response = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: {broken}\n\n";
        CountDownLatch done = new CountDownLatch(1);
        StringBuilder tokens = new StringBuilder();
        AtomicInteger completions = new AtomicInteger();
        service().generateStreamResponse(List.of("Hi"), null, tokens::append, completions::incrementAndGet, e -> done.countDown());
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("Hello", tokens.toString());
        assertEquals(0, completions.get());
    }

    @Test void bodyDeadlineClosesStalledJsonAndSseReads() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.removeContext("/v1/starter/chat/completions");
        server.createContext("/v1/starter/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        StarterAiService service = new StarterAiService(HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/starter", home.resolve("id"), Duration.ofMillis(100));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThrows(StarterAiService.Failure.class, () -> service.generateResponse(List.of("Hi"))));
            CountDownLatch done = new CountDownLatch(1);
            service.generateStreamResponse(List.of("Hi"), null, token -> fail("unexpected token"),
                    () -> fail("unexpected completion"), error -> done.countDown());
            assertTrue(done.await(3, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }
}
