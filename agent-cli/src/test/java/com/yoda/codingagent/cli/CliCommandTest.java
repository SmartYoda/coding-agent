package com.yoda.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CliCommandTest {

    @Test
    void parsesTheCompleteCommandSurfaceAndRejectsUnknownSyntax() {
        assertInstanceOf(CliCommand.Prompt.class, CliCommand.parse("fix the tests"));
        assertInstanceOf(CliCommand.WorkspaceList.class,
                CliCommand.parse("/workspace list"));
        assertInstanceOf(CliCommand.WorkspaceAdd.class,
                CliCommand.parse("/workspace add demo /tmp/a path"));
        assertInstanceOf(CliCommand.SessionNew.class, CliCommand.parse("/session new"));
        assertInstanceOf(CliCommand.SessionClose.class,
                CliCommand.parse("/session close"));
        assertInstanceOf(CliCommand.Context.class, CliCommand.parse("/context"));
        assertInstanceOf(CliCommand.Cancel.class, CliCommand.parse("/cancel"));
        assertInstanceOf(CliCommand.Exit.class, CliCommand.parse(null));

        assertThrows(IllegalArgumentException.class,
                () -> CliCommand.parse("/workspace add missing-path"));
        assertThrows(IllegalArgumentException.class,
                () -> CliCommand.parse("/unknown"));
    }
}
