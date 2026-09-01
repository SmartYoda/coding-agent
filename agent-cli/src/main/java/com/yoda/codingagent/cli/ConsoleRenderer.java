package com.yoda.codingagent.cli;

import com.yoda.codingagent.core.api.AgentEvent;
import com.yoda.codingagent.core.api.AgentResult;
import com.yoda.codingagent.core.api.SessionContextSummary;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.persistence.StateStore;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class ConsoleRenderer {

    private final PrintWriter output;
    private final UnaryOperator<String> redactor;
    private final TerminalStyle style;
    private boolean inlineText;
    private boolean promptVisible;

    public ConsoleRenderer(PrintWriter output, UnaryOperator<String> redactor) {
        this(output, redactor, TerminalStyle.plain());
    }

    ConsoleRenderer(PrintWriter output, UnaryOperator<String> redactor,
                    TerminalStyle style) {
        this.output = Objects.requireNonNull(output, "output");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.style = Objects.requireNonNull(style, "style");
    }

    public synchronized void render(AgentEvent event) {
        promptVisible = false;
        if (event instanceof AgentEvent.ModelTextDelta delta) {
            output.print(safe(delta.text()));
            output.flush();
            inlineText = true;
        } else if (event instanceof AgentEvent.ToolStarted started) {
            separateLine();
            output.println(style.tool("[tool]") + " " + style.safe(started.toolName())
                    + " " + style.warning("started"));
            output.flush();
        } else if (event instanceof AgentEvent.ToolCompleted completed) {
            separateLine();
            String status = completed.success()
                    ? style.success("completed") : style.error("failed");
            output.println(style.tool("[tool]") + " " + style.safe(completed.toolName())
                    + " " + status);
            output.flush();
        } else if (event instanceof AgentEvent.TurnFailed failed) {
            separateLine();
            output.println(style.error("[error] " + failed.errorCode() + ":")
                    + " " + safe(failed.safeMessage()));
            output.flush();
        } else if (event instanceof AgentEvent.TurnLimitReached limited) {
            separateLine();
            output.println(style.strongWarning("[limit] " + limited.errorCode() + ":")
                    + " " + safe(limited.safeMessage()));
            output.flush();
        } else if (event instanceof AgentEvent.TurnCancelled) {
            separateLine();
            output.println(style.warning("[cancelled]"));
            output.flush();
        } else if (event instanceof AgentEvent.TurnCompleted) {
            separateLine();
            output.flush();
        }
    }

    public synchronized void renderResult(AgentResult result) {
        separateLine();
        output.flush();
    }

    public synchronized void prompt() {
        separateLine();
        if (promptVisible) {
            return;
        }
        output.print(style.prompt("coding-agent> "));
        output.flush();
        promptVisible = true;
    }

    public synchronized void commandRead() {
        promptVisible = false;
    }

    public synchronized void line(String message) {
        separateLine();
        promptVisible = false;
        output.println(safe(message));
        output.flush();
    }

    public synchronized void error(String message) {
        separateLine();
        promptVisible = false;
        output.println(style.error("[error]") + " " + safe(message));
        output.flush();
    }

    public synchronized void workspaces(List<WorkspaceDescriptor> workspaces) {
        separateLine();
        for (WorkspaceDescriptor workspace : workspaces) {
            output.println(style.identifier(workspace.workspaceId().toString()) + "  "
                    + workspaceStatus(workspace) + "  " + safe(workspace.displayName())
                    + "  " + safe(workspace.root().toString()));
        }
        if (workspaces.isEmpty()) {
            output.println(style.muted("No workspaces."));
        }
        output.flush();
    }

    public synchronized void sessions(List<SessionDescriptor> sessions) {
        separateLine();
        for (SessionDescriptor session : sessions) {
            output.println(style.identifier(session.sessionId().toString()) + "  "
                    + sessionStatus(session));
        }
        if (sessions.isEmpty()) {
            output.println(style.muted("No sessions in the current workspace."));
        }
        output.flush();
    }

    public synchronized void context(SessionContextSummary summary,
                                     ContextView.Snapshot snapshot) {
        separateLine();
        output.println(style.label("Session:") + " "
                + style.identifier(summary.sessionId().toString()));
        output.println(style.label("Workspace:") + " "
                + style.identifier(summary.workspaceId().toString()));
        output.println(style.label("Completed turns:") + " "
                + summary.completedTurnCount() + ", " + style.label("digests:")
                + " " + summary.digestCount());
        if (snapshot == null) {
            output.println(style.label("Context snapshot:")
                    + " unavailable in this process");
        } else {
            output.println(style.label("Context tokens:") + " "
                    + snapshot.estimatedInputTokens()
                    + "/" + snapshot.maxInputTokens()
                    + ", " + style.label("full turns:") + " "
                    + snapshot.fullTurnIds().size()
                    + ", " + style.label("digests:") + " "
                    + snapshot.digestTurnIds().size()
                    + ", " + style.label("omitted:") + " "
                    + snapshot.omittedTurnCount());
        }
        output.flush();
    }

    public synchronized void recovery(StateStore.RecoverySummary summary) {
        if (summary.interruptedTurns() == 0 && summary.abortedSteps() == 0
                && summary.unknownToolCalls() == 0 && summary.cancelledToolCalls() == 0) {
            return;
        }
        separateLine();
        promptVisible = false;
        output.println(style.warning("Recovered interrupted state:")
                + " turns=" + summary.interruptedTurns()
                + ", steps=" + summary.abortedSteps()
                + ", unknown-tools=" + summary.unknownToolCalls()
                + ", cancelled-tools=" + summary.cancelledToolCalls());
        output.flush();
    }

    private String workspaceStatus(WorkspaceDescriptor workspace) {
        return switch (workspace.status()) {
            case ACTIVE -> style.success(workspace.status().toString());
            case ARCHIVED -> style.inactive(workspace.status().toString());
            case UNAVAILABLE -> style.error(workspace.status().toString());
        };
    }

    private String sessionStatus(SessionDescriptor session) {
        return switch (session.status()) {
            case OPEN -> style.success(session.status().toString());
            case CLOSED -> style.inactive(session.status().toString());
        };
    }

    private String safe(String message) {
        return style.safe(redactor.apply(message));
    }

    private void separateLine() {
        if (inlineText) {
            output.println();
            inlineText = false;
        }
    }
}
