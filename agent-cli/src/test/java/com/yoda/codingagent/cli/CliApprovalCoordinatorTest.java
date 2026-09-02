package com.yoda.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.CommandApprovalDecision;
import com.yoda.codingagent.core.api.CommandApprovalRequest;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceId;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CliApprovalCoordinatorTest {

    @Test
    void resolvesOnlyTheMatchingPendingApproval() throws Exception {
        CliApprovalCoordinator coordinator = new CliApprovalCoordinator();
        CompletableFuture<CommandApprovalDecision> result = CompletableFuture.supplyAsync(
                () -> coordinator.requestApproval(request("call-1", 10),
                        CancellationToken.NONE));
        awaitPending(coordinator, "call-1");

        assertFalse(coordinator.resolve("wrong", CommandApprovalDecision.APPROVED));
        assertTrue(coordinator.resolve("call-1", CommandApprovalDecision.APPROVED));
        assertEquals(CommandApprovalDecision.APPROVED, result.get(2, TimeUnit.SECONDS));
        assertEquals(null, coordinator.pendingApprovalId());
    }

    @Test
    void cancellationAndDeadlineEndApprovalWait() throws Exception {
        CliApprovalCoordinator coordinator = new CliApprovalCoordinator();
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<CommandApprovalDecision> cancellation =
                CompletableFuture.supplyAsync(() -> coordinator.requestApproval(
                        request("cancel", 10), cancelled::get));
        awaitPending(coordinator, "cancel");
        cancelled.set(true);
        assertEquals(CommandApprovalDecision.CANCELLED,
                cancellation.get(2, TimeUnit.SECONDS));

        assertEquals(CommandApprovalDecision.TIMED_OUT,
                coordinator.requestApproval(request("timeout", -1), CancellationToken.NONE));
    }

    private static CommandApprovalRequest request(String id, long deadlineSeconds) {
        return new CommandApprovalRequest(id, WorkspaceId.random(), TurnId.random(), id,
                List.of("curl", "https://example.com"), Path.of("/tmp"),
                Instant.now().plusSeconds(deadlineSeconds));
    }

    private static void awaitPending(CliApprovalCoordinator coordinator, String id)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!id.equals(coordinator.pendingApprovalId()) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(id, coordinator.pendingApprovalId());
    }
}
