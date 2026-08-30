package com.yoda.codingagent.core.context;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.tool.ToolDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ContextManager {

    private final TokenEstimator estimator;

    public ContextManager(TokenEstimator estimator) {
        this.estimator = Objects.requireNonNull(estimator, "estimator");
    }

    public ContextSnapshot buildSnapshot(CanonicalHistory history,
                                         WorkspaceId workspaceId,
                                         Path workspaceRoot,
                                         List<Message> currentTurn,
                                         List<ToolDefinition> tools,
                                         ContextBudgetPolicy policy) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
        List<Message> current = validateCurrentTurn(currentTurn);
        List<ToolDefinition> toolDefinitions = List.copyOf(
                Objects.requireNonNull(tools, "tools"));
        Objects.requireNonNull(policy, "policy");
        if (!history.workspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("history and workspace do not match");
        }

        Message.SystemMessage fixedMessage = new Message.SystemMessage(
                history.systemMessage().content()
                        + "\nCurrent workspace root: " + root);
        int fixedTokens = estimator.estimateMessages(List.of(fixedMessage));
        int toolTokens = estimator.estimateTools(toolDefinitions);
        int currentTokens = estimator.estimateMessages(current);
        long required = (long) fixedTokens + toolTokens + currentTokens
                + policy.reservedOutputTokens();
        if (required > policy.maxInputTokens()) {
            throw new AgentException(ErrorCode.CONTEXT_LIMIT,
                    "fixed context, tools, current turn and output reserve exceed the token budget");
        }

        List<CanonicalHistory.TurnHistory> selectedNewestFirst = new ArrayList<>();
        int recentTokens = 0;
        List<CanonicalHistory.TurnHistory> completed = history.completedTurns();
        for (int index = completed.size() - 1;
                index >= 0 && selectedNewestFirst.size() < policy.recentFullTurns(); index--) {
            CanonicalHistory.TurnHistory candidate = completed.get(index);
            int candidateTokens = estimator.estimateMessages(candidate.messages());
            if (required + recentTokens + candidateTokens > policy.maxInputTokens()) {
                break;
            }
            selectedNewestFirst.add(candidate);
            recentTokens += candidateTokens;
        }
        Collections.reverse(selectedNewestFirst);

        Set<TurnId> fullTurnIds = new HashSet<>();
        selectedNewestFirst.forEach(turn -> fullTurnIds.add(turn.turnId()));
        List<Message.TurnDigestMessage> selectedDigestsNewestFirst = new ArrayList<>();
        int digestTokens = 0;
        List<TurnDigest> availableDigests = history.orderedDigests();
        for (int index = availableDigests.size() - 1; index >= 0; index--) {
            if (selectedDigestsNewestFirst.size() >= 32) {
                break;
            }
            TurnDigest digest = availableDigests.get(index);
            if (fullTurnIds.contains(digest.turnId())) {
                continue;
            }
            Message.TurnDigestMessage digestMessage = new Message.TurnDigestMessage(
                    digest.turnId(), digest.toContextText());
            int candidateTokens = estimator.estimateMessages(List.of(digestMessage));
            if (required + recentTokens + digestTokens + candidateTokens
                    > policy.maxInputTokens()) {
                break;
            }
            selectedDigestsNewestFirst.add(digestMessage);
            digestTokens += candidateTokens;
        }
        Collections.reverse(selectedDigestsNewestFirst);

        List<Message> snapshotMessages = new ArrayList<>();
        snapshotMessages.add(fixedMessage);
        snapshotMessages.addAll(selectedDigestsNewestFirst);
        for (CanonicalHistory.TurnHistory turn : selectedNewestFirst) {
            snapshotMessages.addAll(turn.messages());
        }
        snapshotMessages.addAll(current);
        ContextSnapshot.Budget budget = new ContextSnapshot.Budget(
                fixedTokens, toolTokens, currentTokens, recentTokens, digestTokens,
                policy.reservedOutputTokens(), policy.maxInputTokens());
        List<TurnId> selectedFullTurnIds = selectedNewestFirst.stream()
                .map(CanonicalHistory.TurnHistory::turnId).toList();
        List<TurnId> selectedDigestTurnIds = selectedDigestsNewestFirst.stream()
                .map(Message.TurnDigestMessage::turnId).toList();
        int representedTurns = Math.addExact(
                selectedFullTurnIds.size(), selectedDigestTurnIds.size());
        int omittedTurnCount = Math.max(0,
                history.totalCompletedTurnCount() - representedTurns);
        int selectedWithReserve = budget.totalWithReserve();
        int loadedHistoryTokens = estimator.estimateMessages(
                history.messages().subList(1, history.messages().size()));
        int estimatedBefore = Math.max(selectedWithReserve,
                Math.toIntExact(Math.min(Integer.MAX_VALUE,
                        required + loadedHistoryTokens)));
        ContextSnapshot.CompactionDecision decision =
                new ContextSnapshot.CompactionDecision(selectedFullTurnIds,
                        selectedDigestTurnIds, omittedTurnCount,
                        estimatedBefore, selectedWithReserve);
        return new ContextSnapshot(snapshotMessages, budget, decision);
    }

    private static List<Message> validateCurrentTurn(List<Message> currentTurn) {
        List<Message> messages = List.copyOf(Objects.requireNonNull(currentTurn, "currentTurn"));
        if (messages.isEmpty() || !(messages.getFirst() instanceof Message.UserMessage user)) {
            throw new IllegalArgumentException("current turn must start with a user message");
        }
        TurnId turnId = user.turnId();
        Set<String> outstanding = new HashSet<>();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (!turnId.equals(turnIdOf(message))) {
                throw new IllegalArgumentException("current turn contains another turn id");
            }
            if (index > 0 && (message instanceof Message.UserMessage
                    || message instanceof Message.AssistantMessage)) {
                throw new IllegalArgumentException(
                        "current turn may contain only one user message and completed tool groups");
            }
            if (message instanceof Message.AssistantToolCallsMessage calls) {
                if (!outstanding.isEmpty()) {
                    throw new IllegalArgumentException("nested tool call groups are invalid");
                }
                calls.toolCalls().forEach(call -> {
                    if (!outstanding.add(call.callId())) {
                        throw new IllegalArgumentException("duplicate tool call id");
                    }
                });
            } else if (message instanceof Message.ToolResultMessage result) {
                if (!outstanding.remove(result.callId())) {
                    throw new IllegalArgumentException("orphan tool result");
                }
            } else if (!outstanding.isEmpty()) {
                throw new IllegalArgumentException("tool call group is incomplete");
            }
        }
        if (!outstanding.isEmpty()) {
            throw new IllegalArgumentException("tool call group is incomplete");
        }
        return messages;
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
        throw new IllegalArgumentException("current turn cannot contain a system message");
    }
}
