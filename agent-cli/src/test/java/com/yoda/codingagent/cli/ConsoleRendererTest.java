package com.yoda.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yoda.codingagent.core.api.AgentEvent;
import com.yoda.codingagent.core.api.AgentResult;
import com.yoda.codingagent.core.api.CommandApprovalDecision;
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
import java.time.Duration;
import java.util.Map;
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
        renderer.renderResult(AgentResult.failed(workspace, session, turn,
                TurnStatus.FAILED, ErrorCode.INTERNAL_ERROR,
                "failure accidentally included " + key, 1, 0, Duration.ofSeconds(1)));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains(key));
        assertTrue(output.contains("<redacted>"));
        assertEquals(1, occurrences(output, "[error]"));
    }

    @Test
    void colorsStructuredEventsWithoutColoringStreamedModelText() {
        String key = "test-render-key";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleRenderer renderer = new ConsoleRenderer(
                new PrintWriter(bytes, true, StandardCharsets.UTF_8),
                new SecretRedactor(key)::redact,
                TerminalStyle.resolve(Map.of("CODING_AGENT_COLOR", "always"), false));
        WorkspaceId workspace = WorkspaceId.random();
        SessionId session = SessionId.random();
        TurnId turn = TurnId.random();
        Instant now = Instant.now();

        renderer.render(new AgentEvent.ModelTextDelta(
                workspace, session, turn, 1, now,
                "plain\u001B[31m " + key));
        renderer.render(new AgentEvent.ToolStarted(
                workspace, session, turn, 2, now, "call", "read_file"));
        renderer.render(new AgentEvent.ToolCompleted(
                workspace, session, turn, 3, now, "call", "read_file", false));
        renderer.render(new AgentEvent.TurnFailed(
                workspace, session, turn, 4, now, ErrorCode.INTERNAL_ERROR, "failed turn"));
        renderer.render(new AgentEvent.TurnLimitReached(
                workspace, session, turn, 5, now, ErrorCode.TURN_LIMIT, "step limit"));
        renderer.render(new AgentEvent.TurnCancelled(
                workspace, session, turn, 6, now));
        renderer.prompt();

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.startsWith("plain[31m <redacted>\n"));
        assertTrue(output.contains("\u001B[35m[tool]\u001B[0m"));
        assertTrue(output.contains("\u001B[33mstarted\u001B[0m"));
        assertTrue(output.contains("\u001B[1;31mfailed\u001B[0m"));
        assertTrue(output.contains("\u001B[1;31m[error] INTERNAL_ERROR:\u001B[0m"));
        assertTrue(output.contains("\u001B[1;33m[limit] TURN_LIMIT:\u001B[0m"));
        assertTrue(output.contains("\u001B[33m[cancelled]\u001B[0m"));
        assertTrue(output.endsWith("\u001B[1;36mcoding-agent> \u001B[0m"));
        assertFalse(output.contains(key));
    }

    @Test
    void plainRendererKeepsExistingOutputExactlyForNormalText() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleRenderer renderer = new ConsoleRenderer(
                new PrintWriter(bytes, true, StandardCharsets.UTF_8), value -> value);
        WorkspaceId workspace = WorkspaceId.random();
        SessionId session = SessionId.random();
        TurnId turn = TurnId.random();
        Instant now = Instant.now();

        renderer.render(new AgentEvent.ToolStarted(
                workspace, session, turn, 1, now, "call", "read_file"));
        renderer.render(new AgentEvent.ToolCompleted(
                workspace, session, turn, 2, now, "call", "read_file", true));
        renderer.error("problem");
        renderer.prompt();
        renderer.prompt();

        assertEquals("[tool] read_file started\n"
                + "[tool] read_file completed\n"
                + "[error] problem\n"
                + "coding-agent> ", bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rendersShortToolDetailsAndRedactsThem() {
        String key = "detail-secret";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleRenderer renderer = new ConsoleRenderer(
                new PrintWriter(bytes, true, StandardCharsets.UTF_8),
                new SecretRedactor(key)::redact);
        WorkspaceId workspace = WorkspaceId.random();
        SessionId session = SessionId.random();
        TurnId turn = TurnId.random();
        Instant now = Instant.now();

        renderer.render(new AgentEvent.ToolStarted(
                workspace, session, turn, 1, now, "call", "read_file",
                "src/" + key + "/App.java"));
        renderer.render(new AgentEvent.ToolCompleted(
                workspace, session, turn, 2, now, "call", "read_file",
                "src/" + key + "/App.java", true));

        assertEquals("[tool] read_file started — src/<redacted>/App.java\n"
                + "[tool] read_file completed — src/<redacted>/App.java\n",
                bytes.toString(StandardCharsets.UTF_8));
        assertFalse(bytes.toString(StandardCharsets.UTF_8).contains(key));
    }

    @Test
    void rendersApprovalCommandAndResolutionWithoutLeakingSecrets() {
        String key = "approval-secret";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleRenderer renderer = new ConsoleRenderer(
                new PrintWriter(bytes, true, StandardCharsets.UTF_8),
                new SecretRedactor(key)::redact);
        WorkspaceId workspace = WorkspaceId.random();
        SessionId session = SessionId.random();
        TurnId turn = TurnId.random();
        Instant now = Instant.now();

        renderer.render(new AgentEvent.CommandApprovalRequested(
                workspace, session, turn, 1, now, "call-7", "call-7",
                java.util.List.of("curl", "https://example.com/" + key,
                        "two words", "line one\n[approval] forged\rnext"), "/tmp"));
        renderer.render(new AgentEvent.CommandApprovalResolved(
                workspace, session, turn, 2, now, "call-7",
                CommandApprovalDecision.APPROVED));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[approval required] id=call-7"));
        assertTrue(output.contains("command: [\"curl\",\"https://example.com/<redacted>\","
                + "\"two words\",\"line one\\n[approval] forged\\rnext\"]"));
        assertTrue(output.contains("/approve call-7"));
        assertTrue(output.contains("[approval] call-7 approved"));
        assertFalse(output.contains(key));
        assertFalse(output.contains("line one\n[approval] forged"));
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
