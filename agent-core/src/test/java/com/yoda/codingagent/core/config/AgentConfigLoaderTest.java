package com.yoda.codingagent.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentConfigLoaderTest {

    private static final Path TEST_DATA_DIRECTORY =
            Path.of(System.getProperty("java.io.tmpdir"), "coding-agent-config-test");

    @Test
    void defaultsToQwenFlashAndNeverPrintsTheKey() {
        String key = "test-secret-key";
        AgentConfig config = new AgentConfigLoader().load(Map.of(),
                Map.of("DASHSCOPE_API_KEY", key,
                        "CODING_AGENT_DATA_DIR", TEST_DATA_DIRECTORY.toString()));

        assertEquals("qwen3.8-flash", config.model());
        assertEquals(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1"),
                config.baseUrl());
        assertFalse(config.toString().contains(key));
    }

    @Test
    void overrideWinsOverEnvironment() {
        AgentConfig config = new AgentConfigLoader().load(
                Map.of("model", "override-model", "apiKey", "override-key",
                        "dataDirectory", TEST_DATA_DIRECTORY.resolve("override").toString(),
                        "databaseBusyTimeout", "7500"),
                Map.of("LLM_MODEL", "environment-model", "LLM_API_KEY", "environment-key",
                        "CODING_AGENT_DATA_DIR", TEST_DATA_DIRECTORY.resolve("env").toString(),
                        "CODING_AGENT_DB_BUSY_TIMEOUT_MS", "2500"));

        assertEquals("override-model", config.model());
        assertEquals("override-key", config.apiKey());
        assertEquals(TEST_DATA_DIRECTORY.resolve("override").toAbsolutePath().normalize(),
                config.dataDirectory());
        assertEquals(config.dataDirectory().resolve("agent.db"), config.databasePath());
        assertEquals(Duration.ofMillis(7500), config.databaseBusyTimeout());
    }

    @Test
    void rejectsNonLoopbackHttp() {
        assertThrows(IllegalArgumentException.class, () -> new AgentConfig(
                URI.create("http://example.com/v1"), "secret", "qwen3.8-flash",
                Duration.ofSeconds(30), 1024, 4096, false, TEST_DATA_DIRECTORY,
                Duration.ofSeconds(5)));
    }

    @Test
    void rejectsDatabaseBusyTimeoutOutsideSupportedRange() {
        AgentConfigLoader loader = new AgentConfigLoader();
        Map<String, String> environment = Map.of(
                "LLM_API_KEY", "secret",
                "CODING_AGENT_DATA_DIR", TEST_DATA_DIRECTORY.toString());

        assertThrows(IllegalArgumentException.class, () -> loader.load(
                Map.of("databaseBusyTimeout", "0"), environment));
        assertThrows(IllegalArgumentException.class, () -> loader.load(
                Map.of("databaseBusyTimeout", "60001"), environment));
    }
}
