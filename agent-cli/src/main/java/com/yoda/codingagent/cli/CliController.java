package com.yoda.codingagent.cli;

import com.yoda.codingagent.core.api.AgentRequest;
import com.yoda.codingagent.core.api.AgentService;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.SessionStatus;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.api.WorkspaceStatus;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CliController {

    enum State { IDLE, RUNNING, CANCELLING, CLOSED }

    private final AgentService service;
    private final RunLimits defaultRunLimits;
    private final ConsoleRenderer renderer;
    private final ContextView contextView;
    private final ExecutorService executor;

    private State state = State.IDLE;
    private WorkspaceDescriptor workspace;
    private SessionDescriptor session;
    private CancellationSource cancellationSource;
    private FutureTask<Void> activeTask;
    private long generation;
    private boolean exiting;

    CliController(AgentService service, RunLimits defaultRunLimits,
                  WorkspaceDescriptor workspace, SessionDescriptor session,
                  ConsoleRenderer renderer, ContextView contextView,
                  ExecutorService executor) {
        this.service = Objects.requireNonNull(service, "service");
        this.defaultRunLimits = Objects.requireNonNull(defaultRunLimits, "defaultRunLimits");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.session = Objects.requireNonNull(session, "session");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.contextView = Objects.requireNonNull(contextView, "contextView");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    Outcome handle(CliCommand command) {
        Objects.requireNonNull(command, "command");
        if (command instanceof CliCommand.Exit) {
            return new Outcome(true, false, exit());
        }
        if (command instanceof CliCommand.Cancel) {
            cancel();
            return Outcome.continueWithPrompt();
        }
        if (command instanceof CliCommand.Context) {
            showContext();
            return Outcome.continueWithPrompt();
        }
        if (command instanceof CliCommand.Help) {
            renderer.line(helpText());
            return Outcome.continueWithPrompt();
        }
        if (command instanceof CliCommand.Prompt prompt) {
            startTurn(prompt.text());
            return new Outcome(false, false, 0);
        }
        if (!idle()) {
            renderer.error("A turn is active; use /cancel, /context, /help, or /exit.");
            return Outcome.continueWithPrompt();
        }
        handleManagement(command);
        return Outcome.continueWithPrompt();
    }

    synchronized State state() {
        return state;
    }

    private void startTurn(String input) {
        long taskGeneration;
        SessionId sessionId;
        final CancellationSource source = new CancellationSource();
        FutureTask<Void> task;
        String rejection = null;
        synchronized (this) {
            if (state != State.IDLE) {
                rejection = "A turn is already active.";
                taskGeneration = 0;
                sessionId = null;
                task = null;
            } else if (session == null || session.status() != SessionStatus.OPEN) {
                rejection = "No OPEN session is selected; use /session new or /session use.";
                taskGeneration = 0;
                sessionId = null;
                task = null;
            } else {
                taskGeneration = ++generation;
                sessionId = session.sessionId();
                cancellationSource = source;
                state = State.RUNNING;
                long capturedGeneration = taskGeneration;
                SessionId capturedSessionId = sessionId;
                task = new FutureTask<>(() -> {
                    runTurn(capturedGeneration, capturedSessionId, input, source);
                    return null;
                });
                activeTask = task;
            }
        }
        if (rejection != null) {
            renderer.error(rejection);
            renderer.prompt();
            return;
        }
        try {
            executor.execute(task);
        } catch (RuntimeException exception) {
            synchronized (this) {
                if (generation == taskGeneration && activeTask == task) {
                    activeTask = null;
                    cancellationSource = null;
                    state = State.IDLE;
                }
            }
            String reason = exception instanceof RejectedExecutionException
                    ? "the executor rejected it" : "the executor failed";
            renderer.error("Could not start the turn because " + reason + ".");
            renderer.prompt();
        }
    }

    private void runTurn(long taskGeneration, SessionId sessionId, String input,
                         CancellationSource source) {
        try {
            var result = service.runTurn(sessionId, new AgentRequest(input), event -> {
                contextView.accept(taskGeneration, event);
                renderer.render(event);
            }, source);
            renderer.renderResult(result);
        } catch (RuntimeException exception) {
            renderer.error(exception.getMessage() == null
                    ? "Turn failed unexpectedly." : exception.getMessage());
        } finally {
            boolean restorePrompt = false;
            synchronized (this) {
                if (generation == taskGeneration && cancellationSource == source) {
                    activeTask = null;
                    cancellationSource = null;
                    if (state != State.CLOSED) {
                        state = State.IDLE;
                    }
                    restorePrompt = !exiting;
                }
            }
            if (restorePrompt) {
                renderer.prompt();
            }
        }
    }

    private void cancel() {
        CancellationSource source;
        String message;
        synchronized (this) {
            if (state == State.IDLE) {
                source = null;
                message = "No active turn to cancel.";
            } else if (state == State.CLOSED) {
                source = null;
                message = null;
            } else {
                source = cancellationSource;
                state = State.CANCELLING;
                message = "Cancellation requested.";
            }
        }
        if (source != null) {
            source.cancel();
        }
        if (message != null) {
            renderer.line(message);
        }
    }

    private int exit() {
        FutureTask<Void> task;
        CancellationSource source;
        synchronized (this) {
            if (state == State.CLOSED) {
                return 0;
            }
            exiting = true;
            task = activeTask;
            source = cancellationSource;
            if (task != null) {
                state = State.CANCELLING;
            }
        }
        if (source != null) {
            source.cancel();
        }
        int exitCode = 0;
        if (task != null) {
            try {
                task.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exitCode = 1;
            } catch (ExecutionException exception) {
                exitCode = 1;
            } catch (TimeoutException exception) {
                task.cancel(true);
                exitCode = 1;
                renderer.error("Turn did not stop within five seconds; forcing exit.");
            }
        }
        executor.shutdownNow();
        synchronized (this) {
            state = State.CLOSED;
        }
        return exitCode;
    }

    private void showContext() {
        SessionDescriptor current;
        synchronized (this) {
            current = session;
        }
        if (current == null) {
            renderer.error("No session is selected.");
            return;
        }
        renderer.context(service.getSessionContext(current.sessionId()),
                contextView.snapshotFor(current.sessionId()));
    }

    private void handleManagement(CliCommand command) {
        if (command instanceof CliCommand.WorkspaceList) {
            renderer.workspaces(service.listWorkspaces());
        } else if (command instanceof CliCommand.WorkspaceAdd add) {
            WorkspaceDescriptor added = service.registerWorkspace(add.name(), add.path());
            renderer.line("Registered workspace " + added.workspaceId());
        } else if (command instanceof CliCommand.WorkspaceUse use) {
            WorkspaceDescriptor selected = service.listWorkspaces().stream()
                    .filter(candidate -> candidate.workspaceId().equals(use.workspaceId()))
                    .findFirst().orElseThrow(() ->
                            new IllegalArgumentException("workspace does not exist"));
            if (selected.status() != WorkspaceStatus.ACTIVE) {
                throw new IllegalArgumentException("workspace is not active");
            }
            synchronized (this) {
                workspace = selected;
                session = null;
            }
            renderer.line("Selected workspace " + selected.workspaceId()
                    + "; select or create a session.");
        } else if (command instanceof CliCommand.WorkspaceArchive archive) {
            service.archiveWorkspace(archive.workspaceId());
            synchronized (this) {
                if (workspace.workspaceId().equals(archive.workspaceId())) {
                    session = null;
                }
            }
            renderer.line("Workspace archived.");
        } else if (command instanceof CliCommand.SessionList) {
            renderer.sessions(service.listSessions(currentWorkspaceId()));
        } else if (command instanceof CliCommand.SessionNew) {
            SessionDescriptor opened = service.openSession(
                    new SessionConfig(currentWorkspaceId(), defaultRunLimits));
            synchronized (this) {
                session = opened;
            }
            renderer.line("Selected new session " + opened.sessionId());
        } else if (command instanceof CliCommand.SessionUse use) {
            SessionDescriptor selected = service.getSession(use.sessionId());
            requireCurrentOpenSession(selected);
            synchronized (this) {
                session = selected;
            }
            renderer.line("Selected session " + selected.sessionId());
        } else if (command instanceof CliCommand.SessionClose close) {
            SessionId target = close.sessionId() == null
                    ? currentSessionId() : close.sessionId();
            SessionDescriptor descriptor = service.getSession(target);
            if (!descriptor.workspaceId().equals(currentWorkspaceId())) {
                throw new IllegalArgumentException(
                        "session does not belong to the current workspace");
            }
            service.closeSession(target);
            synchronized (this) {
                if (session != null && session.sessionId().equals(target)) {
                    session = null;
                }
            }
            renderer.line("Session closed.");
        }
    }

    private synchronized boolean idle() {
        return state == State.IDLE;
    }

    private synchronized WorkspaceId currentWorkspaceId() {
        return workspace.workspaceId();
    }

    private synchronized SessionId currentSessionId() {
        if (session == null) {
            throw new IllegalArgumentException("no session is selected");
        }
        return session.sessionId();
    }

    private void requireCurrentOpenSession(SessionDescriptor descriptor) {
        if (!descriptor.workspaceId().equals(currentWorkspaceId())
                || descriptor.status() != SessionStatus.OPEN) {
            throw new IllegalArgumentException(
                    "session must be OPEN and belong to the current workspace");
        }
    }

    static String helpText() {
        return """
                /workspace list|add <name> <path>|use <id>|archive <id>
                /session list|new|use <id>|close [id]
                /context  show persisted summary and the latest in-process context budget
                /cancel   request cancellation of the active turn
                /help     show commands
                /exit     cancel, wait at most five seconds, and exit""";
    }

    record Outcome(boolean exit, boolean prompt, int exitCode) {
        static Outcome continueWithPrompt() {
            return new Outcome(false, true, 0);
        }
    }

    private static final class CancellationSource implements CancellationToken {
        private volatile boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        private void cancel() {
            cancelled = true;
        }
    }
}
