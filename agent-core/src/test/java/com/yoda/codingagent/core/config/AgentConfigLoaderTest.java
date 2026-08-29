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
        assertEquals(20, config.defaultRunLimits().maxSteps());
        assertEquals(Duration.ofSeconds(900), config.defaultRunLimits().turnTimeout());
        assertEquals(20_000, config.defaultRunLimits().maxToolOutputChars());
        assertEquals(65_536, config.defaultRunLimits().maxInputTokens());
        assertEquals(8_192, config.defaultRunLimits().reservedOutputTokens());
        assertEquals(4, config.defaultRunLimits().recentFullTurns());
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
        assertThrows(IllegalArgumentException.class, () -> new AgentConfig(
                URI.create("https:///v1"), "secret", "qwen3.8-flash",
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

    @Test
    void rejectsInvalidBooleanAndCrossLimitCombinations() {
        AgentConfigLoader loader = new AgentConfigLoader();
        Map<String, String> environment = Map.of(
                "LLM_API_KEY", "secret",
                "CODING_AGENT_DATA_DIR", TEST_DATA_DIRECTORY.toString());

        assertThrows(IllegalArgumentException.class,
                () -> loader.load(Map.of("enableThinking", "yes"), environment));
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(Map.of("turnTimeoutSeconds", "10",
                        "modelTimeoutSeconds", "11"), environment));
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(Map.of("reservedOutputTokens", "8192",
                        "maxInputTokens", "8192"), environment));
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(Map.of("maxSteps", "101"), environment));
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(Map.of("maxSteps", " "), environment));
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(Map.of("dataDirectory", " "), environment));
    }

    @Test
    void acceptsAllDocumentedMinimumAndMaximumBoundaries() {
        AgentConfigLoader loader = new AgentConfigLoader();
        Map<String, String> environment = Map.of("LLM_API_KEY", "secret");
        AgentConfig minimum = loader.load(Map.ofEntries(
                Map.entry("dataDirectory", TEST_DATA_DIRECTORY.resolve("minimum").toString()),
                Map.entry("model", "m"), Map.entry("modelTimeoutSeconds", "1"),
                Map.entry("turnTimeoutSeconds", "1"),
                Map.entry("commandTimeoutSeconds", "1"),
                Map.entry("databaseBusyTimeout", "1"), Map.entry("maxSteps", "1"),
                Map.entry("maxToolOutputChars", "1024"),
                Map.entry("maxInputTokens", "8192"),
                Map.entry("reservedOutputTokens", "512"),
                Map.entry("recentFullTurns", "0"), Map.entry("maxSseEventBytes", "1024"),
                Map.entry("maxResponseCharacters", "1024")), environment);
        AgentConfig maximum = loader.load(Map.ofEntries(
                Map.entry("dataDirectory", TEST_DATA_DIRECTORY.resolve("maximum").toString()),
                Map.entry("model", "m".repeat(200)),
                Map.entry("modelTimeoutSeconds", "3600"),
                Map.entry("turnTimeoutSeconds", "3600"),
                Map.entry("commandTimeoutSeconds", "900"),
                Map.entry("databaseBusyTimeout", "60000"),
                Map.entry("maxSteps", "100"),
                Map.entry("maxToolOutputChars", "200000"),
                Map.entry("maxInputTokens", "1000000"),
                Map.entry("reservedOutputTokens", "200000"),
                Map.entry("recentFullTurns", "32"),
                Map.entry("maxSseEventBytes", "4194304"),
                Map.entry("maxResponseCharacters", "16777216")), environment);

        assertEquals(1, minimum.defaultRunLimits().maxSteps());
        assertEquals(100, maximum.defaultRunLimits().maxSteps());
        assertEquals(32, maximum.defaultRunLimits().recentFullTurns());
        assertEquals(4_194_304, maximum.maxSseEventBytes());
        assertEquals(16_777_216, maximum.maxResponseCharacters());
    }

    @Test
    void rejectsEveryDocumentedOutOfRangeBoundary() {
        AgentConfigLoader loader = new AgentConfigLoader();
        Map<String, String> environment = Map.of(
                "LLM_API_KEY", "secret",
                "CODING_AGENT_DATA_DIR", TEST_DATA_DIRECTORY.toString());
        Map<String, String> invalid = Map.ofEntries(
                Map.entry("modelTimeoutSeconds", "3601"),
                Map.entry("turnTimeoutSeconds", "3601"),
                Map.entry("commandTimeoutSeconds", "901"),
                Map.entry("databaseBusyTimeout", "60001"),
                Map.entry("maxSteps", "0"),
                Map.entry("maxToolOutputChars", "1023"),
                Map.entry("maxInputTokens", "8191"),
                Map.entry("reservedOutputTokens", "511"),
                Map.entry("recentFullTurns", "33"),
                Map.entry("maxSseEventBytes", "4194305"),
                Map.entry("maxResponseCharacters", "16777217"));

        invalid.forEach((name, value) -> assertThrows(IllegalArgumentException.class,
                () -> loader.load(Map.of(name, value), environment), name));
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(Map.of("model", "m".repeat(201)), environment));
    }
}
