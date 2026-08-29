package com.yoda.codingagent.core.safety;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.error.AgentException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

public final class WorkspaceGuard {

    public static final int MAX_PATH_CHARACTERS = 1_024;

    private final Path workspaceRoot;
    private final Path protectedDataDirectory;

    public WorkspaceGuard(Path workspaceRoot, Path protectedDataDirectory) {
        this.workspaceRoot = realDirectory(workspaceRoot, "workspace root");
        this.protectedDataDirectory = canonicalOrAbsolute(
                Objects.requireNonNull(protectedDataDirectory, "protectedDataDirectory"));
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public Path resolveExistingFile(String relativePath) {
        Path candidate = resolveRelative(relativePath, false);
        try {
            Path real = candidate.toRealPath();
            ensureAuthorized(real);
            if (!Files.isRegularFile(real)) {
                throw pathError("path is not a regular file");
            }
            return real;
        } catch (IOException exception) {
            throw fileError("file does not exist or cannot be read", exception);
        }
    }

    public Path resolveExistingDirectory(String relativePath) {
        Path candidate = resolveRelative(relativePath, true);
        try {
            Path real = candidate.toRealPath();
            ensureAuthorized(real);
            if (!Files.isDirectory(real)) {
                throw pathError("path is not a directory");
            }
            return real;
        } catch (IOException exception) {
            throw fileError("directory does not exist or cannot be read", exception);
        }
    }

    public Path resolveCommandDirectory(String relativePath) {
        return resolveExistingDirectory(relativePath);
    }

    public Path resolveCreateOrReplaceTarget(String relativePath) {
        Path target = resolveRelative(relativePath, false);
        if (target.equals(workspaceRoot)) {
            throw pathError("target must be a file below the workspace root");
        }
        ensureAuthorized(target);
        validateNearestExistingParent(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Path real = target.toRealPath();
                ensureAuthorized(real);
                if (!Files.isRegularFile(real)) {
                    throw pathError("target is not a regular file");
                }
            } catch (IOException exception) {
                throw fileError("target cannot be resolved", exception);
            }
        }
        return target;
    }

    public Path revalidateWriteTarget(Path target) {
        Objects.requireNonNull(target, "target");
        Path normalized = target.toAbsolutePath().normalize();
        ensureAuthorized(normalized);
        validateNearestExistingParent(normalized);
        return normalized;
    }

    public boolean isProtectedPath(Path candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return candidate.toAbsolutePath().normalize().startsWith(protectedDataDirectory);
    }

    private Path resolveRelative(String value, boolean allowRoot) {
        if (value == null || value.isBlank() || value.length() > MAX_PATH_CHARACTERS
                || value.indexOf('\0') >= 0) {
            throw pathError("path must be a non-blank relative path of at most 1024 characters");
        }
        final Path relative;
        try {
            relative = Path.of(value);
        } catch (InvalidPathException exception) {
            throw pathError("path is invalid", exception);
        }
        if (relative.isAbsolute()) {
            throw pathError("absolute paths are not allowed");
        }
        for (Path component : relative) {
            if (component.toString().equals("..")) {
                throw pathError("parent path traversal is not allowed");
            }
        }
        Path resolved = workspaceRoot.resolve(relative).normalize();
        if (!resolved.startsWith(workspaceRoot) || (!allowRoot && resolved.equals(workspaceRoot))) {
            throw pathError("path is outside the workspace");
        }
        ensureAuthorized(resolved);
        return resolved;
    }

    private void validateNearestExistingParent(Path target) {
        Path parent = target.getParent();
        while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            parent = parent.getParent();
        }
        if (parent == null) {
            throw pathError("target has no existing parent");
        }
        try {
            Path realParent = parent.toRealPath();
            ensureAuthorized(realParent);
            if (!Files.isDirectory(realParent)) {
                throw pathError("target parent is not a directory");
            }
        } catch (IOException exception) {
            throw fileError("target parent cannot be resolved", exception);
        }
    }

    private void ensureAuthorized(Path candidate) {
        Path absolute = candidate.toAbsolutePath().normalize();
        if (!absolute.startsWith(workspaceRoot)
                || absolute.startsWith(protectedDataDirectory)) {
            throw pathError("path is outside the authorized workspace");
        }
    }

    private static Path realDirectory(Path path, String name) {
        Objects.requireNonNull(path, name);
        try {
            Path real = path.toRealPath();
            if (!Files.isDirectory(real) || !Files.isReadable(real)) {
                throw new IllegalArgumentException(name + " must be a readable directory");
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException(name + " must be an existing directory", exception);
        }
    }

    private static Path canonicalOrAbsolute(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static AgentException pathError(String message) {
        return new AgentException(ErrorCode.PATH_OUTSIDE_WORKSPACE, message);
    }

    private static AgentException pathError(String message, Throwable cause) {
        return new AgentException(ErrorCode.PATH_OUTSIDE_WORKSPACE, message, cause);
    }

    private static AgentException fileError(String message, Throwable cause) {
        return new AgentException(ErrorCode.FILE_IO_ERROR, message, cause);
    }
}
