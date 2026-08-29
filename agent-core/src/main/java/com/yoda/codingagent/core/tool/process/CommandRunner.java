package com.yoda.codingagent.core.tool.process;

import com.yoda.codingagent.core.api.CancellationToken;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class CommandRunner implements CommandExecutor {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);
    private static final Duration TERMINATION_GRACE = Duration.ofMillis(300);

    @Override
    public CommandResult run(List<String> argv, Path cwd, Duration timeout,
                             int maximumBytesPerStream, CancellationToken cancellationToken) {
        Objects.requireNonNull(argv, "argv");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        if (argv.isEmpty() || timeout.isZero() || timeout.isNegative()
                || maximumBytesPerStream < 1) {
            throw new IllegalArgumentException("invalid command runner arguments");
        }
        Instant startedAt = Instant.now();
        final Process process;
        try {
            process = new ProcessBuilder(argv).directory(cwd.toFile()).start();
        } catch (IOException exception) {
            return new CommandResult(null, "", "", elapsed(startedAt), false, false,
                    false, safeStartError(exception));
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CapturedStream> stdout = executor.submit(
                    () -> drain(process.getInputStream(), maximumBytesPerStream));
            Future<CapturedStream> stderr = executor.submit(
                    () -> drain(process.getErrorStream(), maximumBytesPerStream));
            Instant deadline = startedAt.plus(timeout);
            boolean timedOut = false;
            boolean cancelled = false;
            while (process.isAlive()) {
                if (cancellationToken.isCancelled()) {
                    cancelled = true;
                    terminateTree(process);
                    break;
                }
                if (!Instant.now().isBefore(deadline)) {
                    timedOut = true;
                    terminateTree(process);
                    break;
                }
                try {
                    process.waitFor(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                    terminateTree(process);
                    break;
                }
            }
            if (process.isAlive()) {
                terminateTree(process);
            }
            CapturedStream capturedOut = await(stdout);
            CapturedStream capturedErr = await(stderr);
            Integer exitCode = process.isAlive() ? null : process.exitValue();
            return new CommandResult(exitCode, capturedOut.text(), capturedErr.text(),
                    elapsed(startedAt), timedOut, cancelled,
                    capturedOut.truncated() || capturedErr.truncated(), null);
        }
    }

    private static CapturedStream drain(InputStream input, int maximumBytes) throws IOException {
        byte[] buffer = new byte[8_192];
        byte[] retained = new byte[maximumBytes];
        int retainedCount = 0;
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            int copy = Math.min(read, maximumBytes - retainedCount);
            if (copy > 0) {
                System.arraycopy(buffer, 0, retained, retainedCount, copy);
                retainedCount += copy;
            }
            if (copy < read) {
                truncated = true;
            }
        }
        return new CapturedStream(new String(retained, 0, retainedCount, StandardCharsets.UTF_8),
                truncated);
    }

    private static CapturedStream await(Future<CapturedStream> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CapturedStream("", true);
        } catch (ExecutionException | java.util.concurrent.TimeoutException exception) {
            future.cancel(true);
            return new CapturedStream("", true);
        }
    }

    private static void terminateTree(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        descendants.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        waitBriefly(process);
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
            waitBriefly(process);
        }
    }

    private static void waitBriefly(Process process) {
        try {
            process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Duration elapsed(Instant startedAt) {
        Duration duration = Duration.between(startedAt, Instant.now());
        if (duration.isNegative()) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(duration.toMillis());
    }

    private static String safeStartError(IOException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "process could not be started" : message;
    }

    private record CapturedStream(String text, boolean truncated) { }
}
