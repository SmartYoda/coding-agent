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
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.agent.AgentRunner;
import com.yoda.codingagent.core.agent.DefaultAgentService;
import com.yoda.codingagent.core.agent.SessionRegistry;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.config.AgentConfigLoader;
import com.yoda.codingagent.core.config.SecretRedactor;
import com.yoda.codingagent.core.context.ContextManager;
import com.yoda.codingagent.core.context.TokenEstimator;
import com.yoda.codingagent.core.context.TurnDigestFactory;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.model.ModelRequest;
import com.yoda.codingagent.core.model.ModelResponse;
import com.yoda.codingagent.core.model.ModelResponseAccumulator;
import com.yoda.codingagent.core.persistence.sqlite.SqliteStateStore;
import com.yoda.codingagent.core.tool.ToolDefinition;
import com.yoda.codingagent.core.tool.Tool;
import com.yoda.codingagent.core.tool.ToolArguments;
import com.yoda.codingagent.core.tool.ToolContext;
import com.yoda.codingagent.core.tool.ToolRegistry;
import com.yoda.codingagent.core.tool.ToolDispatcher;
import com.yoda.codingagent.core.tool.ToolOutputTruncator;
import com.yoda.codingagent.core.tool.ToolResult;
import com.yoda.codingagent.core.workspace.WorkspaceRegistry;
import com.yoda.codingagent.core.workspace.WorkspaceResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        TurnId turnId = TurnId.random();
        messages.add(new Message.SystemMessage(
                "You are testing tool calling. Call read_file exactly once when asked. "
                        + "After its result, answer with a short final summary and no more tools."));
        messages.add(new Message.UserMessage(turnId,
                "Call read_file for pom.xml. Do not answer from memory."));

        ModelResponse first = invoke(client, config, objectMapper, messages, List.of(tool));

        assertEquals("tool_calls", first.finishReason());
        assertEquals(1, first.toolCalls().size());
        assertEquals("read_file", first.toolCalls().getFirst().name());
        messages.add(new Message.AssistantToolCallsMessage(
                turnId, first.visibleText(), first.toolCalls()));
        messages.add(new Message.ToolResultMessage(turnId,
                first.toolCalls().getFirst().callId(),
                "<project><artifactId>coding-agent-parent</artifactId></project>"));

        ModelResponse second = invoke(client, config, objectMapper, messages, List.of(tool));

        assertEquals("stop", second.finishReason());
        assertFalse(second.visibleText().isBlank());
    }

    @Test
    void realQwenDrivesAgentRunnerAndExecutesCompletedToolExactlyOnce(
            @TempDir Path tempDirectory) throws Exception {
        Map<String, String> environment = liveEnvironment();
        ObjectMapper objectMapper = new ObjectMapper();
        AgentConfig config = new AgentConfigLoader().load(
                Map.of("dataDirectory", tempDirectory.resolve("state").toString()), environment);
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
            public ToolResult execute(ToolContext context, ToolArguments arguments) {
                executions.incrementAndGet();
                assertEquals("pom.xml", arguments.allowOnly("path")
                        .requireString("path", 1, 1_024));
                return ToolResult.success(
                        "<project><artifactId>coding-agent-parent</artifactId></project>");
            }
        };
        SqliteStateStore store = SqliteStateStore.open(
                config.databasePath(), config.databaseBusyTimeout());
        Path root = Files.createDirectory(tempDirectory.resolve("workspace"));
        WorkspaceRegistry workspaces = new WorkspaceRegistry(store,
                new WorkspaceResolver(config.dataDirectory()));
        var workspace = workspaces.register("Live", root);
        SessionRegistry sessions = new SessionRegistry(store, workspaces);
        AgentRunner runner = new AgentRunner(
                new OpenAiCompatibleChatModelClient(config),
                new ToolDispatcher(new ToolRegistry(List.of(readFile)),
                        new SecretRedactor(config.apiKey())::redact, new ToolOutputTruncator()),
                objectMapper, config.model(),
                config.maxResponseCharacters(), store,
                new ContextManager(new TokenEstimator()), new TurnDigestFactory());
        DefaultAgentService service = new DefaultAgentService(workspaces, sessions, runner,
                DefaultAgentService.DEFAULT_SYSTEM_PROMPT);
        var session = service.openSession(new SessionConfig(workspace.workspaceId(),
                new RunLimits(4, Duration.ofMinutes(2), config.modelTimeout(),
                        Duration.ofSeconds(10), 16_384, 8_192, 512, 2)));
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = service.runTurn(session.sessionId(),
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
