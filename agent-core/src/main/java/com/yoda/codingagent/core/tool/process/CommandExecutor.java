package com.yoda.codingagent.core.tool.process;

import com.yoda.codingagent.core.api.CancellationToken;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface CommandExecutor {

    CommandResult run(List<String> argv, Path cwd, Duration timeout,
                      int maximumBytesPerStream, CancellationToken cancellationToken);
}
