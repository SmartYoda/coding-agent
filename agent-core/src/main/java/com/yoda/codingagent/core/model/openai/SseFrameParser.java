package com.yoda.codingagent.core.model.openai;

import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.error.AgentException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

public final class SseFrameParser {

    private final int maxEventBytes;

    public SseFrameParser(int maxEventBytes) {
        if (maxEventBytes < 1) {
            throw new IllegalArgumentException("maxEventBytes must be positive");
        }
        this.maxEventBytes = maxEventBytes;
    }

    public void parse(InputStream input, Consumer<SseFrame> consumer,
                      CancellationToken cancellationToken) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        FrameBuilder frame = new FrameBuilder(maxEventBytes);
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            checkCancelled(cancellationToken);
            if (value == '\n') {
                processLine(line.toByteArray(), frame, consumer);
                line.reset();
            } else {
                if (line.size() >= maxEventBytes) {
                    throw protocolError("SSE line exceeds configured size limit");
                }
                line.write(value);
            }
        }
        checkCancelled(cancellationToken);
        if (line.size() > 0) {
            processLine(line.toByteArray(), frame, consumer);
        }
        frame.emitIfPresent(consumer);
    }

    private static void processLine(byte[] bytes, FrameBuilder frame,
                                    Consumer<SseFrame> consumer) {
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        if (length == 0) {
            frame.emitIfPresent(consumer);
            return;
        }
        String line = new String(bytes, 0, length, StandardCharsets.UTF_8);
        if (line.startsWith(":")) {
            return;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        switch (field) {
            case "event" -> frame.event = value;
            case "data" -> frame.addData(value);
            default -> { }
        }
    }

    private static void checkCancelled(CancellationToken token) {
        if (token.isCancelled()) {
            throw new AgentException(ErrorCode.CANCELLED, "model request cancelled");
        }
    }

    private static AgentException protocolError(String message) {
        return new AgentException(ErrorCode.MODEL_PROTOCOL_ERROR, message);
    }

    public record SseFrame(String event, String data) {
        public SseFrame {
            Objects.requireNonNull(data, "data");
        }
    }

    private static final class FrameBuilder {
        private final int maxEventBytes;
        private final StringBuilder data = new StringBuilder();
        private int dataBytes;
        private String event;

        private FrameBuilder(int maxEventBytes) {
            this.maxEventBytes = maxEventBytes;
        }

        private void addData(String value) {
            int bytes = value.getBytes(StandardCharsets.UTF_8).length;
            int separator = data.isEmpty() ? 0 : 1;
            if ((long) dataBytes + separator + bytes > maxEventBytes) {
                throw protocolError("SSE event exceeds configured size limit");
            }
            if (!data.isEmpty()) {
                data.append('\n');
                dataBytes++;
            }
            data.append(value);
            dataBytes += bytes;
        }

        private void emitIfPresent(Consumer<SseFrame> consumer) {
            if (data.isEmpty() && event == null) {
                return;
            }
            consumer.accept(new SseFrame(event, data.toString()));
            event = null;
            data.setLength(0);
            dataBytes = 0;
        }
    }
}
