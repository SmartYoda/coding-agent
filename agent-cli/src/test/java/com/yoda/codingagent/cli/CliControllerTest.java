package com.yoda.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yoda.codingagent.core.api.AgentEvent;
import com.yoda.codingagent.core.api.AgentEventSink;
import com.yoda.codingagent.core.api.AgentRequest;
import com.yoda.codingagent.core.api.AgentResult;
import com.yoda.codingagent.core.api.AgentService;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionContextSummary;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.SessionStatus;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.ThinkingMode;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.api.WorkspaceStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CliControllerTest {

    @Test
    void instantaneousTurnReturnsToIdleAndRestoresPromptOnce() throws Exception {
        Fixture fixture = fixture(false);
        fixture.renderer().prompt();
        fixture.renderer().commandRead();

        fixture.controller().handle(new CliCommand.Prompt("hello"));

        awaitIdle(fixture.controller());
        assertEquals(1, fixture.service().turnCalls.get());
        assertEquals(2, occurrences(fixture.output(), "coding-agent> "));
        fixture.controller().handle(new CliCommand.Exit());
    }

    @Test
    void thinkingSelectionIsCapturedPerTurnAndDefaultUsesStartupValue() throws Exception {
        Fixture fixture = fixture(false, true);

        fixture.controller().handle(new CliCommand.ThinkingShow());
        fixture.controller().handle(new CliCommand.Prompt("default"));
        awaitIdle(fixture.controller());
        fixture.controller().handle(new CliCommand.ThinkingSet(ThinkingMode.DISABLED));
        fixture.controller().handle(new CliCommand.Prompt("off"));
        awaitIdle(fixture.controller());
        fixture.controller().handle(new CliCommand.ThinkingSet(ThinkingMode.DEFAULT));
        fixture.controller().handle(new CliCommand.Prompt("default again"));
        awaitIdle(fixture.controller());

        assertEquals(List.of(ThinkingMode.DEFAULT, ThinkingMode.DISABLED,
                        ThinkingMode.DEFAULT),
                fixture.service().requests.stream().map(AgentRequest::thinkingMode).toList());
        assertTrue(fixture.output().contains("override=DEFAULT, effective=on"));
        assertTrue(fixture.output().contains("override=DISABLED, effective=off"));
        fixture.controller().handle(new CliCommand.Exit());
    }

    @Test
    void activeTurnRejectsThinkingChanges() throws Exception {
        Fixture fixture = fixture(true);
        fixture.controller().handle(new CliCommand.Prompt("block"));
        assertTrue(fixture.service().started.await(2, TimeUnit.SECONDS));

        fixture.controller().handle(new CliCommand.ThinkingSet(ThinkingMode.ENABLED));
        fixture.controller().handle(new CliCommand.Cancel());
        awaitIdle(fixture.controller());

        assertEquals(ThinkingMode.DEFAULT,
                fixture.service().requests.getFirst().thinkingMode());
        assertTrue(fixture.output().contains("A turn is active"));
        fixture.controller().handle(new CliCommand.Exit());
    }

    @Test
    void repeatedCancelUsesOneTokenAndAllowsTheNextTurn() throws Exception {
        Fixture fixture = fixture(true);
        fixture.controller().handle(new CliCommand.Prompt("block"));
        assertTrue(fixture.service().started.await(2, TimeUnit.SECONDS));

        fixture.controller().handle(new CliCommand.Cancel());
        fixture.controller().handle(new CliCommand.Cancel());

        awaitIdle(fixture.controller());
        assertEquals(1, fixture.service().turnCalls.get());
        fixture.service().blocking = false;
        fixture.controller().handle(new CliCommand.Prompt("next"));
        awaitIdle(fixture.controller());
        assertEquals(2, fixture.service().turnCalls.get());
        fixture.controller().handle(new CliCommand.Exit());
    }

    @Test
    void completionAndCancellationRaceAlwaysReturnsToIdle() throws Exception {
        Fixture fixture = fixture(true);
        fixture.renderer().prompt();
        fixture.renderer().commandRead();
        fixture.controller().handle(new CliCommand.Prompt("race"));
        assertTrue(await(fixture.service().started));
        CountDownLatch startRace = new CountDownLatch(1);
        Thread completion = Thread.ofVirtual().start(() -> {
            awaitUnchecked(startRace);
            fixture.service().blocking = false;
        });
        Thread cancellation = Thread.ofVirtual().start(() -> {
            awaitUnchecked(startRace);
            fixture.controller().handle(new CliCommand.Cancel());
        });

        startRace.countDown();
        completion.join();
        cancellation.join();
        awaitIdle(fixture.controller());

        assertEquals(1, fixture.service().turnCalls.get());
        assertEquals(2, occurrences(fixture.output(), "coding-agent> "));
        fixture.controller().handle(new CliCommand.Exit());
    }

    @Test
    void rejectedSubmissionRollsBackToIdle() {
        Fixture fixture = fixture(false);
        fixture.executor().shutdownNow();

        fixture.controller().handle(new CliCommand.Prompt("hello"));

        assertEquals(CliController.State.IDLE, fixture.controller().state());
        assertTrue(fixture.output().contains("executor rejected"));
    }

    @Test
    void arbitraryExecutorFailureRollsBackToIdle() {
        Fixture fixture = fixture(false, new ThrowingExecutor());

        fixture.controller().handle(new CliCommand.Prompt("hello"));

        assertEquals(CliController.State.IDLE, fixture.controller().state());
        assertTrue(fixture.output().contains("executor failed"));
    }

    @Test
    void crossWorkspaceCloseIsRejectedBeforeMutation() {
        Fixture fixture = fixture(false);
        SessionDescriptor foreign = new SessionDescriptor(SessionId.random(),
                WorkspaceId.random(), SessionStatus.OPEN, Instant.now(), Instant.now());
        fixture.service().sessions.add(foreign);

        assertThrows(IllegalArgumentException.class, () -> fixture.controller()
                .handle(new CliCommand.SessionClose(foreign.sessionId())));
        assertEquals(0, fixture.service().closeCalls.get());
        fixture.controller().handle(new CliCommand.Exit());
    }

    @Test
    void contextViewIgnoresLateEventsFromOlderGeneration() {
        ContextView view = new ContextView();
        WorkspaceId workspaceId = WorkspaceId.random();
        SessionId sessionId = SessionId.random();
        TurnId oldTurn = TurnId.random();
        TurnId newTurn = TurnId.random();
        Instant now = Instant.now();
        view.accept(2, new AgentEvent.ContextBudgetEvaluated(workspaceId, sessionId,
                newTurn, 1, now, 1, 1, 1, 1, 1, 5, 1, 10));
        view.accept(1, new AgentEvent.ContextCompacted(workspaceId, sessionId,
                oldTurn, 2, now, List.of(), List.of(), 7, 9, 4, "TOKEN_BUDGET"));

        ContextView.Snapshot snapshot = view.snapshotFor(sessionId);
        assertEquals(newTurn, snapshot.turnId());
        assertEquals(0, snapshot.omittedTurnCount());
    }

    @Test
    void closingSessionsUpdatesSelectionOnlyForTheSelectedSession() throws Exception {
        Fixture fixture = fixture(false);
        SessionDescriptor selected = fixture.service().sessions.getFirst();
        SessionDescriptor other = new SessionDescriptor(SessionId.random(),
                selected.workspaceId(), SessionStatus.OPEN, Instant.now(), Instant.now());
        fixture.service().sessions.add(other);

        fixture.controller().handle(new CliCommand.SessionClose(other.sessionId()));
        fixture.controller().handle(new CliCommand.Prompt("still selected"));
        awaitIdle(fixture.controller());
        assertEquals(1, fixture.service().turnCalls.get());

        fixture.controller().handle(new CliCommand.SessionClose(selected.sessionId()));
        fixture.controller().handle(new CliCommand.Prompt("must not run"));
        assertEquals(1, fixture.service().turnCalls.get());
        assertTrue(fixture.output().contains("No OPEN session is selected"));
        fixture.controller().handle(new CliCommand.Exit());
    }

    @Test
    void workspaceAndSessionCommandsUseOneSelectionState() {
        Fixture fixture = fixture(false);
        WorkspaceDescriptor second = new WorkspaceDescriptor(WorkspaceId.random(),
                "second", Path.of("/tmp/cli-controller-second"), WorkspaceStatus.ACTIVE);
        fixture.service().workspaces.add(second);

        fixture.controller().handle(new CliCommand.WorkspaceUse(second.workspaceId()));
        fixture.controller().handle(new CliCommand.SessionNew());

        SessionDescriptor opened = fixture.service().sessions.getLast();
        assertEquals(second.workspaceId(), opened.workspaceId());
        fixture.controller().handle(new CliCommand.SessionList());
        fixture.controller().handle(new CliCommand.Context());
        assertTrue(fixture.output().contains(opened.sessionId().toString()));
        assertTrue(fixture.output().contains("Completed turns: 0"));
        fixture.controller().handle(new CliCommand.Exit());
    }

    @Test
    void exitTimesOutAtFiveSecondsAndClosesTheController() throws Exception {
        Fixture fixture = fixture(true);
        fixture.service().ignoreCancellation = true;
        fixture.controller().handle(new CliCommand.Prompt("block"));
        assertTrue(await(fixture.service().started));
        long startedAt = System.nanoTime();

        CliController.Outcome outcome = fixture.controller().handle(new CliCommand.Exit());

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertEquals(1, outcome.exitCode());
        assertEquals(CliController.State.CLOSED, fixture.controller().state());
        assertTrue(elapsedMillis >= 4_500 && elapsedMillis < 7_000,
                "exit wait was " + elapsedMillis + " ms");
        fixture.service().blocking = false;
    }

    private static Fixture fixture(boolean blocking) {
        return fixture(blocking, false, Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().factory()));
    }

    private static Fixture fixture(boolean blocking, boolean defaultThinkingEnabled) {
        return fixture(blocking, defaultThinkingEnabled,
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()));
    }

    private static Fixture fixture(boolean blocking, ExecutorService executor) {
        return fixture(blocking, false, executor);
    }

    private static Fixture fixture(boolean blocking, boolean defaultThinkingEnabled,
                                   ExecutorService executor) {
        RunLimits limits = new RunLimits(4, Duration.ofSeconds(30),
                Duration.ofSeconds(10), Duration.ofSeconds(5),
                4_096, 8_192, 1_024, 2);
        WorkspaceDescriptor workspace = new WorkspaceDescriptor(WorkspaceId.random(),
                "main", Path.of("/tmp/cli-controller"), WorkspaceStatus.ACTIVE);
        SessionDescriptor session = new SessionDescriptor(SessionId.random(),
                workspace.workspaceId(), SessionStatus.OPEN, Instant.now(), Instant.now());
        StubService service = new StubService(workspace, session, limits, blocking);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleRenderer renderer = new ConsoleRenderer(
                new PrintWriter(bytes, true, StandardCharsets.UTF_8), value -> value);
        CliController controller = new CliController(service, limits, defaultThinkingEnabled,
                workspace, session, renderer, new ContextView(), executor);
        return new Fixture(service, renderer, controller, executor, bytes);
    }

    private static void awaitIdle(CliController controller) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (controller.state() != CliController.State.IDLE
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(CliController.State.IDLE, controller.state());
    }

    private static int occurrences(String value, String target) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(target, index)) >= 0;
                index += target.length()) {
            count++;
        }
        return count;
    }

    private static boolean await(CountDownLatch latch) throws InterruptedException {
        return latch.await(2, TimeUnit.SECONDS);
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private record Fixture(StubService service, ConsoleRenderer renderer,
                           CliController controller, ExecutorService executor,
                           ByteArrayOutputStream bytes) {
        String output() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class StubService implements AgentService {
        private final WorkspaceDescriptor workspace;
        private final RunLimits limits;
        private final List<WorkspaceDescriptor> workspaces = new ArrayList<>();
        private final List<SessionDescriptor> sessions = new ArrayList<>();
        private final AtomicInteger turnCalls = new AtomicInteger();
        private final List<AgentRequest> requests = Collections.synchronizedList(
                new ArrayList<>());
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private volatile boolean blocking;
        private volatile boolean ignoreCancellation;

        private StubService(WorkspaceDescriptor workspace, SessionDescriptor session,
                            RunLimits limits, boolean blocking) {
            this.workspace = workspace;
            this.limits = limits;
            this.blocking = blocking;
            workspaces.add(workspace);
            sessions.add(session);
        }

        @Override
        public WorkspaceDescriptor registerWorkspace(String displayName, Path root) {
            WorkspaceDescriptor added = new WorkspaceDescriptor(WorkspaceId.random(), displayName,
                    root, WorkspaceStatus.ACTIVE);
            workspaces.add(added);
            return added;
        }

        @Override
        public List<WorkspaceDescriptor> listWorkspaces() { return List.copyOf(workspaces); }

        @Override
        public void archiveWorkspace(WorkspaceId workspaceId) { }

        @Override
        public SessionDescriptor openSession(SessionConfig config) {
            SessionDescriptor opened = new SessionDescriptor(SessionId.random(),
                    config.workspaceId(), SessionStatus.OPEN, Instant.now(), Instant.now());
            sessions.add(opened);
            return opened;
        }

        @Override
        public List<SessionDescriptor> listSessions(WorkspaceId workspaceId) {
            return sessions.stream().filter(session ->
                    session.workspaceId().equals(workspaceId)).toList();
        }

        @Override
        public SessionDescriptor getSession(SessionId sessionId) {
            return sessions.stream().filter(session -> session.sessionId().equals(sessionId))
                    .findFirst().orElseThrow();
        }

        @Override
        public SessionContextSummary getSessionContext(SessionId sessionId) {
            SessionDescriptor descriptor = getSession(sessionId);
            return new SessionContextSummary(sessionId, descriptor.workspaceId(), limits, 0, 0);
        }

        @Override
        public void closeSession(SessionId sessionId) {
            closeCalls.incrementAndGet();
            for (int index = 0; index < sessions.size(); index++) {
                SessionDescriptor descriptor = sessions.get(index);
                if (descriptor.sessionId().equals(sessionId)) {
                    sessions.set(index, new SessionDescriptor(descriptor.sessionId(),
                            descriptor.workspaceId(), SessionStatus.CLOSED,
                            descriptor.createdAt(), Instant.now()));
                    return;
                }
            }
        }

        @Override
        public AgentResult runTurn(SessionId sessionId, AgentRequest request,
                                   AgentEventSink eventSink,
                                   CancellationToken cancellationToken) {
            turnCalls.incrementAndGet();
            requests.add(request);
            started.countDown();
            while (blocking && (ignoreCancellation || !cancellationToken.isCancelled())) {
                Thread.onSpinWait();
            }
            TurnId turnId = TurnId.random();
            if (cancellationToken.isCancelled()) {
                return AgentResult.failed(workspace.workspaceId(), sessionId, turnId,
                        TurnStatus.CANCELLED, ErrorCode.CANCELLED, "cancelled",
                        0, 0, Duration.ZERO);
            }
            eventSink.publish(new AgentEvent.ModelTextDelta(workspace.workspaceId(),
                    sessionId, turnId, 1, Instant.now(), "done"));
            eventSink.publish(new AgentEvent.TurnCompleted(workspace.workspaceId(),
                    sessionId, turnId, 2, Instant.now()));
            return AgentResult.completed(workspace.workspaceId(), sessionId, turnId,
                    "done", 1, 0, Duration.ofMillis(1));
        }
    }

    private static final class ThrowingExecutor extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() { shutdown = true; }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() { return shutdown; }

        @Override
        public boolean isTerminated() { return shutdown; }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }

        @Override
        public void execute(Runnable command) {
            throw new IllegalStateException("synthetic executor failure");
        }
    }
}
