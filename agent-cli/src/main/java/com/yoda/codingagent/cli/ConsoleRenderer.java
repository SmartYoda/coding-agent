package com.yoda.codingagent.cli;

import com.yoda.codingagent.core.api.AgentEvent;
import com.yoda.codingagent.core.api.AgentResult;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class ConsoleRenderer {

    private final PrintWriter output;
    private final UnaryOperator<String> redactor;
    private boolean inlineText;

    public ConsoleRenderer(PrintWriter output, UnaryOperator<String> redactor) {
        this.output = Objects.requireNonNull(output, "output");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    public synchronized void render(AgentEvent event) {
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
        } else if (event instanceof AgentEvent.TurnCompleted) {
            separateLine();
            output.flush();
        }
    }

    public synchronized void renderResult(AgentResult result) {
        separateLine();
        output.flush();
    }

    private void separateLine() {
        if (inlineText) {
            output.println();
            inlineText = false;
        }
    }
}
