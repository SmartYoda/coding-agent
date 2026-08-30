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
    private boolean inlineText;
    private boolean promptVisible;

    public ConsoleRenderer(PrintWriter output, UnaryOperator<String> redactor) {
        this.output = Objects.requireNonNull(output, "output");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    public synchronized void render(AgentEvent event) {
        promptVisible = false;
        if (event instanceof AgentEvent.ModelTextDelta delta) {
            output.print(redactor.apply(delta.text()));
            output.flush();
            inlineText = true;
        } else if (event instanceof AgentEvent.ToolStarted started) {
            separateLine();
            output.println("[tool] " + started.toolName() + " started");
            output.flush();
        } else if (event instanceof AgentEvent.ToolCompleted completed) {
            separateLine();
            output.println("[tool] " + completed.toolName() + " "
                    + (completed.success() ? "completed" : "failed"));
            output.flush();
        } else if (event instanceof AgentEvent.TurnFailed failed) {
            separateLine();
            output.println("[error] " + failed.errorCode() + ": "
                    + redactor.apply(failed.safeMessage()));
            output.flush();
        } else if (event instanceof AgentEvent.TurnLimitReached limited) {
            separateLine();
            output.println("[limit] " + limited.errorCode() + ": "
                    + redactor.apply(limited.safeMessage()));
            output.flush();
        } else if (event instanceof AgentEvent.TurnCancelled) {
            separateLine();
            output.println("[cancelled]");
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
        output.print("coding-agent> ");
        output.flush();
        promptVisible = true;
    }

    public synchronized void commandRead() {
        promptVisible = false;
    }

    public synchronized void line(String message) {
        separateLine();
        promptVisible = false;
        output.println(redactor.apply(message));
        output.flush();
    }

    public synchronized void error(String message) {
        line("[error] " + message);
    }

    public synchronized void workspaces(List<WorkspaceDescriptor> workspaces) {
        separateLine();
        for (WorkspaceDescriptor workspace : workspaces) {
            output.println(workspace.workspaceId() + "  " + workspace.status()
                    + "  " + redactor.apply(workspace.displayName())
                    + "  " + redactor.apply(workspace.root().toString()));
        }
        if (workspaces.isEmpty()) {
            output.println("No workspaces.");
        }
        output.flush();
    }

    public synchronized void sessions(List<SessionDescriptor> sessions) {
        separateLine();
        for (SessionDescriptor session : sessions) {
            output.println(session.sessionId() + "  " + session.status());
        }
        if (sessions.isEmpty()) {
            output.println("No sessions in the current workspace.");
        }
        output.flush();
    }

    public synchronized void context(SessionContextSummary summary,
                                     ContextView.Snapshot snapshot) {
        separateLine();
        output.println("Session: " + summary.sessionId());
        output.println("Workspace: " + summary.workspaceId());
        output.println("Completed turns: " + summary.completedTurnCount()
                + ", digests: " + summary.digestCount());
        if (snapshot == null) {
            output.println("Context snapshot: unavailable in this process");
        } else {
            output.println("Context tokens: " + snapshot.estimatedInputTokens()
                    + "/" + snapshot.maxInputTokens()
                    + ", full turns: " + snapshot.fullTurnIds().size()
                    + ", digests: " + snapshot.digestTurnIds().size()
                    + ", omitted: " + snapshot.omittedTurnCount());
        }
        output.flush();
    }

    public synchronized void recovery(StateStore.RecoverySummary summary) {
        if (summary.interruptedTurns() == 0 && summary.abortedSteps() == 0
                && summary.unknownToolCalls() == 0 && summary.cancelledToolCalls() == 0) {
            return;
        }
        line("Recovered interrupted state: turns=" + summary.interruptedTurns()
                + ", steps=" + summary.abortedSteps()
                + ", unknown-tools=" + summary.unknownToolCalls()
                + ", cancelled-tools=" + summary.cancelledToolCalls());
    }

    private void separateLine() {
        if (inlineText) {
            output.println();
            inlineText = false;
        }
    }
}
