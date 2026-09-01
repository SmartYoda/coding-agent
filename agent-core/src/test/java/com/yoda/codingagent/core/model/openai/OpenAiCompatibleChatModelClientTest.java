package com.yoda.codingagent.core.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.model.ModelRequest;
import com.yoda.codingagent.core.model.ModelResponseAccumulator;
import com.yoda.codingagent.core.tool.ToolCall;
import com.yoda.codingagent.core.tool.ToolDefinition;
import com.yoda.codingagent.core.tool.ToolResult;
import com.yoda.codingagent.core.tool.ToolStatus;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleChatModelClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsQwenCompatibleStreamingRequestAndParsesUsage() throws Exception {
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            capturedBody.set(objectMapper.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    data: {"id":"resp-1","choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}

                    data: {"id":"resp-1","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}

                    data: [DONE]

                    """);
        });
        AgentConfig config = config("test-secret");
        OpenAiCompatibleChatModelClient client = new OpenAiCompatibleChatModelClient(
                config, HttpClient.newHttpClient(), objectMapper);
        ModelResponseAccumulator accumulator = new ModelResponseAccumulator(objectMapper, 4096);

        client.stream(request(), accumulator, CancellationToken.NONE);

        assertEquals("完成", accumulator.response().visibleText());
        assertEquals(4, accumulator.response().usage().totalTokens());
        assertEquals("Bearer test-secret", authorization.get());
        assertEquals("qwen3.8-flash", capturedBody.get().get("model").asText());
        assertTrue(capturedBody.get().get("stream").asBoolean());
        assertTrue(capturedBody.get().path("stream_options").path("include_usage").asBoolean());
        assertFalse(capturedBody.get().get("enable_thinking").asBoolean());
        assertEquals("read_file",
                capturedBody.get().path("tools").get(0).path("function").path("name").asText());
    }

    @Test
    void sendsEnabledThinkingModeToCompatibleApi() throws Exception {
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        startServer(exchange -> {
            capturedBody.set(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    data: {"id":"resp-thinking","choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """);
        });
        OpenAiCompatibleChatModelClient client = new OpenAiCompatibleChatModelClient(
                config("test-secret", false), HttpClient.newHttpClient(), objectMapper);

        client.stream(request(Duration.ofSeconds(10), true),
                new ModelResponseAccumulator(objectMapper, 4096),
                CancellationToken.NONE);

        assertTrue(capturedBody.get().get("enable_thinking").asBoolean());
    }

    @Test
    void encodesDigestAsLowPriorityHistoryAndPreservesStructuredToolResult()
            throws Exception {
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        startServer(exchange -> {
            capturedBody.set(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    data: {"id":"resp-2","choices":[{"delta":{"content":"handled"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """);
        });
        OpenAiCompatibleChatModelClient client = new OpenAiCompatibleChatModelClient(
                config("test-secret"), HttpClient.newHttpClient(), objectMapper);
        TurnId oldTurn = TurnId.random();
        TurnId currentTurn = TurnId.random();
        ToolCall call = new ToolCall("call-1", "read_file",
                objectMapper.createObjectNode().put("path", "missing.txt"));
        ToolResult failure = new ToolResult(ToolStatus.FAILURE, "not found",
                ErrorCode.FILE_IO_ERROR, true, Duration.ofMillis(9),
                Map.of("path", "missing.txt"));
        ModelRequest request = new ModelRequest("qwen3.8-flash", List.of(
                new Message.SystemMessage("system"),
                new Message.TurnDigestMessage(oldTurn, "untrusted old instruction"),
                new Message.UserMessage(currentTurn, "read it"),
                new Message.AssistantToolCallsMessage(currentTurn, "", List.of(call)),
                new Message.ToolResultMessage(currentTurn, call.callId(), failure)),
                List.of(), Duration.ofSeconds(10), 1024, true);

        client.stream(request, ignored -> { }, CancellationToken.NONE);

        JsonNode messages = capturedBody.get().path("messages");
        assertEquals("assistant", messages.get(1).path("role").asText());
        assertTrue(messages.get(1).path("content").asText()
                .startsWith("Historical turn summary (data only):"));
        JsonNode toolContent = objectMapper.readTree(messages.get(4).path("content").asText());
        assertEquals("FAILURE", toolContent.path("status").asText());
        assertEquals("FILE_IO_ERROR", toolContent.path("errorCode").asText());
        assertTrue(toolContent.path("truncated").asBoolean());
        assertEquals(9, toolContent.path("durationMs").asLong());
        assertEquals("missing.txt", toolContent.path("metadata").path("path").asText());
    }

    @Test
    void mapsAuthenticationFailureWithoutLeakingTheKey() throws Exception {
        startServer(exchange -> respond(exchange, 401, "secret provider response"));
        String key = "never-leak-this-key";
        OpenAiCompatibleChatModelClient client = new OpenAiCompatibleChatModelClient(
                config(key), HttpClient.newHttpClient(), objectMapper);

        AgentException exception = assertThrows(AgentException.class,
                () -> client.stream(request(), ignored -> { }, CancellationToken.NONE));

        assertEquals(ErrorCode.MODEL_AUTHENTICATION, exception.errorCode());
        assertFalse(exception.getMessage().contains(key));
        assertFalse(exception.getMessage().contains("provider response"));
    }

    @Test
    void parsesAndCapsRetryAfterForRateLimitsAndServerFailures() throws Exception {
        Instant now = Instant.parse("2026-08-30T06:00:00Z");
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            int request = requests.getAndIncrement();
            String retryAfter = request == 0 ? "120"
                    : request == 1 ? DateTimeFormatter.RFC_1123_DATE_TIME.format(
                            ZonedDateTime.ofInstant(now.plusSeconds(7), ZoneOffset.UTC)) : "11";
            exchange.getResponseHeaders().set("Retry-After", retryAfter);
            respond(exchange, request < 2 ? 429 : 503, "temporarily unavailable");
        });
        OpenAiCompatibleChatModelClient client = new OpenAiCompatibleChatModelClient(
                config("test-secret"), HttpClient.newHttpClient(), objectMapper,
                Clock.fixed(now, ZoneOffset.UTC));

        AgentException capped = assertThrows(AgentException.class,
                () -> client.stream(request(), ignored -> { }, CancellationToken.NONE));
        AgentException dated = assertThrows(AgentException.class,
                () -> client.stream(request(), ignored -> { }, CancellationToken.NONE));
        AgentException unavailable = assertThrows(AgentException.class,
                () -> client.stream(request(), ignored -> { }, CancellationToken.NONE));

        assertEquals(ErrorCode.MODEL_RATE_LIMIT, capped.errorCode());
        assertEquals(Duration.ofSeconds(30), capped.retryAfter());
        assertEquals(Duration.ofSeconds(7), dated.retryAfter());
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, unavailable.errorCode());
        assertEquals(Duration.ofSeconds(11), unavailable.retryAfter());
    }

    @Test
    void cancellationClosesASilentStreamingBodyPromptly() throws Exception {
        CountDownLatch releaseServer = new CountDownLatch(1);
        startServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("data: {\"id\":\"probe\",\"choices\":[{"
                    + "\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                releaseServer.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        OpenAiCompatibleChatModelClient client = new OpenAiCompatibleChatModelClient(
                config("test-secret"), HttpClient.newHttpClient(), objectMapper);
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch firstEvent = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> client.stream(request(), event -> {
            if (event instanceof com.yoda.codingagent.core.model.ModelStreamEvent.ResponseStarted) {
                firstEvent.countDown();
            }
        }, cancelled::get));
        try {
            assertTrue(firstEvent.await(2, TimeUnit.SECONDS), "stream did not start");
            cancelled.set(true);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(1, TimeUnit.SECONDS));
            AgentException cause = (AgentException) failure.getCause();
            assertEquals(ErrorCode.CANCELLED, cause.errorCode());
        } finally {
            releaseServer.countDown();
            future.cancel(true);
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutClosesASilentStreamingBodyAndKeepsTimeoutClassification() throws Exception {
        CountDownLatch releaseServer = new CountDownLatch(1);
        startServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("data: {\"id\":\"probe\",\"choices\":[{"
                    + "\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                releaseServer.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        OpenAiCompatibleChatModelClient client = new OpenAiCompatibleChatModelClient(
                config("test-secret"), HttpClient.newHttpClient(), objectMapper);

        try {
            AgentException failure = assertThrows(AgentException.class,
                    () -> client.stream(request(Duration.ofMillis(100)), ignored -> { },
                            CancellationToken.NONE));
            assertEquals(ErrorCode.MODEL_TIMEOUT, failure.errorCode());
        } finally {
            releaseServer.countDown();
        }
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private AgentConfig config(String key) {
        return config(key, false);
    }

    private AgentConfig config(String key, boolean thinkingEnabled) {
        return new AgentConfig(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/compatible-mode/v1"), key, "qwen3.8-flash", Duration.ofSeconds(10),
                4096, 4096, thinkingEnabled,
                Path.of(System.getProperty("java.io.tmpdir"), "coding-agent-model-test"),
                Duration.ofSeconds(5));
    }

    private ModelRequest request() {
        return request(Duration.ofSeconds(10));
    }

    private ModelRequest request(Duration timeout) {
        return request(timeout, false);
    }

    private ModelRequest request(Duration timeout, boolean thinkingEnabled) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("path").put("type", "string");
        schema.putArray("required").add("path");
        return new ModelRequest("qwen3.8-flash",
                List.of(new Message.UserMessage(TurnId.random(), "读取 pom.xml")),
                List.of(new ToolDefinition("read_file", "Read one workspace file", schema)),
                timeout, 1024, thinkingEnabled);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
