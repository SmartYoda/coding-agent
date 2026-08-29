package com.yoda.codingagent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.tool.ToolCall;
import com.yoda.codingagent.core.tool.ToolResult;
import com.yoda.codingagent.core.tool.ToolStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TurnDigestTest {

    @Test
    void digestUsesPairedResultsAndNeverInfersSuccessfulWrites() {
        ObjectMapper mapper = new ObjectMapper();
        TurnId turnId = TurnId.random();
        ToolCall read = new ToolCall("read", "read_file",
                mapper.createObjectNode().put("path", "A.java"));
        ToolCall write = new ToolCall("write", "write_file",
                mapper.createObjectNode().put("path", "A.java"));
        ObjectNode commandArguments = mapper.createObjectNode();
        commandArguments.putArray("argv").add("mvn").add("test");
        ToolCall command = new ToolCall("cmd", "execute_command", commandArguments);
        ToolResult commandFailure = new ToolResult(ToolStatus.FAILURE,
                "safe failure", ErrorCode.COMMAND_FAILED, false, Duration.ofMillis(10),
                Map.of("exitCode", "1"));
        CanonicalHistory.TurnHistory history = new CanonicalHistory.TurnHistory(turnId, List.of(
                new Message.UserMessage(turnId, "fix it"),
                new Message.AssistantToolCallsMessage(turnId, "", List.of(read, write, command)),
                new Message.ToolResultMessage(turnId, "read", ToolResult.success("source")),
                new Message.ToolResultMessage(turnId, "write",
                        ToolResult.failure(ErrorCode.FILE_IO_ERROR, "write failed")),
                new Message.ToolResultMessage(turnId, "cmd", commandFailure),
                new Message.AssistantMessage(turnId, "could not finish")));

        TurnDigest digest = new TurnDigestFactory().create(history);

        assertEquals(List.of("A.java"), digest.filesRead());
        assertTrue(digest.filesModified().isEmpty());
        assertEquals(List.of("[\"mvn\",\"test\"] status=FAILURE exitCode=1"),
                digest.commands());
        assertEquals(2, digest.importantErrors().size());
        assertTrue(digest.importantErrors().get(0).contains("write_file(write)"));
    }

    @Test
    void digestRejectsOrphanResults() {
        TurnId turnId = TurnId.random();
        CanonicalHistory.TurnHistory history = new CanonicalHistory.TurnHistory(turnId, List.of(
                new Message.UserMessage(turnId, "goal"),
                new Message.ToolResultMessage(turnId, "orphan", "value"),
                new Message.AssistantMessage(turnId, "done")));

        assertThrows(IllegalArgumentException.class,
                () -> new TurnDigestFactory().create(history));
    }

    @Test
    void factoryIsDeterministicAndSnapshotUsesDigestInsteadOfOldDetails() {
        ObjectMapper mapper = new ObjectMapper();
        WorkspaceId workspaceId = WorkspaceId.random();
        TurnId oldTurnId = TurnId.random();
        TurnId recentTurnId = TurnId.random();
        TurnId currentTurnId = TurnId.random();
        CanonicalHistory.TurnHistory oldTurn = new CanonicalHistory.TurnHistory(oldTurnId,
                List.of(
                        new Message.UserMessage(oldTurnId, "read then update a.txt"),
                        new Message.AssistantToolCallsMessage(oldTurnId, "", List.of(
                                new ToolCall("read", "read_file",
                                        mapper.createObjectNode().put("path", "a.txt")),
                                new ToolCall("write", "write_file",
                                        mapper.createObjectNode().put("path", "a.txt")))),
                        new Message.ToolResultMessage(oldTurnId, "read", "old"),
                        new Message.ToolResultMessage(oldTurnId, "write", "ok"),
                        new Message.AssistantMessage(oldTurnId, "updated a.txt")));
        TurnDigestFactory factory = new TurnDigestFactory();
        TurnDigest firstDigest = factory.create(oldTurn);
        TurnDigest secondDigest = factory.create(oldTurn);
        assertEquals(firstDigest, secondDigest);
        assertEquals(List.of("a.txt"), firstDigest.filesRead());
        assertEquals(List.of("a.txt"), firstDigest.filesModified());

        List<Message> completeMessages = List.of(
                new Message.SystemMessage("system"),
                oldTurn.messages().get(0), oldTurn.messages().get(1), oldTurn.messages().get(2),
                oldTurn.messages().get(3), oldTurn.messages().get(4),
                new Message.UserMessage(recentTurnId, "recent"),
                new Message.AssistantMessage(recentTurnId, "recent done"));
        CanonicalHistory history = new CanonicalHistory(SessionId.random(), workspaceId,
                completeMessages, Map.of(oldTurnId, firstDigest));
        ContextSnapshot snapshot = new ContextManager(new TokenEstimator()).buildSnapshot(
                history, workspaceId, Path.of("/tmp/digest-workspace"),
                List.of(new Message.UserMessage(currentTurnId, "current")),
                List.of(), new ContextBudgetPolicy(4096, 512, 1));

        assertTrue(snapshot.compacted());
        assertInstanceOf(Message.TurnDigestMessage.class, snapshot.messages().get(1));
        assertEquals(oldTurnId,
                ((Message.TurnDigestMessage) snapshot.messages().get(1)).turnId());
        assertEquals(8, history.messages().size(), "building a snapshot must not delete history");
        assertTrue(snapshot.budget().digestTokens() > 0);
    }
}
