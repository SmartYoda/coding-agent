package com.yoda.codingagent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoda.codingagent.core.agent.AgentRunner;
import com.yoda.codingagent.core.agent.DefaultAgentService;
import com.yoda.codingagent.core.agent.SessionRegistry;
import com.yoda.codingagent.core.api.AgentRequest;
import com.yoda.codingagent.core.api.AgentService;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.SessionStatus;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.api.WorkspaceStatus;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.config.AgentConfigLoader;
import com.yoda.codingagent.core.config.SecretRedactor;
import com.yoda.codingagent.core.context.ContextManager;
import com.yoda.codingagent.core.context.TokenEstimator;
import com.yoda.codingagent.core.context.TurnDigestFactory;
import com.yoda.codingagent.core.model.ModelClient;
import com.yoda.codingagent.core.model.openai.OpenAiCompatibleChatModelClient;
import com.yoda.codingagent.core.persistence.sqlite.SqliteStateStore;
import com.yoda.codingagent.core.persistence.sqlite.DataDirectoryLock;
import com.yoda.codingagent.core.tool.Tool;
import com.yoda.codingagent.core.tool.ToolDispatcher;
import com.yoda.codingagent.core.tool.ToolOutputTruncator;
import com.yoda.codingagent.core.tool.ToolRegistry;
import com.yoda.codingagent.core.tool.builtin.ExecuteCommandTool;
import com.yoda.codingagent.core.tool.builtin.ListFilesTool;
import com.yoda.codingagent.core.tool.builtin.ReadFileTool;
import com.yoda.codingagent.core.tool.builtin.ReplaceInFileTool;
import com.yoda.codingagent.core.tool.builtin.SearchTextTool;
import com.yoda.codingagent.core.tool.builtin.WriteFileTool;
import com.yoda.codingagent.core.tool.process.CommandRunner;
import com.yoda.codingagent.core.workspace.WorkspaceRegistry;
import com.yoda.codingagent.core.workspace.WorkspaceResolver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.Function;

public final class CliApplication {

    private final Function<AgentConfig, ModelClient> modelClientFactory;

    public CliApplication() {
        this(OpenAiCompatibleChatModelClient::new);
    }

    public CliApplication(Function<AgentConfig, ModelClient> modelClientFactory) {
        this.modelClientFactory = Objects.requireNonNull(modelClientFactory, "modelClientFactory");
    }

    public int run(String[] args, Map<String, String> environment,
                   InputStream input, OutputStream output, OutputStream error) {
        PrintWriter out = new PrintWriter(output, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(error, true, StandardCharsets.UTF_8);
        SecretRedactor redactor = new SecretRedactor(environment.getOrDefault(
                "LLM_API_KEY", environment.getOrDefault("DASHSCOPE_API_KEY", "")));
        try {
            CliArguments cli = CliArguments.parse(args);
            if (cli.help()) {
                printHelp(out);
                return 0;
            }
            AgentConfig config = new AgentConfigLoader().load(
                    cli.configOverrides(), environment);
            redactor = new SecretRedactor(config.apiKey());
            try (CliRuntime runtime = compose(config)) {
                AgentService service = runtime.service();
                WorkspaceDescriptor workspace = selectWorkspace(service, cli);
                SessionDescriptor session = selectSession(service, workspace, cli, config);
                return interact(service, workspace, session, config, runtime,
                        input, out, redactor);
            }
        } catch (IllegalArgumentException exception) {
            err.println("Configuration error: " + redactor.redact(exception.getMessage()));
            return 2;
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            err.println("Startup error: " + redactor.redact(
                    message == null ? "unexpected failure" : message));
            return 1;
        }
    }

    private CliRuntime compose(AgentConfig config) {
        ObjectMapper objectMapper = new ObjectMapper();
        DataDirectoryLock dataDirectoryLock = DataDirectoryLock.acquire(config.dataDirectory());
        try {
            SqliteStateStore store = SqliteStateStore.open(dataDirectoryLock,
                    config.databasePath(), config.databaseBusyTimeout());
            WorkspaceRegistry workspaces = new WorkspaceRegistry(
                    store, new WorkspaceResolver(config.dataDirectory()));
            SessionRegistry sessions = new SessionRegistry(store, workspaces);
            List<Tool> tools = List.of(
                    new ListFilesTool(config.dataDirectory()),
                    new ReadFileTool(config.dataDirectory()),
                    new SearchTextTool(config.dataDirectory()),
                    new WriteFileTool(config.dataDirectory()),
                    new ReplaceInFileTool(config.dataDirectory()),
                    new ExecuteCommandTool(config.dataDirectory(), new CommandRunner()));
            SecretRedactor redactor = new SecretRedactor(config.apiKey());
            ToolDispatcher dispatcher = new ToolDispatcher(new ToolRegistry(tools),
                    redactor::redact, new ToolOutputTruncator());
            AgentRunner runner = new AgentRunner(modelClientFactory.apply(config), dispatcher,
                    objectMapper, config.model(), config.maxResponseCharacters(), store,
                    new ContextManager(new TokenEstimator()), new TurnDigestFactory(),
                    new com.yoda.codingagent.core.model.ModelRetryPolicy(),
                    com.yoda.codingagent.core.model.RetryWaiter.cancellableSleep(),
                    java.time.Clock.systemUTC(), redactor);
            AgentService service = new DefaultAgentService(workspaces, sessions, runner,
                    DefaultAgentService.DEFAULT_SYSTEM_PROMPT, redactor);
            return new CliRuntime(service, store.startupRecoverySummary(), dataDirectoryLock);
        } catch (RuntimeException exception) {
            dataDirectoryLock.close();
            throw exception;
        }
    }

    private static WorkspaceDescriptor selectWorkspace(AgentService service, CliArguments cli) {
        Path requested = (cli.workspacePath() == null ? Path.of("") : cli.workspacePath())
                .toAbsolutePath().normalize();
        String name = cli.workspaceName() == null ? "main" : cli.workspaceName();
        final Path canonical;
        try {
            canonical = requested.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("workspace must be an existing directory", exception);
        }
        WorkspaceDescriptor existing = service.listWorkspaces().stream()
                .filter(workspace -> workspace.root().equals(canonical))
                .findFirst().orElse(null);
        if (existing == null) {
            return service.registerWorkspace(name, canonical);
        }
        if (existing.status() != WorkspaceStatus.ACTIVE) {
            throw new IllegalArgumentException("registered workspace is not active");
        }
        return existing;
    }

    private static SessionDescriptor selectSession(AgentService service,
                                                   WorkspaceDescriptor workspace,
                                                   CliArguments cli, AgentConfig config) {
        if (cli.sessionId() == null) {
            return service.openSession(new SessionConfig(
                    workspace.workspaceId(), config.defaultRunLimits()));
        }
        SessionDescriptor session = service.getSession(cli.sessionId());
        if (!session.workspaceId().equals(workspace.workspaceId())
                || session.status() != SessionStatus.OPEN) {
            throw new IllegalArgumentException(
                    "session must be open and belong to the selected workspace");
        }
        return session;
    }

    private static int interact(AgentService service, WorkspaceDescriptor workspace,
                                SessionDescriptor session, AgentConfig config,
                                CliRuntime runtime, InputStream input, PrintWriter output,
                                SecretRedactor redactor) {
        ConsoleRenderer renderer = new ConsoleRenderer(output, redactor::redact);
        CliController controller = new CliController(service, config.defaultRunLimits(),
                workspace, session, renderer, new ContextView(),
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("coding-agent-turn-", 0).factory()));
        renderer.recovery(runtime.startupRecoverySummary());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            renderer.prompt();
            while (true) {
                String line = reader.readLine();
                renderer.commandRead();
                if (line != null && line.isBlank()) {
                    renderer.prompt();
                    continue;
                }
                try {
                    CliController.Outcome outcome = controller.handle(CliCommand.parse(line));
                    if (outcome.exit()) {
                        renderer.line("");
                        return outcome.exitCode();
                    }
                    if (outcome.prompt()) {
                        renderer.prompt();
                    }
                } catch (IllegalArgumentException exception) {
                    renderer.error(exception.getMessage());
                    renderer.prompt();
                } catch (RuntimeException exception) {
                    renderer.error(exception.getMessage() == null
                            ? "Command failed unexpectedly." : exception.getMessage());
                    renderer.prompt();
                }
            }
        } catch (IOException exception) {
            controller.handle(new CliCommand.Exit());
            throw new IllegalStateException("console input failed", exception);
        }
    }

    private static void printHelp(PrintWriter output) {
        output.println("Usage: java -jar coding-agent.jar [options]");
        output.println("  --workspace <name=path>       Select or register a workspace");
        output.println("  --session <uuid>              Resume an OPEN session");
        output.println("  --base-url <url>              OpenAI-compatible API base URL");
        output.println("  --model <name>                Model name");
        output.println("  --enable-thinking <true|false> Enable model thinking mode");
        output.println("  --data-dir <path>             Agent state directory");
        output.println("  --max-steps <n>               Maximum model steps per turn");
        output.println("  --turn-timeout-seconds <n>    Turn timeout");
        output.println("  --model-timeout-seconds <n>   Model stream timeout");
        output.println("  --command-timeout-seconds <n> Command timeout");
        output.println("  --max-tool-output-chars <n>   Tool result character limit");
        output.println("  --max-input-tokens <n>        Context input budget");
        output.println("  --reserved-output-tokens <n>  Reserved model output budget");
        output.println("  --recent-full-turns <n>       Full recent turns in context");
        output.println("  --help                        Show this help");
    }
}
