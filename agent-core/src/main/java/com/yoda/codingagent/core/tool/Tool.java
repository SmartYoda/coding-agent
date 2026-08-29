package com.yoda.codingagent.core.tool;

public interface Tool {

    ToolDefinition definition();

    ToolResult execute(ToolContext context, ToolArguments arguments);
}
