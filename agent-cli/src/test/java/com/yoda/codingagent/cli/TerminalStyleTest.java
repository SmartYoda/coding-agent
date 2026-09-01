package com.yoda.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalStyleTest {

    @Test
    void alwaysWrapsEveryStyledFragmentAndResetsIt() {
        TerminalStyle style = TerminalStyle.resolve(
                Map.of("CODING_AGENT_COLOR", "always"), false);

        assertTrue(style.enabled());
        assertEquals("\u001B[1;36mcoding-agent> \u001B[0m",
                style.prompt("coding-agent> "));
        assertEquals("\u001B[35m[tool]\u001B[0m", style.tool("[tool]"));
        assertEquals("\u001B[32mcompleted\u001B[0m", style.success("completed"));
        assertEquals("\u001B[33mstarted\u001B[0m", style.warning("started"));
        assertEquals("\u001B[1;33m[limit]\u001B[0m",
                style.strongWarning("[limit]"));
        assertEquals("\u001B[1;31m[error]\u001B[0m", style.error("[error]"));
        assertEquals("\u001B[1mSession:\u001B[0m", style.label("Session:"));
        assertEquals("id", style.identifier("id"));
        assertEquals("CLOSED", style.inactive("CLOSED"));
        assertEquals("\u001B[2mNo sessions.\u001B[0m", style.muted("No sessions."));
    }

    @Test
    void autoUsesOnlyInteractiveNonDumbTerminals() {
        assertTrue(TerminalStyle.resolve(Map.of(), true).enabled());
        assertFalse(TerminalStyle.resolve(Map.of(), false).enabled());
        assertFalse(TerminalStyle.resolve(Map.of("TERM", "dumb"), true).enabled());
    }

    @Test
    void explicitModesAndNoColorUseDocumentedPrecedence() {
        assertTrue(TerminalStyle.resolve(
                Map.of("CODING_AGENT_COLOR", " ALWAYS "), false).enabled());
        assertFalse(TerminalStyle.resolve(
                Map.of("CODING_AGENT_COLOR", "never"), true).enabled());
        assertFalse(TerminalStyle.resolve(Map.of(
                "CODING_AGENT_COLOR", "always", "NO_COLOR", "1"), true).enabled());
        assertFalse(TerminalStyle.resolve(Map.of(
                "CODING_AGENT_COLOR", "invalid", "NO_COLOR", "1"), true).enabled());
    }

    @Test
    void invalidModeFailsClearlyWhenNoColorDoesNotOverrideIt() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> TerminalStyle.resolve(
                        Map.of("CODING_AGENT_COLOR", "sometimes"), true));

        assertEquals("CODING_AGENT_COLOR must be auto, always, or never",
                exception.getMessage());
    }

    @Test
    void removesUntrustedTerminalControlsButPreservesTextLayout() {
        TerminalStyle style = TerminalStyle.plain();

        assertEquals("red[31m\nnext\tcolumn",
                style.safe("red\u001B[31m\nnext\u0000\tcolumn\u009B"));
        assertEquals("普通文本😀", style.safe("普通文本😀"));
        assertEquals("null", style.safe(null));
    }
}
