package com.yoda.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.config.AgentConfigLoader;
import com.yoda.codingagent.core.model.ModelClient;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.model.ModelRequest;
import com.yoda.codingagent.core.model.ModelStreamEvent;
import com.yoda.codingagent.core.model.ModelStreamSink;
import com.yoda.codingagent.core.persistence.sqlite.SqliteStateStore;
import com.yoda.codingagent.core.persistence.sqlite.DataDirectoryLock;
import com.yoda.codingagent.core.tool.ToolStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliApplicationIntegrationTest {

    @Test
    void completesOfflineReadModifyTestLoopAndPersistsDigest(@TempDir Path temp)
            throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("broken-project"));
        createBrokenMavenProject(workspace);
        Path state = temp.resolve("state");
        AtomicInteger step = new AtomicInteger();
        ModelClient scripted = (request, sink, cancellationToken) -> {
            assertEquals(6, request.tools().size());
            switch (step.getAndIncrement()) {
                case 0 -> toolCall(sink, "run-before", "execute_command",
                        "{\"argv\":[\"mvn\",\"-q\",\"test\"]}");
                case 1 -> {
                    assertLastToolStatus(request, ToolStatus.FAILURE);
                    toolCall(sink, "read-source", "read_file",
                            "{\"path\":\"src/main/java/demo/Calculator.java\"}");
                }
                case 2 -> {
                    assertLastToolStatus(request, ToolStatus.SUCCESS);
                    toolCall(sink, "fix-source", "replace_in_file",
                            "{\"path\":\"src/main/java/demo/Calculator.java\","
                                    + "\"oldText\":\"return left - right;\","
                                    + "\"newText\":\"return left + right;\","
                                    + "\"expectedOccurrences\":1}");
                }
                case 3 -> {
                    assertLastToolStatus(request, ToolStatus.SUCCESS);
                    toolCall(sink, "run-after", "execute_command",
                            "{\"argv\":[\"mvn\",\"-q\",\"test\"]}");
                }
                case 4 -> {
                    assertLastToolStatus(request, ToolStatus.SUCCESS);
                    sink.onEvent(new ModelStreamEvent.ResponseStarted("final"));
                    sink.onEvent(new ModelStreamEvent.TextDelta("fixed and verified"));
                    sink.onEvent(new ModelStreamEvent.ResponseFinished("stop"));
                    sink.onEvent(new ModelStreamEvent.StreamEnded());
                }
                default -> throw new AssertionError("unexpected model request");
            }
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = new CliApplication(ignored -> scripted).run(new String[]{
                        "--workspace", "fixture=" + workspace,
                        "--data-dir", state.toString()}, Map.of("LLM_API_KEY", "offline-key"),
                // The fifth model request starts before the runner commits the final step.
                // Wait for the prompt restored after the turn so /exit cannot cancel that commit.
                inputAfter(() -> countOccurrences(
                        output.toString(StandardCharsets.UTF_8), "coding-agent> ") >= 2,
                        "fix it\n/exit\n"),
                output, error);

        assertEquals(0, exit, error.toString(StandardCharsets.UTF_8));
        assertEquals(5, step.get());
        assertTrue(Files.readString(workspace.resolve(
                "src/main/java/demo/Calculator.java")).contains("left + right"));
        AgentConfig config = new AgentConfigLoader().load(
                Map.of("dataDirectory", state.toString()),
                Map.of("LLM_API_KEY", "offline-key"));
        DataDirectoryLock lock = DataDirectoryLock.acquire(config.dataDirectory());
        SqliteStateStore store = SqliteStateStore.open(lock,
                config.databasePath(), config.databaseBusyTimeout());
        var registered = store.listWorkspaces();
        var session = store.listSessions(registered.getFirst().workspaceId()).getFirst();
        var history = store.loadCanonicalHistory(session.sessionId());
        var digest = history.orderedDigests().getFirst();
        assertEquals(List.of("src/main/java/demo/Calculator.java"), digest.filesRead());
        assertEquals(List.of("src/main/java/demo/Calculator.java"), digest.filesModified());
        assertEquals(2, digest.commands().size());
        assertTrue(digest.commands().get(0).contains("FAILURE"));
        assertTrue(digest.commands().get(1).contains("SUCCESS"));
        assertFalse(digest.importantErrors().isEmpty());
    }

    @Test
    void runsOneOfflineTurnAndPersistsSessionHistory(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = temp.resolve("state");
        AtomicInteger requests = new AtomicInteger();
        ModelClient scripted = new ModelClient() {
            @Override
            public void stream(ModelRequest request, ModelStreamSink sink,
                               CancellationToken cancellationToken) {
                requests.incrementAndGet();
                assertEquals(6, request.tools().size());
                sink.onEvent(new ModelStreamEvent.ResponseStarted("response"));
                sink.onEvent(new ModelStreamEvent.TextDelta("done"));
                sink.onEvent(new ModelStreamEvent.ResponseFinished("stop"));
                sink.onEvent(new ModelStreamEvent.StreamEnded());
            }
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Map<String, String> environment = Map.of("LLM_API_KEY", "offline-key");

        int exit = new CliApplication(ignored -> scripted).run(new String[]{
                        "--workspace", "main=" + workspace,
                        "--data-dir", state.toString()}, environment,
                inputAfter(() -> requests.get() == 1, "hello\n/exit\n"),
                output, error);

        assertEquals(0, exit, error.toString(StandardCharsets.UTF_8));
        assertEquals(1, requests.get());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("done"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("offline-key"));
        AgentConfig config = new AgentConfigLoader().load(
                Map.of("dataDirectory", state.toString()), environment);
        DataDirectoryLock lock = DataDirectoryLock.acquire(config.dataDirectory());
        SqliteStateStore store = SqliteStateStore.open(lock,
                config.databasePath(), config.databaseBusyTimeout());
        var registered = store.listWorkspaces();
        assertEquals(1, registered.size());
        var sessions = store.listSessions(registered.getFirst().workspaceId());
        assertEquals(1, sessions.size());
        assertEquals(1, store.loadCanonicalHistory(
                sessions.getFirst().sessionId()).completedTurns().size());
    }

    @Test
    void resumesOnlyOpenSessionFromTheSelectedWorkspace(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path otherWorkspace = Files.createDirectory(temp.resolve("other-workspace"));
        Path state = temp.resolve("state");
        Map<String, String> environment = Map.of("LLM_API_KEY", "offline-key");
        AtomicInteger completedRequests = new AtomicInteger();
        ModelClient finalModel = (request, sink, token) -> {
            completedRequests.incrementAndGet();
            sink.onEvent(new ModelStreamEvent.ResponseStarted("response"));
            sink.onEvent(new ModelStreamEvent.TextDelta("done"));
            sink.onEvent(new ModelStreamEvent.ResponseFinished("stop"));
            sink.onEvent(new ModelStreamEvent.StreamEnded());
        };
        CliApplication application = new CliApplication(ignored -> finalModel);
        String[] baseArguments = {"--workspace", "main=" + workspace,
                "--data-dir", state.toString()};

        assertEquals(0, application.run(baseArguments, environment,
                inputAfter(() -> completedRequests.get() >= 1, "first\n/exit\n"),
                new ByteArrayOutputStream(),
                new ByteArrayOutputStream()));
        AgentConfig config = new AgentConfigLoader().load(
                Map.of("dataDirectory", state.toString()), environment);
        DataDirectoryLock lock = DataDirectoryLock.acquire(config.dataDirectory());
        SqliteStateStore store = SqliteStateStore.open(lock,
                config.databasePath(), config.databaseBusyTimeout());
        var registered = store.listWorkspaces();
        var session = store.listSessions(registered.getFirst().workspaceId()).getFirst();
        lock.close();

        assertEquals(0, application.run(new String[]{"--workspace", "main=" + workspace,
                        "--data-dir", state.toString(), "--session",
                session.sessionId().value().toString()}, environment,
                inputAfter(() -> completedRequests.get() >= 2, "second\n/exit\n"),
                new ByteArrayOutputStream(),
                new ByteArrayOutputStream()));
        assertEquals(2, store.loadCanonicalHistory(session.sessionId()).completedTurns().size());

        AtomicInteger wrongWorkspaceCalls = new AtomicInteger();
        CliApplication guarded = new CliApplication(ignored -> (request, sink, token) ->
                wrongWorkspaceCalls.incrementAndGet());
        ByteArrayOutputStream wrongError = new ByteArrayOutputStream();
        int wrongWorkspace = guarded.run(new String[]{
                        "--workspace", "other=" + otherWorkspace,
                        "--data-dir", state.toString(), "--session",
                        session.sessionId().value().toString()}, environment, input("/exit\n"),
                new ByteArrayOutputStream(), wrongError);
        assertEquals(2, wrongWorkspace);
        assertTrue(wrongError.toString(StandardCharsets.UTF_8)
                .contains("session must be open and belong"));
        assertEquals(0, wrongWorkspaceCalls.get());

        store.closeSession(session.sessionId());
        ByteArrayOutputStream closedError = new ByteArrayOutputStream();
        int closed = guarded.run(new String[]{"--workspace", "main=" + workspace,
                        "--data-dir", state.toString(), "--session",
                        session.sessionId().value().toString()}, environment, input("/exit\n"),
                new ByteArrayOutputStream(), closedError);
        assertEquals(2, closed);
        assertTrue(closedError.toString(StandardCharsets.UTF_8)
                .contains("session must be open and belong"));
        assertEquals(0, wrongWorkspaceCalls.get());
    }

    @Test
    void helpShortCircuitsBeforeApiKeyLoading() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = new CliApplication(config -> {
            throw new AssertionError("model client must not be created for help");
        }).run(new String[]{"--help"}, Map.of(), InputStreamHolder.EMPTY,
                output, new ByteArrayOutputStream());

        assertEquals(0, exit);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Usage:"));
    }

    @Test
    void compositionFailureReleasesTheDataDirectoryLock(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = temp.resolve("state");
        String[] arguments = {"--workspace", "main=" + workspace,
                "--data-dir", state.toString()};
        Map<String, String> environment = Map.of("LLM_API_KEY", "offline-key");
        int failed = new CliApplication(ignored -> {
            throw new IllegalStateException("synthetic composition failure");
        }).run(arguments, environment, input("/exit\n"),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(1, failed);

        int restarted = new CliApplication(ignored -> (request, sink, token) -> {
            throw new AssertionError("model must not be called");
        }).run(arguments, environment, input("/exit\n"),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(0, restarted);
    }

    @Test
    void firstInteractiveStartupRendersRecoverySummary(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = temp.resolve("state");
        Map<String, String> environment = Map.of("LLM_API_KEY", "offline-key");
        AgentConfig config = new AgentConfigLoader().load(
                Map.of("dataDirectory", state.toString()), environment);
        DataDirectoryLock initialLock = DataDirectoryLock.acquire(config.dataDirectory());
        SqliteStateStore initial = SqliteStateStore.open(initialLock,
                config.databasePath(), config.databaseBusyTimeout());
        var registered = initial.registerWorkspace("main", workspace.toRealPath());
        var session = initial.createSessionWithSystemMessage(
                new com.yoda.codingagent.core.api.SessionConfig(
                        registered.workspaceId(), config.defaultRunLimits()), "system");
        initial.beginTurn(TurnId.random(), session.sessionId(), java.time.Instant.now(),
                "interrupted input");
        initialLock.close();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = new CliApplication(ignored -> (request, sink, token) -> {
            throw new AssertionError("model must not be called");
        }).run(new String[]{"--workspace", "main=" + workspace,
                        "--data-dir", state.toString()}, environment, input("/exit\n"),
                output, new ByteArrayOutputStream());

        assertEquals(0, exit);
        assertTrue(output.toString(StandardCharsets.UTF_8)
                .contains("Recovered interrupted state: turns=1"));
    }

    private static void toolCall(ModelStreamSink sink, String callId,
                                 String name, String arguments) {
        sink.onEvent(new ModelStreamEvent.ResponseStarted(callId));
        sink.onEvent(new ModelStreamEvent.ToolCallDelta(
                0, callId, name, arguments));
        sink.onEvent(new ModelStreamEvent.ResponseFinished("tool_calls"));
        sink.onEvent(new ModelStreamEvent.StreamEnded());
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int countOccurrences(String value, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }

    private static java.io.InputStream inputAfter(BooleanSupplier ready, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int pauseAt = value.indexOf('\n') + 1;
        return new java.io.InputStream() {
            private int index;
            private boolean waited;

            @Override
            public int read() throws java.io.IOException {
                awaitIfNeeded();
                return index >= bytes.length ? -1 : bytes[index++] & 0xff;
            }

            @Override
            public int read(byte[] target, int offset, int length) throws java.io.IOException {
                if (index >= bytes.length) {
                    return -1;
                }
                awaitIfNeeded();
                int allowed = !waited && index < pauseAt
                        ? Math.min(length, pauseAt - index) : length;
                int copied = Math.min(allowed, bytes.length - index);
                System.arraycopy(bytes, index, target, offset, copied);
                index += copied;
                return copied;
            }

            private void awaitIfNeeded() throws java.io.IOException {
                if (waited || index < pauseAt) {
                    return;
                }
                waited = true;
                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("interrupted while awaiting turn", exception);
                    }
                }
                if (!ready.getAsBoolean()) {
                    throw new java.io.IOException("turn did not reach expected state");
                }
            }
        };
    }

    private static void assertLastToolStatus(ModelRequest request, ToolStatus status) {
        Message.ToolResultMessage result = (Message.ToolResultMessage)
                request.messages().getLast();
        assertEquals(status, result.result().status());
    }

    private static void createBrokenMavenProject(Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java/demo"));
        Files.createDirectories(workspace.resolve("src/test/java/demo"));
        Files.writeString(workspace.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId><artifactId>broken</artifactId><version>1</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                      <version>5.14.4</version><scope>test</scope>
                    </dependency>
                  </dependencies>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version>
                  </plugin></plugins></build>
                </project>
                """);
        Files.writeString(workspace.resolve("src/main/java/demo/Calculator.java"), """
                package demo;
                public final class Calculator {
                    public int add(int left, int right) { return left - right; }
                }
                """);
        Files.writeString(workspace.resolve("src/test/java/demo/CalculatorTest.java"), """
                package demo;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import org.junit.jupiter.api.Test;
                class CalculatorTest {
                    @Test void adds() { assertEquals(7, new Calculator().add(3, 4)); }
                }
                """);
    }

    private static final class InputStreamHolder {
        private static final ByteArrayInputStream EMPTY = new ByteArrayInputStream(new byte[0]);
    }
}
