package com.yoda.codingagent.cli;

import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.CommandAccessMode;
import com.yoda.codingagent.core.api.ThinkingMode;
import com.yoda.codingagent.core.api.WorkspaceId;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

sealed interface CliCommand permits CliCommand.Prompt, CliCommand.Help, CliCommand.Exit,
        CliCommand.Cancel, CliCommand.Context, CliCommand.WorkspaceList,
        CliCommand.WorkspaceAdd, CliCommand.WorkspaceUse, CliCommand.WorkspaceArchive,
        CliCommand.SessionList, CliCommand.SessionNew, CliCommand.SessionUse,
        CliCommand.SessionClose, CliCommand.ThinkingShow, CliCommand.ThinkingSet,
        CliCommand.AccessShow, CliCommand.AccessSet, CliCommand.Approve, CliCommand.Deny {

    static CliCommand parse(String input) {
        if (input == null) {
            return new Exit();
        }
        String line = input.trim();
        if (line.isEmpty()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        if (!line.startsWith("/")) {
            return new Prompt(input);
        }
        String[] parts = line.split("\\s+", 4);
        String root = parts[0].toLowerCase(Locale.ROOT);
        return switch (root) {
            case "/help" -> requireArity(parts, 1, new Help());
            case "/exit" -> requireArity(parts, 1, new Exit());
            case "/cancel" -> requireArity(parts, 1, new Cancel());
            case "/context" -> requireArity(parts, 1, new Context());
            case "/thinking" -> parseThinking(parts);
            case "/access" -> parseAccess(parts);
            case "/approve" -> parseApproval(parts, true);
            case "/deny" -> parseApproval(parts, false);
            case "/workspace" -> parseWorkspace(parts);
            case "/session" -> parseSession(parts);
            default -> throw new IllegalArgumentException("unknown command: " + parts[0]);
        };
    }

    private static CliCommand parseAccess(String[] parts) {
        if (parts.length == 1) {
            return new AccessShow();
        }
        if (parts.length != 2) {
            throw new IllegalArgumentException("usage: /access [restricted|ask|full]");
        }
        CommandAccessMode mode = switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "restricted" -> CommandAccessMode.RESTRICTED;
            case "ask" -> CommandAccessMode.ASK;
            case "full" -> CommandAccessMode.FULL_ACCESS;
            default -> throw new IllegalArgumentException(
                    "usage: /access [restricted|ask|full]");
        };
        return new AccessSet(mode);
    }

    private static CliCommand parseApproval(String[] parts, boolean approve) {
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "usage: /" + (approve ? "approve" : "deny") + " <approval-id>");
        }
        return approve ? new Approve(parts[1]) : new Deny(parts[1]);
    }

    private static CliCommand parseThinking(String[] parts) {
        if (parts.length == 1) {
            return new ThinkingShow();
        }
        if (parts.length != 2) {
            throw new IllegalArgumentException("usage: /thinking [on|off|default]");
        }
        ThinkingMode mode = switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "on" -> ThinkingMode.ENABLED;
            case "off" -> ThinkingMode.DISABLED;
            case "default" -> ThinkingMode.DEFAULT;
            default -> throw new IllegalArgumentException(
                    "usage: /thinking [on|off|default]");
        };
        return new ThinkingSet(mode);
    }

    private static CliCommand parseWorkspace(String[] parts) {
        if (parts.length < 2) {
            throw new IllegalArgumentException("usage: /workspace list|add|use|archive");
        }
        return switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "list" -> requireArity(parts, 2, new WorkspaceList());
            case "add" -> {
                if (parts.length != 4) {
                    throw new IllegalArgumentException("usage: /workspace add <name> <path>");
                }
                yield new WorkspaceAdd(parts[2], Path.of(parts[3]));
            }
            case "use" -> {
                requireLength(parts, 3, "usage: /workspace use <workspace-id>");
                yield new WorkspaceUse(workspaceId(parts[2]));
            }
            case "archive" -> {
                requireLength(parts, 3, "usage: /workspace archive <workspace-id>");
                yield new WorkspaceArchive(workspaceId(parts[2]));
            }
            default -> throw new IllegalArgumentException("unknown workspace command");
        };
    }

    private static CliCommand parseSession(String[] parts) {
        if (parts.length < 2) {
            throw new IllegalArgumentException("usage: /session list|new|use|close");
        }
        return switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "list" -> requireArity(parts, 2, new SessionList());
            case "new" -> requireArity(parts, 2, new SessionNew());
            case "use" -> {
                requireLength(parts, 3, "usage: /session use <session-id>");
                yield new SessionUse(sessionId(parts[2]));
            }
            case "close" -> {
                if (parts.length > 3) {
                    throw new IllegalArgumentException("usage: /session close [session-id]");
                }
                yield new SessionClose(parts.length == 3 ? sessionId(parts[2]) : null);
            }
            default -> throw new IllegalArgumentException("unknown session command");
        };
    }

    private static <T extends CliCommand> T requireArity(String[] parts, int expected, T value) {
        requireLength(parts, expected, "command does not accept arguments");
        return value;
    }

    private static void requireLength(String[] parts, int expected, String message) {
        if (parts.length != expected) {
            throw new IllegalArgumentException(message);
        }
    }

    private static WorkspaceId workspaceId(String value) {
        try {
            return new WorkspaceId(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid workspace id", exception);
        }
    }

    private static SessionId sessionId(String value) {
        try {
            return new SessionId(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid session id", exception);
        }
    }

    record Prompt(String text) implements CliCommand { }
    record Help() implements CliCommand { }
    record Exit() implements CliCommand { }
    record Cancel() implements CliCommand { }
    record Context() implements CliCommand { }
    record ThinkingShow() implements CliCommand { }
    record ThinkingSet(ThinkingMode mode) implements CliCommand {
        public ThinkingSet {
            java.util.Objects.requireNonNull(mode, "mode");
        }
    }
    record AccessShow() implements CliCommand { }
    record AccessSet(CommandAccessMode mode) implements CliCommand {
        public AccessSet {
            java.util.Objects.requireNonNull(mode, "mode");
        }
    }
    record Approve(String approvalId) implements CliCommand { }
    record Deny(String approvalId) implements CliCommand { }
    record WorkspaceList() implements CliCommand { }
    record WorkspaceAdd(String name, Path path) implements CliCommand { }
    record WorkspaceUse(WorkspaceId workspaceId) implements CliCommand { }
    record WorkspaceArchive(WorkspaceId workspaceId) implements CliCommand { }
    record SessionList() implements CliCommand { }
    record SessionNew() implements CliCommand { }
    record SessionUse(SessionId sessionId) implements CliCommand { }
    record SessionClose(SessionId sessionId) implements CliCommand { }
}
