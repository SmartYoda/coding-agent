package com.yoda.codingagent.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.CommandAccessMode;
import com.yoda.codingagent.core.api.CommandApprovalDecision;
import com.yoda.codingagent.core.api.CommandApprovalGateway;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.config.SecretRedactor;
import com.yoda.codingagent.core.safety.CommandDecision;
import com.yoda.codingagent.core.safety.CommandPolicy;
import com.yoda.codingagent.core.tool.builtin.ExecuteCommandTool;
import com.yoda.codingagent.core.tool.process.CommandResult;
import com.yoda.codingagent.core.tool.process.CommandRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandExecutionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void commandPolicyIsExactAndFailClosed(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace")).toRealPath();
        Path state = Files.createDirectory(temp.resolve("state"));
        CommandPolicy policy = new CommandPolicy(workspace, state);

        assertEquals(CommandDecision.ALLOW,
                policy.evaluate(List.of("./mvnw", "-q", "test"), workspace));
        assertEquals(CommandDecision.ALLOW,
                policy.evaluate(List.of("git", "diff", "--stat"), workspace));
        assertEquals(CommandDecision.REQUIRE_APPROVAL,
                policy.evaluate(List.of("sh", "-lc", "echo unsafe"), workspace));
        assertEquals(CommandDecision.REQUIRE_APPROVAL,
                policy.evaluate(List.of("mvn", "-DskipTests", "package"), workspace));
        assertEquals(CommandDecision.DENY,
                policy.evaluate(List.of("rm", "-rf", "."), workspace));
        assertEquals(CommandDecision.DENY,
                policy.evaluate(List.of("git", "diff", "../../outside"), workspace));
        assertEquals(CommandDecision.DENY,
                policy.evaluate(List.of("git", "diff", ".."), workspace));
        assertEquals(CommandDecision.REQUIRE_APPROVAL,
                policy.evaluate(List.of("unknown-program"), workspace));

        List<PolicyCase> cases = List.of(
                new PolicyCase(List.of("mvn", "compile"), CommandDecision.ALLOW),
                new PolicyCase(List.of("mvnw", "-B", "test"), CommandDecision.ALLOW),
                new PolicyCase(List.of("./mvnw", "-q", "-pl", "core", "verify"),
                        CommandDecision.ALLOW),
                new PolicyCase(List.of("gradle", "test"), CommandDecision.ALLOW),
                new PolicyCase(List.of("./gradlew", "--no-daemon", "build"),
                        CommandDecision.ALLOW),
                new PolicyCase(List.of("git", "status", "--porcelain"),
                        CommandDecision.ALLOW),
                new PolicyCase(List.of("git", "log", "--oneline", "-n", "10"),
                        CommandDecision.ALLOW),
                new PolicyCase(List.of("git", "show", "HEAD", "--stat"),
                        CommandDecision.ALLOW),
                new PolicyCase(List.of("curl", "https://example.com"),
                        CommandDecision.REQUIRE_APPROVAL),
                new PolicyCase(List.of("npm", "test"), CommandDecision.REQUIRE_APPROVAL),
                new PolicyCase(List.of("gradle", "clean"),
                        CommandDecision.REQUIRE_APPROVAL),
                new PolicyCase(List.of("git", "commit"),
                        CommandDecision.REQUIRE_APPROVAL),
                new PolicyCase(List.of("git", "-C", ".", "status"),
                        CommandDecision.REQUIRE_APPROVAL),
                new PolicyCase(List.of("sudo", "mvn", "test"), CommandDecision.DENY),
                new PolicyCase(List.of("git", "diff", "agent.db"), CommandDecision.DENY),
                new PolicyCase(List.of("git", "diff", state.toString()), CommandDecision.DENY),
                new PolicyCase(List.of("git", "diff", temp.resolve("outside").toString()),
                        CommandDecision.DENY));
        cases.forEach(testCase -> assertEquals(testCase.expected(),
                policy.evaluate(testCase.argv(), workspace), testCase.argv().toString()));
    }

    @Test
    void runnerDrainsBothStreamsAndBoundsCapture(@TempDir Path temp) throws Exception {
        CommandResult result = new CommandRunner().run(List.of("sh", "-c",
                        "i=0; while [ $i -lt 500 ]; do printf out; printf err >&2; i=$((i+1)); done"),
                temp, Duration.ofSeconds(5), 256, CancellationToken.NONE);

        assertEquals(0, result.exitCode());
        assertEquals(256, result.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(256, result.stderr().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(result.truncated());
        assertFalse(result.timedOut());
    }

    @Test
    void runnerDistinguishesStartFailureTimeoutAndCancellation(@TempDir Path temp) {
        CommandRunner runner = new CommandRunner();
        CommandResult start = runner.run(List.of("definitely-not-a-real-command-9137"),
                temp, Duration.ofSeconds(1), 100, CancellationToken.NONE);
        assertTrue(start.startFailed());
        assertNull(start.exitCode());

        CommandResult timeout = runner.run(List.of("sh", "-c", "sleep 5"),
                temp, Duration.ofMillis(100), 100, CancellationToken.NONE);
        assertTrue(timeout.timedOut());

        AtomicBoolean cancelled = new AtomicBoolean(true);
        CommandResult cancellation = runner.run(List.of("sh", "-c", "sleep 5"),
                temp, Duration.ofSeconds(5), 100, cancelled::get);
        assertTrue(cancellation.cancelled());
    }

    @Test
    void timeoutTerminatesDescendantProcessTree(@TempDir Path temp) {
        CommandResult result = new CommandRunner().run(List.of("sh", "-c",
                        "sleep 30 & child=$!; echo $child; wait"),
                temp, Duration.ofMillis(150), 100, CancellationToken.NONE);

        assertTrue(result.timedOut());
        long childPid = Long.parseLong(result.stdout().trim());
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void cancellationTerminatesDescendantProcessTree(@TempDir Path temp) throws Exception {
        Path childPidFile = temp.resolve("child.pid");
        CommandResult result = new CommandRunner().run(List.of("sh", "-c",
                        "sleep 30 & child=$!; echo $child > child.pid; wait"),
                temp, Duration.ofSeconds(5), 100, () -> Files.exists(childPidFile));

        assertTrue(result.cancelled());
        long childPid = Long.parseLong(Files.readString(childPidFile).trim());
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void executeCommandMapsResultsAndDeniesBeforeStarting(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = Files.createDirectory(temp.resolve("state"));
        Process init = new ProcessBuilder("git", "init", "-q")
                .directory(workspace.toFile()).start();
        assertEquals(0, init.waitFor());
        ExecuteCommandTool tool = new ExecuteCommandTool(state, new CommandRunner());
        assertFalse(tool.definition().inputSchema().path("properties")
                .path("timeoutSeconds").has("default"));
        assertEquals(1_024, tool.definition().inputSchema().path("properties")
                .path("cwd").path("maxLength").asInt());
        ToolDispatcher dispatcher = new ToolDispatcher(new ToolRegistry(List.of(tool)),
                new SecretRedactor("")::redact, new ToolOutputTruncator());

        var successArguments = mapper.createObjectNode();
        successArguments.putArray("argv").add("git").add("status").add("--short");
        ToolResult success = dispatcher.dispatch(new ToolCall("ok", "execute_command",
                successArguments),
                context(workspace, "ok"));
        assertEquals(ToolStatus.SUCCESS, success.status());
        assertEquals("0", success.metadata().get("exitCode"));
        assertTrue(success.output().startsWith("stdout:\n"));

        var deniedArguments = mapper.createObjectNode();
        deniedArguments.putArray("argv").add("rm").add("-rf").add(".");
        ToolResult denied = dispatcher.dispatch(new ToolCall("no", "execute_command",
                deniedArguments),
                context(workspace, "no"));
        assertEquals(ToolStatus.DENIED, denied.status());
        assertEquals(ErrorCode.COMMAND_DENIED, denied.errorCode());
        assertEquals("DENY", denied.metadata().get("policyDecision"));
        assertTrue(denied.output().contains("do not retry"));
        assertFalse(denied.metadata().containsKey("exitCode"));

        var approvalArguments = mapper.createObjectNode();
        approvalArguments.putArray("argv").add("curl").add("https://example.com");
        ToolResult approvalRequired = dispatcher.dispatch(new ToolCall(
                "approval", "execute_command", approvalArguments),
                context(workspace, "approval"));
        assertEquals(ToolStatus.DENIED, approvalRequired.status());
        assertEquals("REQUIRE_APPROVAL",
                approvalRequired.metadata().get("policyDecision"));
        assertTrue(approvalRequired.output().contains("restricted access"));
        assertTrue(approvalRequired.output().contains("do not retry"));

        var cancelledArguments = mapper.createObjectNode();
        cancelledArguments.putArray("argv").add("git").add("status").add("--short");
        ToolResult cancelled = dispatcher.dispatch(new ToolCall("cancel", "execute_command",
                cancelledArguments), context(workspace, "cancel", () -> true));
        assertEquals(ToolStatus.CANCELLED, cancelled.status());
        assertEquals("true", cancelled.metadata().get("cancelled"));
    }

    @Test
    void askModeRequiresAUserDecisionAndFullAccessBypassesPolicy(@TempDir Path temp)
            throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = Files.createDirectory(temp.resolve("state"));
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<List<String>> executed = new AtomicReference<>();
        var executor = (com.yoda.codingagent.core.tool.process.CommandExecutor)
                (argv, cwd, timeout, maximumBytes, token) -> {
                    starts.incrementAndGet();
                    executed.set(argv);
                    return new CommandResult(0, "ok", "", Duration.ofMillis(1),
                            false, false, false, null);
                };
        ExecuteCommandTool tool = new ExecuteCommandTool(state, executor);
        assertEquals("Run a structured command subject to the current turn's command access "
                + "policy", tool.definition().description());
        ToolDispatcher dispatcher = new ToolDispatcher(new ToolRegistry(List.of(tool)),
                value -> value, new ToolOutputTruncator());
        var curl = mapper.createObjectNode();
        curl.putArray("argv").add("curl").add("https://example.com");

        AtomicReference<String> requestedId = new AtomicReference<>();
        CommandApprovalGateway approve = (request, token) -> {
            requestedId.set(request.approvalId());
            return CommandApprovalDecision.APPROVED;
        };
        ToolResult approved = dispatcher.dispatch(
                new ToolCall("approval-1", "execute_command", curl),
                context(workspace, "approval-1", CommandAccessMode.ASK, approve));
        assertEquals(ToolStatus.SUCCESS, approved.status());
        assertTrue(requestedId.get() != null && !requestedId.get().isBlank());
        assertEquals("USER_APPROVED", approved.metadata().get("policyDecision"));
        assertEquals(1, starts.get());

        ToolResult userDenied = dispatcher.dispatch(
                new ToolCall("approval-2", "execute_command", curl),
                context(workspace, "approval-2", CommandAccessMode.ASK,
                        (request, token) -> CommandApprovalDecision.DENIED));
        assertEquals(ToolStatus.DENIED, userDenied.status());
        assertEquals("USER_DENIED", userDenied.metadata().get("policyDecision"));
        assertEquals(1, starts.get());

        var prohibited = mapper.createObjectNode();
        prohibited.putArray("argv").add("rm").add("-rf").add(".");
        ToolResult askHardDenied = dispatcher.dispatch(
                new ToolCall("approval-3", "execute_command", prohibited),
                context(workspace, "approval-3", CommandAccessMode.ASK, approve));
        assertEquals(ToolStatus.DENIED, askHardDenied.status());
        assertEquals(1, starts.get());

        ToolResult full = dispatcher.dispatch(
                new ToolCall("full", "execute_command", prohibited),
                context(workspace, "full", CommandAccessMode.FULL_ACCESS,
                        CommandApprovalGateway.denyAll()));
        assertEquals(ToolStatus.SUCCESS, full.status());
        assertEquals("FULL_ACCESS", full.metadata().get("policyDecision"));
        assertEquals(List.of("rm", "-rf", "."), executed.get());
        assertEquals(2, starts.get());
    }

    @Test
    void executeToolNeverStartsDeniedCommandsAndUsesSmallestTimeout(@TempDir Path temp)
            throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = Files.createDirectory(temp.resolve("state"));
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<Duration> timeoutSeen = new AtomicReference<>();
        var executor = (com.yoda.codingagent.core.tool.process.CommandExecutor)
                (argv, cwd, timeout, maximumBytes, token) -> {
                    starts.incrementAndGet();
                    timeoutSeen.set(timeout);
                    return new CommandResult(0, "ok", "", Duration.ofMillis(2),
                            false, false, false, null);
                };
        ExecuteCommandTool tool = new ExecuteCommandTool(state, executor);
        ToolDispatcher dispatcher = new ToolDispatcher(new ToolRegistry(List.of(tool)),
                value -> value, new ToolOutputTruncator());

        for (List<String> deniedArgv : List.of(
                List.of("rm", "-rf", "."), List.of("curl", "https://example.com"))) {
            var deniedJson = mapper.createObjectNode();
            deniedArgv.forEach(deniedJson.putArray("argv")::add);
            ToolResult denied = dispatcher.dispatch(
                    new ToolCall("denied-" + starts.get(), "execute_command", deniedJson),
                    context(workspace, "denied", CancellationToken.NONE));
            assertEquals(ToolStatus.DENIED, denied.status());
        }
        assertEquals(0, starts.get());

        var allowed = mapper.createObjectNode();
        allowed.putArray("argv").add("mvn").add("test");
        allowed.put("timeoutSeconds", 80);
        RunLimits limits = new RunLimits(20, Duration.ofSeconds(60),
                Duration.ofSeconds(30), Duration.ofSeconds(20),
                20_000, 65_536, 8_192, 4);
        ToolContext shortTurn = new ToolContext(WorkspaceId.random(), workspace.toRealPath(),
                TurnId.random(), "allowed", Instant.now().plusSeconds(2), limits,
                CancellationToken.NONE);
        ToolResult result = dispatcher.dispatch(
                new ToolCall("allowed", "execute_command", allowed), shortTurn);

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertEquals(1, starts.get());
        assertTrue(timeoutSeen.get().compareTo(Duration.ZERO) > 0);
        assertTrue(timeoutSeen.get().compareTo(Duration.ofSeconds(2)) <= 0);
    }

    @Test
    void executeToolMapsEveryProcessTerminalOutcome(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = Files.createDirectory(temp.resolve("state"));
        Map<CommandResult, ExpectedResult> cases = new java.util.LinkedHashMap<>();
        cases.put(new CommandResult(null, "", "", Duration.ZERO,
                        false, false, false, "missing executable"),
                new ExpectedResult(ToolStatus.FAILURE, ErrorCode.COMMAND_START_FAILED));
        cases.put(new CommandResult(7, "", "failed", Duration.ofMillis(1),
                        false, false, false, null),
                new ExpectedResult(ToolStatus.FAILURE, ErrorCode.COMMAND_FAILED));
        cases.put(new CommandResult(null, "", "", Duration.ofMillis(1),
                        true, false, false, null),
                new ExpectedResult(ToolStatus.TIMED_OUT, ErrorCode.COMMAND_TIMEOUT));
        cases.put(new CommandResult(null, "", "", Duration.ofMillis(1),
                        false, true, false, null),
                new ExpectedResult(ToolStatus.CANCELLED, ErrorCode.CANCELLED));

        for (var entry : cases.entrySet()) {
            ExecuteCommandTool tool = new ExecuteCommandTool(state,
                    (argv, cwd, timeout, maximumBytes, token) -> entry.getKey());
            var arguments = mapper.createObjectNode();
            arguments.putArray("argv").add("mvn").add("test");
            ToolResult result = new ToolDispatcher(new ToolRegistry(List.of(tool)),
                    value -> value, new ToolOutputTruncator()).dispatch(
                    new ToolCall("call", "execute_command", arguments),
                    context(workspace, "call"));
            assertEquals(entry.getValue().status(), result.status());
            assertEquals(entry.getValue().errorCode(), result.errorCode());
        }
    }

    private static ToolContext context(Path workspace, String callId) throws Exception {
        return context(workspace, callId, CancellationToken.NONE);
    }

    private static ToolContext context(Path workspace, String callId,
                                       CancellationToken cancellationToken) throws Exception {
        return new ToolContext(WorkspaceId.random(), workspace.toRealPath(), TurnId.random(),
                callId, Instant.now().plusSeconds(30), RunLimits.DEFAULTS, cancellationToken);
    }

    private static ToolContext context(Path workspace, String callId,
                                       CommandAccessMode accessMode,
                                       CommandApprovalGateway approvalGateway) throws Exception {
        return new ToolContext(WorkspaceId.random(), workspace.toRealPath(), TurnId.random(),
                callId, Instant.now().plusSeconds(30), RunLimits.DEFAULTS,
                CancellationToken.NONE, accessMode, approvalGateway);
    }

    private record PolicyCase(List<String> argv, CommandDecision expected) { }

    private record ExpectedResult(ToolStatus status, ErrorCode errorCode) { }
}
