package com.yoda.codingagent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.api.WorkspaceStatus;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.tool.ToolCall;
import com.yoda.codingagent.core.workspace.WorkspaceContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TurnDigestTest {

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
                history,
                new WorkspaceContext(workspaceId, Path.of("/tmp/digest-workspace"),
                        WorkspaceStatus.ACTIVE),
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
