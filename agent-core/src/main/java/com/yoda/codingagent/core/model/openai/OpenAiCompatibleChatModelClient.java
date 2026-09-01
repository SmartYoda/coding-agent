package com.yoda.codingagent.core.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.model.ModelClient;
import com.yoda.codingagent.core.model.ModelRequest;
import com.yoda.codingagent.core.model.ModelStreamSink;
import com.yoda.codingagent.core.tool.ToolCall;
import com.yoda.codingagent.core.tool.ToolDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.channels.UnresolvedAddressException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class OpenAiCompatibleChatModelClient implements ModelClient {

    private static final int MAX_ERROR_BODY_BYTES = 8_192;
    private static final long WATCHDOG_INTERVAL_MILLIS = 25;
    private static final ScheduledExecutorService STREAM_WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "coding-agent-model-stream-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    private final AgentConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final Clock clock;

    public OpenAiCompatibleChatModelClient(AgentConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), new ObjectMapper(), Clock.systemUTC());
    }

    public OpenAiCompatibleChatModelClient(
            AgentConfig config, HttpClient httpClient, ObjectMapper objectMapper) {
        this(config, httpClient, objectMapper, Clock.systemUTC());
    }

    public OpenAiCompatibleChatModelClient(
            AgentConfig config, HttpClient httpClient, ObjectMapper objectMapper, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.endpoint = chatCompletionsEndpoint(config.baseUrl());
    }

    @Override
    public void stream(ModelRequest request, ModelStreamSink sink,
                       CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        checkCancelled(cancellationToken);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(request.timeout())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson(request),
                        StandardCharsets.UTF_8))
                .build();
        long startedAt = System.nanoTime();
        long timeoutNanos = request.timeout().toNanos();
        AtomicBoolean deadlineExceeded = new AtomicBoolean();
        AtomicReference<InputStream> activeBody = new AtomicReference<>();
        CompletableFuture<HttpResponse<InputStream>> responseFuture = httpClient.sendAsync(
                httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        responseFuture.whenComplete((response, failure) -> {
            if (response != null) {
                activeBody.set(response.body());
                boolean timedOut = markDeadlineIfExceeded(
                        deadlineExceeded, startedAt, timeoutNanos);
                if (cancellationToken.isCancelled() || timedOut) {
                    safeClose(response.body());
                }
            }
        });
        ScheduledFuture<?> watchdog = STREAM_WATCHDOG.scheduleAtFixedRate(() -> {
            boolean cancelled = cancellationToken.isCancelled();
            boolean timedOut = markDeadlineIfExceeded(
                    deadlineExceeded, startedAt, timeoutNanos);
            if (cancelled || timedOut) {
                responseFuture.cancel(true);
                safeClose(activeBody.get());
            }
        }, WATCHDOG_INTERVAL_MILLIS, WATCHDOG_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        try {
            HttpResponse<InputStream> response = responseFuture.get();
            activeBody.compareAndSet(null, response.body());
            boolean timedOut = markDeadlineIfExceeded(
                    deadlineExceeded, startedAt, timeoutNanos);
            if (cancellationToken.isCancelled() || timedOut) {
                safeClose(response.body());
                throw cancellationOrTimeout(
                        cancellationToken, timedOut, null);
            }
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    try {
                        discardLimited(body);
                    } catch (IOException ignored) {
                        // The HTTP status remains the authoritative failure classification.
                    }
                    throw statusError(response.statusCode(),
                            response.headers().firstValue("Retry-After").orElse(null));
                }
                ChatCompletionsStreamParser chunkParser =
                        new ChatCompletionsStreamParser(objectMapper);
                new SseFrameParser(config.maxSseEventBytes()).parse(
                        body, frame -> chunkParser.accept(frame, sink), cancellationToken);
            }
            timedOut = markDeadlineIfExceeded(deadlineExceeded, startedAt, timeoutNanos);
            if (cancellationToken.isCancelled() || timedOut) {
                throw cancellationOrTimeout(cancellationToken, timedOut, null);
            }
        } catch (AgentException exception) {
            throw exception;
        } catch (CancellationException exception) {
            throw cancellationOrTimeout(cancellationToken,
                    markDeadlineIfExceeded(deadlineExceeded, startedAt, timeoutNanos), exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof HttpConnectTimeoutException
                    || cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof UnresolvedAddressException) {
                throw new AgentException(ErrorCode.MODEL_UNAVAILABLE,
                        "could not connect to the model service", cause);
            }
            if (cause instanceof java.net.http.HttpTimeoutException) {
                throw new AgentException(ErrorCode.MODEL_TIMEOUT,
                        "model request timed out", cause);
            }
            if (cause instanceof AgentException agentException) {
                throw agentException;
            }
            throw cancellationOrStreamFailure(
                    cancellationToken,
                    markDeadlineIfExceeded(deadlineExceeded, startedAt, timeoutNanos), cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentException(ErrorCode.CANCELLED,
                    "model request interrupted", exception);
        } catch (IOException exception) {
            throw cancellationOrStreamFailure(
                    cancellationToken,
                    markDeadlineIfExceeded(deadlineExceeded, startedAt, timeoutNanos), exception);
        } finally {
            watchdog.cancel(false);
            if (cancellationToken.isCancelled()
                    || markDeadlineIfExceeded(deadlineExceeded, startedAt, timeoutNanos)) {
                safeClose(activeBody.get());
            }
        }
    }

    private String requestJson(ModelRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.model());
        root.set("messages", messagesJson(request));
        root.put("stream", true);
        root.putObject("stream_options").put("include_usage", true);
        root.put("enable_thinking", request.thinkingEnabled());
        root.put("max_tokens", request.maxOutputTokens());
        if (!request.tools().isEmpty()) {
            root.set("tools", toolsJson(request));
            root.put("tool_choice", "auto");
            root.put("parallel_tool_calls", false);
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new AgentException(ErrorCode.INTERNAL_ERROR,
                    "could not encode model request", exception);
        }
    }

    private ArrayNode messagesJson(ModelRequest request) {
        ArrayNode messages = objectMapper.createArrayNode();
        for (Message message : request.messages()) {
            ObjectNode encoded = messages.addObject();
            if (message instanceof Message.SystemMessage system) {
                encoded.put("role", "system");
                encoded.put("content", system.content());
            } else if (message instanceof Message.UserMessage user) {
                encoded.put("role", "user");
                encoded.put("content", user.content());
            } else if (message instanceof Message.AssistantMessage assistant) {
                encoded.put("role", "assistant");
                encoded.put("content", assistant.content());
            } else if (message instanceof Message.AssistantToolCallsMessage assistantCalls) {
                encoded.put("role", "assistant");
                if (assistantCalls.visibleText().isBlank()) {
                    encoded.putNull("content");
                } else {
                    encoded.put("content", assistantCalls.visibleText());
                }
                ArrayNode calls = encoded.putArray("tool_calls");
                for (ToolCall call : assistantCalls.toolCalls()) {
                    ObjectNode encodedCall = calls.addObject();
                    encodedCall.put("id", call.callId());
                    encodedCall.put("type", "function");
                    ObjectNode function = encodedCall.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", writeArguments(call));
                }
            } else if (message instanceof Message.ToolResultMessage toolResult) {
                encoded.put("role", "tool");
                encoded.put("tool_call_id", toolResult.callId());
                encoded.put("content", writeToolResult(toolResult));
            } else if (message instanceof Message.TurnDigestMessage digest) {
                encoded.put("role", "assistant");
                encoded.put("content", digest.content());
            }
        }
        return messages;
    }

    private ArrayNode toolsJson(ModelRequest request) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (ToolDefinition definition : request.tools()) {
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.set("parameters", definition.inputSchema());
        }
        return tools;
    }

    private String writeArguments(ToolCall call) {
        try {
            return objectMapper.writeValueAsString(call.arguments());
        } catch (JsonProcessingException exception) {
            throw new AgentException(ErrorCode.INTERNAL_ERROR,
                    "could not encode tool arguments", exception);
        }
    }

    private String writeToolResult(Message.ToolResultMessage message) {
        ObjectNode encoded = objectMapper.createObjectNode();
        encoded.put("status", message.result().status().name());
        encoded.put("output", message.result().output());
        if (message.result().errorCode() == null) {
            encoded.putNull("errorCode");
        } else {
            encoded.put("errorCode", message.result().errorCode().name());
        }
        encoded.put("truncated", message.result().truncated());
        encoded.put("durationMs", message.result().duration().toMillis());
        ObjectNode metadata = encoded.putObject("metadata");
        message.result().metadata().forEach(metadata::put);
        try {
            return objectMapper.writeValueAsString(encoded);
        } catch (JsonProcessingException exception) {
            throw new AgentException(ErrorCode.INTERNAL_ERROR,
                    "could not encode tool result", exception);
        }
    }

    private static URI chatCompletionsEndpoint(URI baseUrl) {
        String base = baseUrl.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return URI.create(base);
        }
        return URI.create(base + "/chat/completions");
    }

    private static void discardLimited(InputStream input) throws IOException {
        input.readNBytes(MAX_ERROR_BODY_BYTES);
    }

    private static boolean deadlineExceeded(long startedAt, long timeoutNanos) {
        return System.nanoTime() - startedAt >= timeoutNanos;
    }

    private static boolean markDeadlineIfExceeded(
            AtomicBoolean state, long startedAt, long timeoutNanos) {
        if (state.get()) {
            return true;
        }
        if (deadlineExceeded(startedAt, timeoutNanos)) {
            state.set(true);
            return true;
        }
        return false;
    }

    private static void safeClose(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // The request is already terminating; the original outcome is more useful.
        }
    }

    private static AgentException cancellationOrTimeout(
            CancellationToken token, boolean timedOut, Throwable cause) {
        if (token.isCancelled()) {
            return new AgentException(ErrorCode.CANCELLED, "model request cancelled", cause);
        }
        if (timedOut) {
            return new AgentException(ErrorCode.MODEL_TIMEOUT, "model request timed out", cause);
        }
        return new AgentException(ErrorCode.MODEL_STREAM_INTERRUPTED,
                "model request was cancelled by the HTTP client", cause);
    }

    private static AgentException cancellationOrStreamFailure(
            CancellationToken token, boolean timedOut, Throwable cause) {
        if (token.isCancelled()) {
            return new AgentException(ErrorCode.CANCELLED, "model request cancelled", cause);
        }
        if (timedOut) {
            return new AgentException(ErrorCode.MODEL_TIMEOUT, "model request timed out", cause);
        }
        return new AgentException(ErrorCode.MODEL_STREAM_INTERRUPTED,
                "model stream was interrupted", cause);
    }

    private AgentException statusError(int statusCode, String retryAfterHeader) {
        return switch (statusCode) {
            case 401, 403 -> new AgentException(ErrorCode.MODEL_AUTHENTICATION,
                    "model service rejected authentication");
            case 429 -> new AgentException(ErrorCode.MODEL_RATE_LIMIT,
                    "model service rate limit reached", parseRetryAfter(retryAfterHeader));
            default -> {
                if (statusCode >= 500) {
                    yield new AgentException(ErrorCode.MODEL_UNAVAILABLE,
                            "model service is unavailable", parseRetryAfter(retryAfterHeader));
                }
                yield new AgentException(ErrorCode.MODEL_PROTOCOL_ERROR,
                        "model service rejected the request (HTTP " + statusCode + ")");
            }
        };
    }

    private Duration parseRetryAfter(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String value = rawValue.trim();
        try {
            long seconds = Long.parseLong(value);
            if (seconds < 0) {
                return null;
            }
            return clampRetryAfter(Duration.ofSeconds(seconds));
        } catch (NumberFormatException | ArithmeticException ignored) {
            // Try the HTTP-date form next.
        }
        try {
            Instant retryAt = ZonedDateTime.parse(
                    value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(clock.instant(), retryAt);
            return clampRetryAfter(delay.isNegative() ? Duration.ZERO : delay);
        } catch (DateTimeParseException | ArithmeticException ignored) {
            return null;
        }
    }

    private static Duration clampRetryAfter(Duration delay) {
        return delay.compareTo(Duration.ofSeconds(30)) > 0
                ? Duration.ofSeconds(30) : delay;
    }

    private static void checkCancelled(CancellationToken token) {
        if (token.isCancelled()) {
            throw new AgentException(ErrorCode.CANCELLED, "model request cancelled");
        }
    }
}
