package com.yoda.codingagent.core.workspace;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.error.AgentException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class WorkspaceResolver {

    private final Path protectedDataDirectory;

    public WorkspaceResolver(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.protectedDataDirectory = canonicalizeDataDirectory(dataDirectory);
    }

    public Path resolveForRegistration(Path root) {
        Objects.requireNonNull(root, "root");
        try {
            Path canonicalRoot = root.toRealPath();
            validateDirectory(canonicalRoot);
            validateOutsideProtectedData(canonicalRoot);
            return canonicalRoot;
        } catch (IOException exception) {
            throw new AgentException(ErrorCode.INVALID_REQUEST,
                    "workspace root must be an existing readable directory", exception);
        }
    }

    public boolean isAvailable(Path storedRoot) {
        Objects.requireNonNull(storedRoot, "storedRoot");
        try {
            Path normalizedStoredRoot = storedRoot.toAbsolutePath().normalize();
            Path canonicalRoot = storedRoot.toRealPath();
            validateDirectory(canonicalRoot);
            validateOutsideProtectedData(canonicalRoot);
            return canonicalRoot.equals(normalizedStoredRoot);
        } catch (IOException | AgentException exception) {
            return false;
        }
    }

    private void validateDirectory(Path root) {
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            throw new AgentException(ErrorCode.INVALID_REQUEST,
                    "workspace root must be an existing readable directory");
        }
    }

    private void validateOutsideProtectedData(Path root) {
        if (root.startsWith(protectedDataDirectory)) {
            throw new AgentException(ErrorCode.INVALID_REQUEST,
                    "workspace root must not be inside the agent data directory");
        }
    }

    private static Path canonicalizeDataDirectory(Path dataDirectory) {
        Path absolute = dataDirectory.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException ignored) {
            return absolute;
        }
    }
}
