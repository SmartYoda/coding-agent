package com.yoda.codingagent.cli;

import com.yoda.codingagent.core.api.AgentService;
import com.yoda.codingagent.core.persistence.StateStore;
import com.yoda.codingagent.core.persistence.sqlite.DataDirectoryLock;
import java.util.Objects;

final class CliRuntime implements AutoCloseable {

    private final AgentService service;
    private final StateStore.RecoverySummary startupRecoverySummary;
    private final DataDirectoryLock dataDirectoryLock;

    CliRuntime(AgentService service, StateStore.RecoverySummary startupRecoverySummary,
               DataDirectoryLock dataDirectoryLock) {
        this.service = Objects.requireNonNull(service, "service");
        this.startupRecoverySummary = Objects.requireNonNull(
                startupRecoverySummary, "startupRecoverySummary");
        this.dataDirectoryLock = Objects.requireNonNull(dataDirectoryLock, "dataDirectoryLock");
    }

    AgentService service() {
        return service;
    }

    StateStore.RecoverySummary startupRecoverySummary() {
        return startupRecoverySummary;
    }

    @Override
    public void close() {
        dataDirectoryLock.close();
    }
}
