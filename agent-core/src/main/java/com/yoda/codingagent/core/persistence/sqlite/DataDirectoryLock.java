package com.yoda.codingagent.core.persistence.sqlite;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.error.AgentException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Owns one agent data directory for the lifetime of a local process runtime. */
public final class DataDirectoryLock implements AutoCloseable {

    private static final String LOCK_FILE_NAME = ".coding-agent.lock";

    private final Path dataDirectory;
    private final FileChannel channel;
    private FileLock fileLock;

    private DataDirectoryLock(Path dataDirectory, FileChannel channel, FileLock fileLock) {
        this.dataDirectory = dataDirectory;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    public static DataDirectoryLock acquire(Path requestedDataDirectory) {
        Objects.requireNonNull(requestedDataDirectory, "dataDirectory");
        FileChannel channel = null;
        try {
            Path normalized = requestedDataDirectory.toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            Path dataDirectory = normalized.toRealPath();
            channel = FileChannel.open(dataDirectory.resolve(LOCK_FILE_NAME),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock;
            try {
                fileLock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                fileLock = null;
            }
            if (fileLock == null) {
                closeQuietly(channel);
                throw unavailable(dataDirectory, null);
            }
            return new DataDirectoryLock(dataDirectory, channel, fileLock);
        } catch (IOException exception) {
            closeQuietly(channel);
            throw unavailable(requestedDataDirectory.toAbsolutePath().normalize(), exception);
        }
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public synchronized boolean isHeld() {
        return fileLock != null && fileLock.isValid() && channel.isOpen();
    }

    synchronized void requireHeldFor(Path requestedDatabasePath) {
        Path databasePath = Objects.requireNonNull(requestedDatabasePath, "databasePath")
                .toAbsolutePath().normalize();
        if (!isHeld()) {
            throw new AgentException(ErrorCode.STORAGE_ERROR,
                    "data directory lock is not held");
        }
        Path parent = databasePath.getParent();
        final Path canonicalParent;
        try {
            canonicalParent = parent == null ? null : parent.toRealPath();
        } catch (IOException exception) {
            throw new AgentException(ErrorCode.STORAGE_ERROR,
                    "database parent directory is not accessible", exception);
        }
        if (!dataDirectory.equals(canonicalParent)) {
            throw new AgentException(ErrorCode.STORAGE_ERROR,
                    "database path must be inside the locked data directory");
        }
    }

    @Override
    public synchronized void close() {
        FileLock held = fileLock;
        fileLock = null;
        try {
            if (held != null && held.isValid()) {
                held.release();
            }
        } catch (IOException ignored) {
            // Closing the channel below also releases the operating-system lock.
        } finally {
            closeQuietly(channel);
        }
    }

    private static AgentException unavailable(Path dataDirectory, Throwable cause) {
        String message = "data directory is already in use: " + dataDirectory;
        return cause == null
                ? new AgentException(ErrorCode.STORAGE_ERROR, message)
                : new AgentException(ErrorCode.STORAGE_ERROR, message, cause);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Preserve the authoritative acquisition/validation failure.
        }
    }
}
