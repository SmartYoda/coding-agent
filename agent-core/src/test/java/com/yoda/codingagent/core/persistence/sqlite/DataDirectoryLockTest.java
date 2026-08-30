package com.yoda.codingagent.core.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yoda.codingagent.core.error.AgentException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataDirectoryLockTest {

    @Test
    void rejectsClosedOrMismatchedLockBeforeCreatingDatabase(@TempDir Path tempDirectory) {
        Path lockedDirectory = tempDirectory.resolve("locked");
        DataDirectoryLock lock = DataDirectoryLock.acquire(lockedDirectory);
        Path mismatchedDatabase = tempDirectory.resolve("other").resolve("agent.db");

        AgentException missing = assertThrows(AgentException.class,
                () -> SqliteStateStore.open(null, mismatchedDatabase,
                        Duration.ofSeconds(1)));
        assertEquals(com.yoda.codingagent.core.api.ErrorCode.STORAGE_ERROR,
                missing.errorCode());
        assertFalse(Files.exists(mismatchedDatabase));

        assertThrows(AgentException.class, () -> SqliteStateStore.open(
                lock, mismatchedDatabase, Duration.ofSeconds(1)));
        assertFalse(Files.exists(mismatchedDatabase));

        lock.close();
        Path database = lockedDirectory.resolve("agent.db");
        assertThrows(AgentException.class, () -> SqliteStateStore.open(
                lock, database, Duration.ofSeconds(1)));
        assertFalse(Files.exists(database));
    }

    @Test
    void rejectsOverlappingLockAndAllowsReacquireAfterClose(@TempDir Path tempDirectory) {
        Path dataDirectory = tempDirectory.resolve("state");
        DataDirectoryLock first = DataDirectoryLock.acquire(dataDirectory);

        assertThrows(AgentException.class,
                () -> DataDirectoryLock.acquire(dataDirectory));

        first.close();
        try (DataDirectoryLock reacquired = DataDirectoryLock.acquire(dataDirectory)) {
            assertTrue(reacquired.isHeld());
        }
    }

    @Test
    void secondJvmCannotOpenUntilFirstJvmReleasesLock(@TempDir Path tempDirectory)
            throws Exception {
        Path dataDirectory = tempDirectory.resolve("state");
        Process first = startProbe(dataDirectory, true);
        assertEquals("READY", awaitMarker(first));
        Path database = dataDirectory.resolve("agent.db");
        long sizeBefore = Files.size(database);
        long modifiedBefore = Files.getLastModifiedTime(database).toMillis();

        Process second = startProbe(dataDirectory, false);
        assertEquals("DENIED", awaitMarker(second));
        assertTrue(second.waitFor(10, TimeUnit.SECONDS));
        assertEquals(3, second.exitValue());
        assertEquals(sizeBefore, Files.size(database));
        assertEquals(modifiedBefore, Files.getLastModifiedTime(database).toMillis());

        try (OutputStreamWriter writer = new OutputStreamWriter(
                first.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write("release\n");
            writer.flush();
        }
        assertTrue(first.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, first.exitValue());

        Process third = startProbe(dataDirectory, false);
        assertEquals("READY", awaitMarker(third));
        assertTrue(third.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, third.exitValue());

        Process forciblyStopped = startProbe(dataDirectory, true);
        assertEquals("READY", awaitMarker(forciblyStopped));
        forciblyStopped.destroyForcibly();
        assertTrue(forciblyStopped.waitFor(10, TimeUnit.SECONDS));
        Process afterForcedStop = startProbe(dataDirectory, false);
        assertEquals("READY", awaitMarker(afterForcedStop));
        assertTrue(afterForcedStop.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, afterForcedStop.exitValue());
    }

    private static Process startProbe(Path dataDirectory, boolean hold) throws IOException {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java")
                .toString();
        ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-cp",
                System.getProperty("java.class.path"), DataDirectoryLockProbe.class.getName(),
                dataDirectory.toString(), hold ? "hold" : "once");
        return builder.redirectErrorStream(true).start();
    }

    private static String awaitMarker(Process process) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line == null || line.equals("READY") || line.equals("DENIED")) {
                    return line;
                }
            } else if (!process.isAlive()) {
                break;
            } else {
                Thread.sleep(10);
            }
        }
        process.destroyForcibly();
        throw new AssertionError("lock probe did not report readiness");
    }
}
