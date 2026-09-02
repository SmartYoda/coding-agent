package com.yoda.codingagent.cli;

import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.CommandApprovalDecision;
import com.yoda.codingagent.core.api.CommandApprovalGateway;
import com.yoda.codingagent.core.api.CommandApprovalRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CliApprovalCoordinator implements CommandApprovalGateway {

    private Pending pending;

    @Override
    public CommandApprovalDecision requestApproval(CommandApprovalRequest request,
                                                     CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        Pending created = new Pending(request, new CompletableFuture<>());
        synchronized (this) {
            if (pending != null) {
                return CommandApprovalDecision.DENIED;
            }
            pending = created;
        }
        try {
            while (true) {
                if (cancellationToken.isCancelled()) {
                    return CommandApprovalDecision.CANCELLED;
                }
                Duration remaining = Duration.between(Instant.now(), request.deadline());
                if (remaining.isZero() || remaining.isNegative()) {
                    return CommandApprovalDecision.TIMED_OUT;
                }
                try {
                    long waitMillis = Math.max(1, Math.min(100, remaining.toMillis()));
                    return created.decision().get(waitMillis, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Recheck cancellation and the turn deadline.
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return CommandApprovalDecision.CANCELLED;
                } catch (ExecutionException exception) {
                    return CommandApprovalDecision.DENIED;
                }
            }
        } finally {
            synchronized (this) {
                if (pending == created) {
                    pending = null;
                }
            }
        }
    }

    synchronized boolean resolve(String approvalId, CommandApprovalDecision decision) {
        Objects.requireNonNull(approvalId, "approvalId");
        if (decision != CommandApprovalDecision.APPROVED
                && decision != CommandApprovalDecision.DENIED) {
            throw new IllegalArgumentException("interactive resolution must approve or deny");
        }
        if (pending == null
                || !pending.request().approvalId().equals(approvalId)) {
            return false;
        }
        return pending.decision().complete(decision);
    }

    synchronized String pendingApprovalId() {
        return pending == null ? null : pending.request().approvalId();
    }

    private record Pending(CommandApprovalRequest request,
                           CompletableFuture<CommandApprovalDecision> decision) { }
}
