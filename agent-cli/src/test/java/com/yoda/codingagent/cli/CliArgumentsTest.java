package com.yoda.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CliArgumentsTest {

    @Test
    void parsesEveryDayThreeFlagExactlyOnce() {
        UUID session = UUID.randomUUID();
        CliArguments arguments = CliArguments.parse(new String[]{
                "--workspace", "main=/tmp/a=b",
                "--session", session.toString(),
                "--base-url", "http://127.0.0.1:8080/v1",
                "--model", "test-model",
                "--data-dir", "/tmp/state",
                "--max-steps", "7",
                "--turn-timeout-seconds", "60",
                "--model-timeout-seconds", "30",
                "--command-timeout-seconds", "20",
                "--max-tool-output-chars", "4096",
                "--max-input-tokens", "16384",
                "--reserved-output-tokens", "2048",
                "--recent-full-turns", "3"});

        assertEquals("main", arguments.workspaceName());
        assertEquals(Path.of("/tmp/a=b"), arguments.workspacePath());
        assertEquals(session, arguments.sessionId().value());
        assertEquals("7", arguments.configOverrides().get("maxSteps"));
        assertEquals("3", arguments.configOverrides().get("recentFullTurns"));
    }

    @Test
    void defaultsWorkspaceAndHelpNeedsNoOtherConfiguration() {
        CliArguments empty = CliArguments.parse(new String[0]);
        assertNull(empty.workspacePath());
        assertNull(empty.sessionId());
        assertTrue(CliArguments.parse(new String[]{"--help"}).help());
    }

    @Test
    void rejectsUnknownDuplicateMissingAndMalformedOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArguments.parse(new String[]{"--unknown", "x"}));
        assertThrows(IllegalArgumentException.class,
                () -> CliArguments.parse(new String[]{"--model", "a", "--model", "b"}));
        assertThrows(IllegalArgumentException.class,
                () -> CliArguments.parse(new String[]{"--model"}));
        assertThrows(IllegalArgumentException.class,
                () -> CliArguments.parse(new String[]{"--workspace", "missing-separator"}));
        assertThrows(IllegalArgumentException.class,
                () -> CliArguments.parse(new String[]{"--session", "not-a-uuid"}));
    }
}
