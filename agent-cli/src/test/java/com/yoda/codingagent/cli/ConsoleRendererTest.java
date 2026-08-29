package com.yoda.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yoda.codingagent.core.api.AgentEvent;
import com.yoda.codingagent.core.api.AgentResult;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.config.SecretRedactor;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConsoleRendererTest {

    @Test
    void rendersDeltasAndToolLifecycleWithoutDuplicatingTextOrSecrets() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleRenderer renderer = new ConsoleRenderer(
                new PrintWriter(bytes, true, StandardCharsets.UTF_8), value -> value);
        WorkspaceId workspace = WorkspaceId.random();
        SessionId session = SessionId.random();
        TurnId turn = TurnId.random();
        Instant now = Instant.now();

        renderer.render(new AgentEvent.ModelTextDelta(
                workspace, session, turn, 1, now, "hel"));
        renderer.render(new AgentEvent.ModelTextDelta(
                workspace, session, turn, 2, now, "lo"));
        renderer.render(new AgentEvent.ToolStarted(
                workspace, session, turn, 3, now, "call", "read_file"));
        renderer.render(new AgentEvent.ToolCompleted(
                workspace, session, turn, 4, now, "call", "read_file", true));
        renderer.render(new AgentEvent.TurnCompleted(
                workspace, session, turn, 5, now));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.startsWith("hello\n"));
        assertTrue(output.contains("[tool] read_file started"));
        assertTrue(output.contains("[tool] read_file completed"));
        assertFalse(output.contains("Authorization"));
    }

    @Test
    void redactsTextAndFailureExactlyOnceAtTheCliBoundary() {
        String key = "test-render-key";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleRenderer renderer = new ConsoleRenderer(
                new PrintWriter(bytes, true, StandardCharsets.UTF_8),
                new SecretRedactor(key)::redact);
        WorkspaceId workspace = WorkspaceId.random();
        SessionId session = SessionId.random();
        TurnId turn = TurnId.random();
        Instant now = Instant.now();

        renderer.render(new AgentEvent.ModelTextDelta(
                workspace, session, turn, 1, now, "model accidentally emitted " + key));
        renderer.render(new AgentEvent.TurnFailed(
                workspace, session, turn, 2, now, ErrorCode.INTERNAL_ERROR,
                "failure accidentally included " + key));
        renderer.renderResult(AgentResult.failed(turn, TurnStatus.FAILED,
                ErrorCode.INTERNAL_ERROR, "failure accidentally included " + key));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains(key));
        assertTrue(output.contains("<redacted>"));
        assertEquals(1, occurrences(output, "[error]"));
    }

    private static int occurrences(String value, String target) {
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(target, from)) >= 0) {
            count++;
            from += target.length();
        }
        return count;
    }
}
