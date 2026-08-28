package com.yoda.codingagent.core.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.AgentEvent;
import com.yoda.codingagent.core.api.AgentRequest;
import com.yoda.codingagent.core.api.AgentResult;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.agent.AgentRunner;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.config.AgentConfigLoader;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.model.ModelRequest;
import com.yoda.codingagent.core.model.ModelResponse;
import com.yoda.codingagent.core.model.ModelResponseAccumulator;
import com.yoda.codingagent.core.tool.ToolDefinition;
import com.yoda.codingagent.core.tool.Tool;
import com.yoda.codingagent.core.tool.ToolContext;
import com.yoda.codingagent.core.tool.ToolRegistry;
import com.yoda.codingagent.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in real API test. It is skipped unless RUN_QWEN_LIVE_TEST=true is present. */
class QwenLiveSmokeTest {

    @Test
    void qwenFlashStreamsOneToolCallAndThenFinalText() {
        Map<String, String> environment = liveEnvironment();

        ObjectMapper objectMapper = new ObjectMapper();
        AgentConfig config = new AgentConfigLoader().load(Map.of(), environment);
        OpenAiCompatibleChatModelClient client =
                new OpenAiCompatibleChatModelClient(config);
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("path").put("type", "string");
        schema.putArray("required").add("path");
        ToolDefinition tool = new ToolDefinition(
                "read_file", "Read a UTF-8 file relative to the workspace", schema);
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.SystemMessage(
                "You are testing tool calling. Call read_file exactly once when asked. "
                        + "After its result, answer with a short final summary and no more tools."));
        messages.add(new Message.UserMessage(
                "Call read_file for pom.xml. Do not answer from memory."));

        ModelResponse first = invoke(client, config, objectMapper, messages, List.of(tool));

        assertEquals("tool_calls", first.finishReason());
        assertEquals(1, first.toolCalls().size());
        assertEquals("read_file", first.toolCalls().getFirst().name());
        messages.add(new Message.AssistantToolCallsMessage(
                first.visibleText(), first.toolCalls()));
        messages.add(new Message.ToolResultMessage(first.toolCalls().getFirst().callId(),
                "<project><artifactId>coding-agent-parent</artifactId></project>"));

        ModelResponse second = invoke(client, config, objectMapper, messages, List.of(tool));

        assertEquals("stop", second.finishReason());
        assertFalse(second.visibleText().isBlank());
    }

    @Test
    void realQwenDrivesAgentRunnerAndExecutesCompletedToolExactlyOnce() {
        Map<String, String> environment = liveEnvironment();
        ObjectMapper objectMapper = new ObjectMapper();
        AgentConfig config = new AgentConfigLoader().load(Map.of(), environment);
        AtomicInteger executions = new AtomicInteger();
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("path").put("type", "string");
        schema.putArray("required").add("path");
        Tool readFile = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("read_file",
                        "Read a UTF-8 file relative to the workspace", schema);
            }

            @Override
            public ToolResult execute(ToolContext context, ObjectNode arguments) {
                executions.incrementAndGet();
                assertEquals("pom.xml", arguments.path("path").asText());
                return ToolResult.success(
                        "<project><artifactId>coding-agent-parent</artifactId></project>");
            }
        };
        AgentRunner runner = new AgentRunner(
                new OpenAiCompatibleChatModelClient(config),
                new ToolRegistry(List.of(readFile)), objectMapper, config.model(),
                config.modelTimeout(), 512, config.maxResponseCharacters(), 4);
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = runner.run(WorkspaceId.random(), SessionId.random(),
                new AgentRequest("必须先且仅调用一次 read_file 读取 pom.xml；收到工具结果后，"
                        + "不要再次调用工具，只用一句中文说明读取到的 artifactId。"),
                events::add, CancellationToken.NONE);

        assertEquals(TurnStatus.COMPLETED, result.status(), result.errorMessage());
        assertFalse(result.finalText().isBlank());
        assertEquals(1, executions.get());
        assertEquals(1, events.stream().filter(AgentEvent.ToolStarted.class::isInstance).count());
        int completedToolResponse = indexOfToolCallCompletion(events);
        int toolStarted = indexOf(events, AgentEvent.ToolStarted.class);
        assertTrue(completedToolResponse >= 0 && completedToolResponse < toolStarted,
                "the complete model response must precede tool execution");
        int textDelta = indexOf(events, AgentEvent.ModelTextDelta.class);
        int finalResponseCompleted = indexOfStopCompletion(events);
        assertTrue(textDelta >= 0 && textDelta < finalResponseCompleted,
                "text delta must be published before the final stream completes");
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1L, events.get(index).sequence());
        }
    }

    private static Map<String, String> liveEnvironment() {
        Map<String, String> environment = System.getenv();
        Assumptions.assumeTrue("true".equalsIgnoreCase(environment.get("RUN_QWEN_LIVE_TEST")),
                "live Qwen test is opt-in");
        Assumptions.assumeTrue(environment.containsKey("DASHSCOPE_API_KEY")
                        || environment.containsKey("LLM_API_KEY"),
                "live Qwen test requires an API key environment variable");
        return environment;
    }

    private static int indexOfToolCallCompletion(List<AgentEvent> events) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index) instanceof AgentEvent.ModelRequestCompleted completed
                    && "tool_calls".equals(completed.finishReason())) {
                return index;
            }
        }
        return -1;
    }

    private static int indexOfStopCompletion(List<AgentEvent> events) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index) instanceof AgentEvent.ModelRequestCompleted completed
                    && "stop".equals(completed.finishReason())) {
                return index;
            }
        }
        return -1;
    }

    private static int indexOf(List<AgentEvent> events, Class<? extends AgentEvent> type) {
        for (int index = 0; index < events.size(); index++) {
            if (type.isInstance(events.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static ModelResponse invoke(OpenAiCompatibleChatModelClient client,
                                        AgentConfig config, ObjectMapper objectMapper,
                                        List<Message> messages, List<ToolDefinition> tools) {
        ModelResponseAccumulator accumulator = new ModelResponseAccumulator(
                objectMapper, config.maxResponseCharacters());
        client.stream(new ModelRequest(config.model(), messages, tools,
                        config.modelTimeout(), 512), accumulator, CancellationToken.NONE);
        return accumulator.response();
    }
}
