package com.yoda.codingagent.core.persistence.sqlite;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/** Separate-JVM helper for the process ownership integration test. */
public final class DataDirectoryLockProbe {

    private DataDirectoryLockProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path dataDirectory = Path.of(args[0]);
        boolean hold = args.length > 1 && args[1].equals("hold");
        try (DataDirectoryLock lock = DataDirectoryLock.acquire(dataDirectory)) {
            SqliteStateStore.open(lock, dataDirectory.resolve("agent.db"),
                    Duration.ofSeconds(2));
            System.out.println("READY");
            System.out.flush();
            if (hold) {
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                        .readLine();
            }
        } catch (RuntimeException exception) {
            System.out.println("DENIED");
            System.out.flush();
            System.exit(3);
        }
    }
}
