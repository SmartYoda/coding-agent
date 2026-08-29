package com.yoda.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedCliSmokeIT {

    @Test
    void shadedJarShowsHelpAndMigratesWithoutNetwork(@TempDir Path temp) throws Exception {
        Path jar = Path.of("target", "coding-agent.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(jar), "shaded jar must exist before integration tests");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        Process help = new ProcessBuilder(java, "-jar", jar.toString(), "--help")
                .redirectErrorStream(true).start();
        assertTrue(help.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, help.exitValue(), new String(help.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));

        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = temp.resolve("state");
        ProcessBuilder builder = new ProcessBuilder(java, "-jar", jar.toString(),
                "--workspace", "main=" + workspace,
                "--data-dir", state.toString(),
                "--base-url", "http://127.0.0.1:9/v1");
        builder.redirectErrorStream(true);
        builder.environment().put("LLM_API_KEY", "dummy-offline-key");
        Process process = builder.start();
        process.getOutputStream().write("/exit\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(Files.isRegularFile(state.resolve("agent.db")), output);
    }
}
