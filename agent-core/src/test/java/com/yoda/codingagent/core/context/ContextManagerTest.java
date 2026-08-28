package com.yoda.codingagent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.api.WorkspaceStatus;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.tool.ToolCall;
import com.yoda.codingagent.core.tool.ToolDefinition;
import com.yoda.codingagent.core.workspace.WorkspaceContext;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextManagerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TokenEstimator estimator = new TokenEstimator();
    private final ContextManager manager = new ContextManager(estimator);

    @Test
    void keepsCurrentAndMostRecentCompleteTurnWithoutSplittingToolGroup() {
        WorkspaceId workspaceId = WorkspaceId.random();
        TurnId first = TurnId.random();
        TurnId second = TurnId.random();
        TurnId current = TurnId.random();
        ToolCall call = new ToolCall("call-1", "read_file", arguments("a.txt"));
        CanonicalHistory history = new CanonicalHistory(SessionId.random(), workspaceId,
                List.of(
                        new Message.SystemMessage("system"),
                        new Message.UserMessage(first, "first user"),
                        new Message.AssistantToolCallsMessage(first, "", List.of(call)),
                        new Message.ToolResultMessage(first, "call-1", "first result"),
                        new Message.AssistantMessage(first, "first final"),
                        new Message.UserMessage(second, "second user"),
                        new Message.AssistantMessage(second, "second final")));
        List<Message> currentMessages = List.of(new Message.UserMessage(current, "current user"));
        WorkspaceContext workspace = new WorkspaceContext(workspaceId,
                Path.of("/tmp/context-workspace"), WorkspaceStatus.ACTIVE);

        ContextSnapshot snapshot = manager.buildSnapshot(history, workspace, currentMessages,
                List.of(toolDefinition()), new ContextBudgetPolicy(4096, 512, 1));

        assertTrue(snapshot.compacted());
        assertEquals(4, snapshot.messages().size());
        assertTrue(snapshot.messages().getFirst() instanceof Message.SystemMessage);
        assertEquals(second, ((Message.UserMessage) snapshot.messages().get(1)).turnId());
        assertEquals(current, ((Message.UserMessage) snapshot.messages().getLast()).turnId());
        assertFalse(snapshot.messages().stream().anyMatch(message ->
                message instanceof Message.UserMessage user && user.turnId().equals(first)));
        assertTrue(snapshot.budget().totalWithReserve()
                <= snapshot.budget().maxInputTokens());
    }

    @Test
    void rejectsRequiredPartitionsOverBudgetAndBrokenToolGroups() {
        WorkspaceId workspaceId = WorkspaceId.random();
        TurnId current = TurnId.random();
        CanonicalHistory history = new CanonicalHistory(SessionId.random(), workspaceId,
                List.of(new Message.SystemMessage("system")));
        WorkspaceContext workspace = new WorkspaceContext(workspaceId,
                Path.of("/tmp/context-workspace"), WorkspaceStatus.ACTIVE);
        List<Message> largeCurrent = List.of(new Message.UserMessage(current, "x".repeat(1000)));

        AgentException limit = assertThrows(AgentException.class,
                () -> manager.buildSnapshot(history, workspace, largeCurrent,
                        List.of(toolDefinition()), new ContextBudgetPolicy(100, 20, 0)));
        assertEquals(ErrorCode.CONTEXT_LIMIT, limit.errorCode());

        ToolCall call = new ToolCall("call-1", "read_file", arguments("a.txt"));
        List<Message> incomplete = List.of(
                new Message.UserMessage(current, "read"),
                new Message.AssistantToolCallsMessage(current, "", List.of(call)));
        assertThrows(IllegalArgumentException.class,
                () -> manager.buildSnapshot(history, workspace, incomplete,
                        List.of(toolDefinition()), new ContextBudgetPolicy(4096, 512, 0)));

        List<Message> prematureFinalText = List.of(
                new Message.UserMessage(current, "read"),
                new Message.AssistantMessage(current, "not committed yet"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.buildSnapshot(history, workspace, prematureFinalText,
                        List.of(toolDefinition()), new ContextBudgetPolicy(4096, 512, 0)));
    }

    @Test
    void canonicalHistoryRejectsOrphanAndMismatchedToolResults() {
        TurnId turnId = TurnId.random();
        ToolCall call = new ToolCall("call-1", "read_file", arguments("a.txt"));

        assertThrows(IllegalArgumentException.class, () -> new CanonicalHistory(
                SessionId.random(), WorkspaceId.random(), List.of(
                new Message.SystemMessage("system"),
                new Message.UserMessage(turnId, "read"),
                new Message.AssistantToolCallsMessage(turnId, "", List.of(call)),
                new Message.ToolResultMessage(turnId, "other-call", "result"),
                new Message.AssistantMessage(turnId, "done"))));
    }

    private ObjectNode arguments(String path) {
        return objectMapper.createObjectNode().put("path", path);
    }

    private ToolDefinition toolDefinition() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("path").put("type", "string");
        return new ToolDefinition("read_file", "Read a workspace file", schema);
    }
}
