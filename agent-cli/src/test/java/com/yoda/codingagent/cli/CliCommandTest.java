package com.yoda.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yoda.codingagent.core.api.ThinkingMode;
import com.yoda.codingagent.core.api.CommandAccessMode;
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
        assertInstanceOf(CliCommand.ThinkingShow.class, CliCommand.parse("/thinking"));
        assertEquals(ThinkingMode.ENABLED,
                ((CliCommand.ThinkingSet) CliCommand.parse("/thinking ON")).mode());
        assertEquals(ThinkingMode.DISABLED,
                ((CliCommand.ThinkingSet) CliCommand.parse("/thinking off")).mode());
        assertEquals(ThinkingMode.DEFAULT,
                ((CliCommand.ThinkingSet) CliCommand.parse("/thinking default")).mode());
        assertInstanceOf(CliCommand.AccessShow.class, CliCommand.parse("/access"));
        assertEquals(CommandAccessMode.RESTRICTED,
                ((CliCommand.AccessSet) CliCommand.parse("/access restricted")).mode());
        assertEquals(CommandAccessMode.ASK,
                ((CliCommand.AccessSet) CliCommand.parse("/access ASK")).mode());
        assertEquals(CommandAccessMode.FULL_ACCESS,
                ((CliCommand.AccessSet) CliCommand.parse("/access full")).mode());
        assertEquals("call-1",
                ((CliCommand.Approve) CliCommand.parse("/approve call-1")).approvalId());
        assertEquals("call-2",
                ((CliCommand.Deny) CliCommand.parse("/deny call-2")).approvalId());
        assertInstanceOf(CliCommand.Exit.class, CliCommand.parse(null));

        assertThrows(IllegalArgumentException.class,
                () -> CliCommand.parse("/workspace add missing-path"));
        assertThrows(IllegalArgumentException.class,
                () -> CliCommand.parse("/unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> CliCommand.parse("/thinking yes"));
        assertThrows(IllegalArgumentException.class,
                () -> CliCommand.parse("/thinking on extra"));
        assertThrows(IllegalArgumentException.class,
                () -> CliCommand.parse("/access unsafe"));
        assertThrows(IllegalArgumentException.class,
                () -> CliCommand.parse("/approve"));
    }
}
