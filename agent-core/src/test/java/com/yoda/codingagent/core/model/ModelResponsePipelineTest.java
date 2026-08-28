package com.yoda.codingagent.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.openai.ChatCompletionsStreamParser;
import com.yoda.codingagent.core.model.openai.SseFrameParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelResponsePipelineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aggregatesQwenStyleSplitToolCallOnlyAfterCleanEnd() throws Exception {
        String stream = frame("""
                {"id":"resp-1","choices":[{"delta":{"content":"我先读取。","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"read_","arguments":"{\\\"pa"}}]},"finish_reason":null}]}
                """) + frame("""
                {"id":"resp-1","choices":[{"delta":{"tool_calls":[{"index":0,"id":"","type":"function","function":{"name":"file","arguments":"th\\\":\\\"pom.xml\\\"}"}}]},"finish_reason":"tool_calls"}]}
                """) + frame("""
                {"id":"resp-1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                """) + "data: [DONE]\n\n";
        ModelResponseAccumulator accumulator = new ModelResponseAccumulator(objectMapper, 4096);
        List<String> visibleDeltas = new ArrayList<>();
        ChatCompletionsStreamParser chunkParser =
                new ChatCompletionsStreamParser(objectMapper);

        new SseFrameParser(4096).parse(bytes(stream), frame -> chunkParser.accept(frame, event -> {
            accumulator.onEvent(event);
            if (event instanceof ModelStreamEvent.TextDelta delta) {
                visibleDeltas.add(delta.text());
            }
        }), CancellationToken.NONE);

        assertTrue(accumulator.isComplete());
        ModelResponse response = accumulator.response();
        assertEquals(List.of("我先读取。"), visibleDeltas);
        assertEquals("read_file", response.toolCalls().getFirst().name());
        assertEquals("pom.xml", response.toolCalls().getFirst().arguments().get("path").asText());
        assertEquals(15, response.usage().totalTokens());
    }

    @Test
    void doesNotProduceResponseBeforeDone() {
        ModelResponseAccumulator accumulator = new ModelResponseAccumulator(objectMapper, 4096);
        accumulator.onEvent(new ModelStreamEvent.ResponseStarted("resp-1"));
        accumulator.onEvent(new ModelStreamEvent.TextDelta("partial"));
        accumulator.onEvent(new ModelStreamEvent.ResponseFinished("stop"));

        assertFalse(accumulator.isComplete());
        assertThrows(AgentException.class, accumulator::response);
    }

    @Test
    void rejectsConflictingToolCallIds() {
        ModelResponseAccumulator accumulator = new ModelResponseAccumulator(objectMapper, 4096);
        accumulator.onEvent(new ModelStreamEvent.ResponseStarted("resp-1"));
        accumulator.onEvent(new ModelStreamEvent.ToolCallDelta(
                0, "call-1", "read_file", "{\"path\":"));

        assertThrows(AgentException.class, () -> accumulator.onEvent(
                new ModelStreamEvent.ToolCallDelta(0, "call-2", null, "\"pom.xml\"}")));
    }

    @Test
    void rejectsInvalidProviderJson() {
        ChatCompletionsStreamParser parser = new ChatCompletionsStreamParser(objectMapper);

        assertThrows(AgentException.class, () -> parser.accept(
                new SseFrameParser.SseFrame(null, "{not-json}"), ignored -> { }));
    }

    @Test
    void ignoresUnknownProviderFields() {
        ModelResponseAccumulator accumulator = new ModelResponseAccumulator(objectMapper, 4096);
        ChatCompletionsStreamParser parser = new ChatCompletionsStreamParser(objectMapper);
        parser.accept(new SseFrameParser.SseFrame(null, """
                {"id":"resp-1","unknown":{"nested":true},"choices":[{
                  "delta":{"role":"assistant","content":"ok","future_field":123},
                  "finish_reason":"stop","other":"ignored"
                }]}
                """), accumulator);
        parser.accept(new SseFrameParser.SseFrame(null, "[DONE]"), accumulator);

        assertEquals("ok", accumulator.response().visibleText());
    }

    @Test
    void rejectsUnsupportedFinishReasonWithoutProducingToolCall() {
        ModelResponseAccumulator accumulator = new ModelResponseAccumulator(objectMapper, 4096);
        accumulator.onEvent(new ModelStreamEvent.ResponseStarted("resp-1"));
        accumulator.onEvent(new ModelStreamEvent.TextDelta("truncated"));
        accumulator.onEvent(new ModelStreamEvent.ResponseFinished("length"));
        accumulator.onEvent(new ModelStreamEvent.StreamEnded());

        assertThrows(AgentException.class, accumulator::response);
    }

    @Test
    void enforcesCumulativeResponseLimit() {
        ModelResponseAccumulator accumulator = new ModelResponseAccumulator(objectMapper, 5);
        accumulator.onEvent(new ModelStreamEvent.ResponseStarted("resp-1"));
        accumulator.onEvent(new ModelStreamEvent.TextDelta("12345"));

        assertThrows(AgentException.class,
                () -> accumulator.onEvent(new ModelStreamEvent.TextDelta("6")));
    }

    @Test
    void aggregatesInterleavedToolCallsInStableIndexOrder() {
        ModelResponseAccumulator accumulator = new ModelResponseAccumulator(objectMapper, 4096);
        accumulator.onEvent(new ModelStreamEvent.ResponseStarted("resp-1"));
        accumulator.onEvent(new ModelStreamEvent.ToolCallDelta(
                1, "call-2", "second", "{\"value\":"));
        accumulator.onEvent(new ModelStreamEvent.ToolCallDelta(
                0, "call-1", "first", "{\"value\":"));
        accumulator.onEvent(new ModelStreamEvent.ToolCallDelta(
                1, "", null, "2}"));
        accumulator.onEvent(new ModelStreamEvent.ToolCallDelta(
                0, "", null, "1}"));
        accumulator.onEvent(new ModelStreamEvent.ResponseFinished("tool_calls"));
        accumulator.onEvent(new ModelStreamEvent.StreamEnded());

        ModelResponse response = accumulator.response();
        assertEquals(List.of("first", "second"),
                response.toolCalls().stream().map(call -> call.name()).toList());
        assertEquals(1, response.toolCalls().get(0).arguments().get("value").asInt());
        assertEquals(2, response.toolCalls().get(1).arguments().get("value").asInt());
    }

    @Test
    void rejectsSemanticDeltaAfterResponseFinished() {
        ModelResponseAccumulator accumulator = new ModelResponseAccumulator(objectMapper, 4096);
        accumulator.onEvent(new ModelStreamEvent.ResponseStarted("resp-1"));
        accumulator.onEvent(new ModelStreamEvent.TextDelta("complete"));
        accumulator.onEvent(new ModelStreamEvent.ResponseFinished("stop"));

        assertThrows(AgentException.class,
                () -> accumulator.onEvent(new ModelStreamEvent.TextDelta("late")));
        assertThrows(AgentException.class,
                () -> accumulator.onEvent(new ModelStreamEvent.ToolCallDelta(
                        0, "late-call", "read_file", "{}")));
    }

    private static String frame(String json) {
        return "data: " + json.strip() + "\n\n";
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
