package com.yoda.codingagent.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yoda.codingagent.core.api.AgentResult;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.SessionStatus;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.WorkspaceStatus;
import com.yoda.codingagent.core.model.MessageKind;
import com.yoda.codingagent.core.model.MessageRole;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PersistenceContractTest {

    @Test
    void persistenceEnumsMatchTheV1SchemaContract() {
        assertEquals(Set.of("ACTIVE", "ARCHIVED", "UNAVAILABLE"), names(WorkspaceStatus.values()));
        assertEquals(Set.of("OPEN", "CLOSED"), names(SessionStatus.values()));
        assertEquals(Set.of("CREATED", "RUNNING", "STREAMING_MODEL", "EXECUTING_TOOL",
                "INTERRUPTED", "COMPLETED", "FAILED", "CANCELLED", "LIMIT_REACHED"),
                names(TurnStatus.values()));
        assertEquals(Set.of("STAGED", "COMMITTED", "ABORTED"),
                names(ModelStepStatus.values()));
        assertEquals(Set.of("PENDING", "EXECUTING", "SUCCESS", "FAILURE", "DENIED",
                "TIMED_OUT", "CANCELLED", "UNKNOWN"), names(ToolExecutionStatus.values()));
        assertEquals(Set.of("SYSTEM", "USER", "ASSISTANT", "TOOL"),
                names(MessageRole.values()));
        assertEquals(Set.of("SYSTEM_PROMPT", "USER_TEXT", "ASSISTANT_TEXT",
                "ASSISTANT_TOOL_CALLS", "TOOL_RESULT"), names(MessageKind.values()));
    }

    @Test
    void agentResultAcceptsOnlyTheFiveTerminalTurnStates() {
        TurnId turnId = TurnId.random();
        for (TurnStatus status : TurnStatus.values()) {
            if (Set.of(TurnStatus.INTERRUPTED, TurnStatus.COMPLETED, TurnStatus.FAILED,
                    TurnStatus.CANCELLED, TurnStatus.LIMIT_REACHED).contains(status)) {
                if (status == TurnStatus.COMPLETED) {
                    AgentResult.completed(turnId, "done");
                } else {
                    AgentResult.failed(turnId, status, ErrorCode.INTERNAL_ERROR, "failed");
                }
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> new AgentResult(turnId, status, null,
                                ErrorCode.INTERNAL_ERROR, "failed"));
            }
        }
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
