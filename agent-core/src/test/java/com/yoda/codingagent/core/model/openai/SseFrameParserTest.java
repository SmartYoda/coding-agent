package com.yoda.codingagent.core.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.openai.SseFrameParser.SseFrame;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseFrameParserTest {

    @Test
    void handlesCommentsCrLfAndMultipleDataLines() throws Exception {
        String input = ": keepalive\r\nevent: message\r\ndata: first\r\ndata: second\r\n\r\n"
                + "data: [DONE]\n\n";
        List<SseFrame> frames = new ArrayList<>();

        new SseFrameParser(1024).parse(bytes(input), frames::add, CancellationToken.NONE);

        assertEquals(List.of(
                new SseFrame("message", "first\nsecond"),
                new SseFrame(null, "[DONE]")), frames);
    }

    @Test
    void rejectsOversizedEvent() {
        String input = "data: 12345\n\n";
        assertThrows(AgentException.class, () ->
                new SseFrameParser(4).parse(bytes(input), ignored -> { }, CancellationToken.NONE));
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
