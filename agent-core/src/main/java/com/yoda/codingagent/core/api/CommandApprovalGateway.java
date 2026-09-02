package com.yoda.codingagent.core.api;

import java.util.Objects;

@FunctionalInterface
public interface CommandApprovalGateway {

    CommandApprovalDecision requestApproval(CommandApprovalRequest request,
                                              CancellationToken cancellationToken);

    static CommandApprovalGateway denyAll() {
        return (request, cancellationToken) -> {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(cancellationToken, "cancellationToken");
            return cancellationToken.isCancelled()
                    ? CommandApprovalDecision.CANCELLED
                    : CommandApprovalDecision.DENIED;
        };
    }
}
