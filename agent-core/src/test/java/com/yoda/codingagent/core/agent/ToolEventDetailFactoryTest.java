package com.yoda.codingagent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.tool.ToolCall;
import org.junit.jupiter.api.Test;

class ToolEventDetailFactoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void summarizesFileSearchAndCommandCallsWithoutFileContent() {
        assertEquals("src/main/App.java", detail("read_file",
                JSON.createObjectNode().put("path", "src/main/App.java")));
        assertEquals("marker @ src", detail("search_text",
                JSON.createObjectNode().put("query", "marker").put("path", "src")));
        assertEquals("mvn test", detail("execute_command",
                JSON.createObjectNode().set("argv", JSON.createArrayNode()
                        .add("mvn").add("test").add("-q").add("-DskipTests=false"))));
        assertEquals("mysql", detail("execute_command",
                JSON.createObjectNode().set("argv", JSON.createArrayNode()
                        .add("mysql").add("-psecret-that-must-not-appear"))));

        ObjectNode write = JSON.createObjectNode()
                .put("path", "result.txt")
                .put("content", "content-must-not-appear");
        assertEquals("result.txt", detail("write_file", write));
        assertFalse(detail("write_file", write).contains("content-must-not-appear"));
    }

    @Test
    void redactsNormalizesAndBoundsDetails() {
        String detail = ToolEventDetailFactory.create(new ToolCall(
                "call", "read_file", JSON.createObjectNode()
                .put("path", "prefix\nsecret-value-" + "x".repeat(150))),
                value -> value.replace("secret-value", "<redacted>"));

        assertFalse(detail.contains("secret-value"));
        assertFalse(detail.contains("\n"));
        assertTrue(detail.length() <= 100);
    }

    private static String detail(String name, ObjectNode arguments) {
        return ToolEventDetailFactory.create(
                new ToolCall("call", name, arguments), value -> value);
    }
}
