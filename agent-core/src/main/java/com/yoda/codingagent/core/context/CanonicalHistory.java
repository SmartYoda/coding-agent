package com.yoda.codingagent.core.context;

import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.tool.ToolCall;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CanonicalHistory {

    private final SessionId sessionId;
    private final WorkspaceId workspaceId;
    private final Message.SystemMessage systemMessage;
    private final List<TurnHistory> completedTurns;
    private final List<Message> messages;
    private final Map<TurnId, TurnDigest> digests;
    private final List<TurnDigest> orderedDigests;

    public CanonicalHistory(SessionId sessionId, WorkspaceId workspaceId,
                            List<Message> messages) {
        this(sessionId, workspaceId, messages, Map.of());
    }

    public CanonicalHistory(SessionId sessionId, WorkspaceId workspaceId,
                            List<Message> messages, Map<TurnId, TurnDigest> digests) {
        this(sessionId, workspaceId, messages, validateDigestMap(digests));
    }

    public CanonicalHistory(SessionId sessionId, WorkspaceId workspaceId,
                            List<Message> messages, List<TurnDigest> orderedDigests) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (this.messages.isEmpty()
                || !(this.messages.getFirst() instanceof Message.SystemMessage system)) {
            throw new IllegalArgumentException("history must start with one system message");
        }
        this.systemMessage = system;
        this.completedTurns = groupAndValidate(this.messages);
        this.orderedDigests = validateDigests(orderedDigests);
        Map<TurnId, TurnDigest> indexed = new LinkedHashMap<>();
        this.orderedDigests.forEach(digest -> indexed.put(digest.turnId(), digest));
        this.digests = Collections.unmodifiableMap(indexed);
    }

    public SessionId sessionId() {
        return sessionId;
    }

    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    public Message.SystemMessage systemMessage() {
        return systemMessage;
    }

    public List<TurnHistory> completedTurns() {
        return completedTurns;
    }

    public List<Message> messages() {
        return messages;
    }

    public Map<TurnId, TurnDigest> digests() {
        return digests;
    }

    public List<TurnDigest> orderedDigests() {
        return orderedDigests;
    }

    private static List<TurnDigest> validateDigests(List<TurnDigest> digests) {
        List<TurnDigest> copied = List.copyOf(Objects.requireNonNull(digests, "digests"));
        Set<TurnId> seen = new HashSet<>();
        for (TurnDigest digest : copied) {
            if (digest.status() != TurnStatus.COMPLETED
                    || !seen.add(digest.turnId())) {
                throw new IllegalArgumentException(
                        "canonical digest must uniquely identify a completed turn");
            }
        }
        return copied;
    }

    private static List<TurnDigest> validateDigestMap(Map<TurnId, TurnDigest> digests) {
        Map<TurnId, TurnDigest> copied = new LinkedHashMap<>(
                Objects.requireNonNull(digests, "digests"));
        copied.forEach((turnId, digest) -> {
            if (digest == null || !turnId.equals(digest.turnId())) {
                throw new IllegalArgumentException("digest key and turn id must match");
            }
        });
        return List.copyOf(copied.values());
    }

    private static List<TurnHistory> groupAndValidate(List<Message> messages) {
        Map<TurnId, List<Message>> grouped = new LinkedHashMap<>();
        TurnId currentTurn = null;
        Set<TurnId> closedGroups = new HashSet<>();
        for (int index = 1; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message instanceof Message.SystemMessage) {
                throw new IllegalArgumentException("history contains more than one system message");
            }
            TurnId turnId = turnIdOf(message);
            if (!turnId.equals(currentTurn)) {
                if (currentTurn != null) {
                    closedGroups.add(currentTurn);
                }
                if (closedGroups.contains(turnId)) {
                    throw new IllegalArgumentException("turn messages must be contiguous");
                }
                currentTurn = turnId;
            }
            grouped.computeIfAbsent(turnId, ignored -> new ArrayList<>()).add(message);
        }

        List<TurnHistory> turns = new ArrayList<>();
        for (Map.Entry<TurnId, List<Message>> entry : grouped.entrySet()) {
            List<Message> turnMessages = List.copyOf(entry.getValue());
            validateCompletedTurn(entry.getKey(), turnMessages);
            turns.add(new TurnHistory(entry.getKey(), turnMessages));
        }
        return List.copyOf(turns);
    }

    private static void validateCompletedTurn(TurnId turnId, List<Message> messages) {
        if (messages.size() < 2
                || !(messages.getFirst() instanceof Message.UserMessage)
                || !(messages.getLast() instanceof Message.AssistantMessage)) {
            throw new IllegalArgumentException(
                    "a completed turn must start with user and end with assistant text");
        }
        int index = 1;
        while (index < messages.size() - 1) {
            if (!(messages.get(index) instanceof Message.AssistantToolCallsMessage calls)) {
                throw new IllegalArgumentException("invalid message inside completed turn");
            }
            requireTurn(turnId, calls.turnId());
            Set<String> expectedCallIds = new HashSet<>();
            for (ToolCall call : calls.toolCalls()) {
                if (!expectedCallIds.add(call.callId())) {
                    throw new IllegalArgumentException("duplicate tool call id in model step");
                }
            }
            Set<String> resultCallIds = new HashSet<>();
            for (int resultIndex = 0; resultIndex < calls.toolCalls().size(); resultIndex++) {
                index++;
                if (index >= messages.size() - 1
                        || !(messages.get(index) instanceof Message.ToolResultMessage result)) {
                    throw new IllegalArgumentException("tool call group is incomplete");
                }
                requireTurn(turnId, result.turnId());
                if (!resultCallIds.add(result.callId())) {
                    throw new IllegalArgumentException("duplicate tool result call id");
                }
            }
            if (!expectedCallIds.equals(resultCallIds)) {
                throw new IllegalArgumentException("tool results do not match tool calls");
            }
            index++;
        }
        for (Message message : messages) {
            requireTurn(turnId, turnIdOf(message));
        }
    }

    private static TurnId turnIdOf(Message message) {
        if (message instanceof Message.UserMessage user) {
            return user.turnId();
        }
        if (message instanceof Message.AssistantMessage assistant) {
            return assistant.turnId();
        }
        if (message instanceof Message.AssistantToolCallsMessage calls) {
            return calls.turnId();
        }
        if (message instanceof Message.ToolResultMessage result) {
            return result.turnId();
        }
        throw new IllegalArgumentException("system message cannot belong to a turn");
    }

    private static void requireTurn(TurnId expected, TurnId actual) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("message belongs to the wrong turn");
        }
    }

    public record TurnHistory(TurnId turnId, List<Message> messages) {
        public TurnHistory {
            Objects.requireNonNull(turnId, "turnId");
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        }
    }
}
