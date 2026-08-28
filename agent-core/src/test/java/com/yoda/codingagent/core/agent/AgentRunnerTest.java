package com.yoda.codingagent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.api.AgentEvent;
import com.yoda.codingagent.core.api.AgentRequest;
import com.yoda.codingagent.core.api.AgentResult;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.model.ModelClient;
import com.yoda.codingagent.core.model.ModelRequest;
import com.yoda.codingagent.core.model.ModelStreamEvent;
import com.yoda.codingagent.core.model.ModelStreamSink;
import com.yoda.codingagent.core.tool.Tool;
import com.yoda.codingagent.core.tool.ToolContext;
import com.yoda.codingagent.core.tool.ToolDefinition;
import com.yoda.codingagent.core.tool.ToolRegistry;
import com.yoda.codingagent.core.tool.ToolResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completesToolResultRoundTripAndExecutesOnlyOnce() {
        ScriptedModelClient model = new ScriptedModelClient(List.of(
                List.of(new ModelStreamEvent.ResponseStarted("one"),
                        new ModelStreamEvent.ToolCallDelta(
                                0, "call-1", "test_", "{\"value\":"),
                        new ModelStreamEvent.ToolCallDelta(
                                0, "", "echo", "\"hello\"}"),
                        new ModelStreamEvent.ResponseFinished("tool_calls"),
                        new ModelStreamEvent.StreamEnded()),
                List.of(new ModelStreamEvent.ResponseStarted("two"),
                        new ModelStreamEvent.TextDelta("任务完成"),
                        new ModelStreamEvent.ResponseFinished("stop"),
                        new ModelStreamEvent.StreamEnded())));
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry(List.of(echoTool(executions)));
        AgentRunner runner = runner(model, tools);
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = runner.run(WorkspaceId.random(), SessionId.random(),
                new AgentRequest("运行测试工具"), events::add, CancellationToken.NONE);

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals("任务完成", result.finalText());
        assertEquals(1, executions.get());
        assertEquals(2, model.requests.size());
        assertInstanceOf(Message.AssistantToolCallsMessage.class,
                model.requests.get(1).messages().get(2));
        assertInstanceOf(Message.ToolResultMessage.class,
                model.requests.get(1).messages().get(3));
        assertTrue(events.stream().anyMatch(AgentEvent.ModelTextDelta.class::isInstance));
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1L, events.get(index).sequence());
        }
    }

    @Test
    void incompleteToolCallNeverExecutes() {
        ScriptedModelClient model = new ScriptedModelClient(List.of(List.of(
                new ModelStreamEvent.ResponseStarted("one"),
                new ModelStreamEvent.ToolCallDelta(
                        0, "call-1", "test_echo", "{\"value\":"))));
        AtomicInteger executions = new AtomicInteger();

        AgentResult result = runner(model,
                new ToolRegistry(List.of(echoTool(executions)))).run(
                WorkspaceId.random(), SessionId.random(), new AgentRequest("run"),
                ignored -> { }, CancellationToken.NONE);

        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(0, executions.get());
    }

    private AgentRunner runner(ModelClient model, ToolRegistry tools) {
        return new AgentRunner(model, tools, objectMapper, "qwen3.8-flash",
                Duration.ofSeconds(10), 1024, 4096, 4);
    }

    private Tool echoTool(AtomicInteger executions) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("value").put("type", "string");
        schema.putArray("required").add("value");
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("test_echo", "Echo a test value", schema);
            }

            @Override
            public ToolResult execute(ToolContext context, ObjectNode arguments) {
                executions.incrementAndGet();
                return ToolResult.success(arguments.path("value").asText());
            }
        };
    }

    private static final class ScriptedModelClient implements ModelClient {
        private final List<List<ModelStreamEvent>> scripts;
        private final List<ModelRequest> requests = new ArrayList<>();

        private ScriptedModelClient(List<List<ModelStreamEvent>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public void stream(ModelRequest request, ModelStreamSink sink,
                           CancellationToken token) {
            int index = requests.size();
            requests.add(request);
            for (ModelStreamEvent event : scripts.get(index)) {
                sink.onEvent(event);
            }
        }
    }
}
