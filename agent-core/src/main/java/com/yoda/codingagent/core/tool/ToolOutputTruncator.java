package com.yoda.codingagent.core.tool;

public final class ToolOutputTruncator {

    public ToolResult truncate(ToolResult result, int maximumCharacters) {
        if (maximumCharacters < 1) {
            throw new IllegalArgumentException("maximumCharacters must be positive");
        }
        if (result.output().length() <= maximumCharacters) {
            return result;
        }
        int omitted = result.output().length();
        String marker;
        int available;
        do {
            marker = "\n... output truncated: omitted " + omitted + " characters ...\n";
            available = Math.max(0, maximumCharacters - marker.length());
            int recalculated = result.output().length() - available;
            if (recalculated == omitted) {
                break;
            }
            omitted = recalculated;
        } while (true);
        if (marker.length() >= maximumCharacters) {
            marker = marker.substring(0, maximumCharacters);
            return result.withOutput(marker, true);
        }
        int head = (available + 1) / 2;
        int tail = available - head;
        String output = result.output().substring(0, head) + marker
                + result.output().substring(result.output().length() - tail);
        return result.withOutput(output, true);
    }
}
