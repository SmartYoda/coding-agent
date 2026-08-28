package com.yoda.codingagent.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentConfigLoaderTest {

    @Test
    void defaultsToQwenFlashAndNeverPrintsTheKey() {
        String key = "test-secret-key";
        AgentConfig config = new AgentConfigLoader().load(Map.of(),
                Map.of("DASHSCOPE_API_KEY", key));

        assertEquals("qwen3.8-flash", config.model());
        assertEquals(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1"),
                config.baseUrl());
        assertFalse(config.toString().contains(key));
    }

    @Test
    void overrideWinsOverEnvironment() {
        AgentConfig config = new AgentConfigLoader().load(
                Map.of("model", "override-model", "apiKey", "override-key"),
                Map.of("LLM_MODEL", "environment-model", "LLM_API_KEY", "environment-key"));

        assertEquals("override-model", config.model());
        assertEquals("override-key", config.apiKey());
    }

    @Test
    void rejectsNonLoopbackHttp() {
        assertThrows(IllegalArgumentException.class, () -> new AgentConfig(
                URI.create("http://example.com/v1"), "secret", "qwen3.8-flash",
                Duration.ofSeconds(30), 1024, 4096, false));
    }
}
