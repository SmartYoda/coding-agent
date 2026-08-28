package com.yoda.codingagent.core.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface Tool {

    ToolDefinition definition();

    ToolResult execute(ToolContext context, ObjectNode arguments);
}
