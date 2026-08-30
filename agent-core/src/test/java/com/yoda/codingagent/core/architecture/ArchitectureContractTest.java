package com.yoda.codingagent.core.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yoda.codingagent.core.api.AgentResult;
import com.yoda.codingagent.core.agent.AgentRunner;
import com.yoda.codingagent.core.agent.DefaultAgentService;
import com.yoda.codingagent.core.config.SecretRedactor;
import java.io.IOException;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ArchitectureContractTest {

    @Test
    void apiSourcesImportOnlyTheJdk() throws IOException {
        Path apiDirectory = Path.of("src/main/java/com/yoda/codingagent/core/api");
        try (Stream<Path> sources = Files.list(apiDirectory)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(source)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ")) {
                        assertTrue(trimmed.startsWith("import java."),
                                () -> source + " has non-JDK API import: " + trimmed);
                    }
                }
            }
        }
    }

    @Test
    void agentResultFactoriesDoNotAcceptAgentLayerTypes() {
        Stream<Executable> executables = Stream.concat(
                Arrays.stream(AgentResult.class.getDeclaredConstructors()),
                Arrays.stream(AgentResult.class.getDeclaredMethods())
                        .filter(method -> Modifier.isStatic(method.getModifiers()))
                        .map(method -> (Executable) method));
        executables.flatMap(executable -> Arrays.stream(executable.getParameterTypes()))
                .forEach(type -> assertFalse(type.getPackageName().contains(".agent"),
                        () -> "AgentResult factory leaks agent type " + type.getName()));
    }

    @Test
    void safetySensitiveCompositionRequiresASecretRedactor() {
        for (var constructor : AgentRunner.class.getConstructors()) {
            assertTrue(Arrays.asList(constructor.getParameterTypes())
                            .contains(SecretRedactor.class),
                    () -> "AgentRunner constructor can bypass SecretRedactor: " + constructor);
        }
        for (var constructor : DefaultAgentService.class.getConstructors()) {
            assertTrue(Arrays.asList(constructor.getParameterTypes())
                            .contains(SecretRedactor.class),
                    () -> "DefaultAgentService constructor can bypass SecretRedactor: "
                            + constructor);
        }
    }

    @Test
    void forbiddenPackageDirectionsRemainAbsent() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        assertNoImport(sourceRoot, "com.yoda.codingagent.cli");
        assertNoImport(sourceRoot.resolve("com/yoda/codingagent/core/context"),
                "com.yoda.codingagent.core.persistence");
        assertNoImport(sourceRoot.resolve("com/yoda/codingagent/core/persistence"),
                "com.yoda.codingagent.core.agent");
        assertNoImport(sourceRoot.resolve("com/yoda/codingagent/core/tool"),
                "com.yoda.codingagent.core.config");
        assertNoImport(sourceRoot.resolve("com/yoda/codingagent/core/tool"),
                "com.yoda.codingagent.core.model");

        String runner = Files.readString(sourceRoot.resolve(
                "com/yoda/codingagent/core/agent/AgentRunner.java"));
        String stateStore = Files.readString(sourceRoot.resolve(
                "com/yoda/codingagent/core/persistence/StateStore.java"));
        String dispatcher = Files.readString(sourceRoot.resolve(
                "com/yoda/codingagent/core/tool/ToolDispatcher.java"));
        assertFalse(runner.contains("SessionRegistry"));
        assertFalse(stateStore.contains("AgentTurn"));
        assertFalse(stateStore.contains("TerminalSnapshot"));
        assertFalse(dispatcher.contains("SecretRedactor"));
    }

    @Test
    void coreSourcesContainNoFrameworkOrAgentSdkDependency() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        assertNoImport(sourceRoot, "org.springframework");
        assertNoImport(sourceRoot, "dev.langchain4j");
        assertNoImport(sourceRoot, "com.openai");
    }

    private static void assertNoImport(Path root, String forbiddenPrefix) throws IOException {
        try (Stream<Path> sources = Files.walk(root)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(source)) {
                    String trimmed = line.trim();
                    assertFalse(trimmed.startsWith("import " + forbiddenPrefix),
                            () -> source + " has forbidden dependency: " + trimmed);
                }
            }
        }
    }
}
