package com.yoda.codingagent.core.safety;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CommandPolicy {

    private static final Set<String> DENIED = Set.of(
            "sudo", "su", "rm", "shutdown", "reboot", "halt", "mkfs", "dd");
    private static final Set<String> APPROVAL = Set.of(
            "sh", "bash", "zsh", "curl", "wget", "ssh", "scp",
            "npm", "pnpm", "yarn", "pip", "pip3", "brew", "apt", "apt-get");
    private static final Set<String> MAVEN_GOALS = Set.of(
            "compile", "test", "package", "verify");
    private static final Set<String> MAVEN_FLAGS = Set.of(
            "-q", "--quiet", "-B", "--batch-mode", "-am", "--also-make");
    private static final Set<String> GRADLE_TASKS = Set.of("test", "check", "build");
    private static final Set<String> GRADLE_FLAGS = Set.of(
            "-q", "--quiet", "--no-daemon");

    private final Path workspaceRoot;
    private final Path protectedDataDirectory;

    public CommandPolicy(Path workspaceRoot, Path protectedDataDirectory) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
        this.protectedDataDirectory = Objects.requireNonNull(
                protectedDataDirectory, "protectedDataDirectory").toAbsolutePath().normalize();
    }

    public CommandDecision evaluate(List<String> argv, Path cwd) {
        Objects.requireNonNull(argv, "argv");
        Objects.requireNonNull(cwd, "cwd");
        if (argv.isEmpty() || containsDeniedPath(argv, cwd)) {
            return CommandDecision.DENY;
        }
        String executable = argv.getFirst();
        if (DENIED.contains(executable)) {
            return CommandDecision.DENY;
        }
        if (APPROVAL.contains(executable)) {
            return CommandDecision.REQUIRE_APPROVAL;
        }
        if (Set.of("mvn", "mvnw", "./mvnw").contains(executable)) {
            return validMaven(argv) ? CommandDecision.ALLOW : CommandDecision.REQUIRE_APPROVAL;
        }
        if (Set.of("gradle", "gradlew", "./gradlew").contains(executable)) {
            return validGradle(argv) ? CommandDecision.ALLOW : CommandDecision.REQUIRE_APPROVAL;
        }
        if (executable.equals("git")) {
            return validGit(argv) ? CommandDecision.ALLOW : CommandDecision.REQUIRE_APPROVAL;
        }
        return CommandDecision.REQUIRE_APPROVAL;
    }

    private static boolean validMaven(List<String> argv) {
        int goals = 0;
        for (int index = 1; index < argv.size(); index++) {
            String token = argv.get(index);
            if (MAVEN_GOALS.contains(token)) {
                goals++;
            } else if (MAVEN_FLAGS.contains(token)) {
                continue;
            } else if (token.equals("-pl") || token.equals("--projects")) {
                if (++index >= argv.size() || !argv.get(index).matches("[A-Za-z0-9_.:,!-]+")) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return goals > 0;
    }

    private static boolean validGradle(List<String> argv) {
        int tasks = 0;
        for (int index = 1; index < argv.size(); index++) {
            String token = argv.get(index);
            if (GRADLE_TASKS.contains(token)) {
                tasks++;
            } else if (!GRADLE_FLAGS.contains(token)) {
                return false;
            }
        }
        return tasks > 0;
    }

    private static boolean validGit(List<String> argv) {
        if (argv.size() < 2 || argv.get(1).startsWith("-")) {
            return false;
        }
        String command = argv.get(1);
        List<String> rest = argv.subList(2, argv.size());
        return switch (command) {
            case "status" -> rest.stream().allMatch(Set.of("--short", "--porcelain")::contains);
            case "diff" -> rest.stream().allMatch(token -> Set.of(
                    "--stat", "--name-only", "--name-status", "--cached", "--staged")
                    .contains(token) || (!token.startsWith("-") && !isDangerousGitToken(token)));
            case "log" -> validGitLog(rest);
            case "show" -> validGitShow(rest);
            default -> false;
        };
    }

    private static boolean validGitLog(List<String> rest) {
        for (int index = 0; index < rest.size(); index++) {
            String token = rest.get(index);
            if (token.equals("--oneline")) {
                continue;
            }
            if (token.equals("-n") && ++index < rest.size()) {
                try {
                    int value = Integer.parseInt(rest.get(index));
                    if (value >= 1 && value <= 100) {
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                    // Rejected below.
                }
            }
            return false;
        }
        return true;
    }

    private static boolean validGitShow(List<String> rest) {
        int revisions = 0;
        for (String token : rest) {
            if (Set.of("--stat", "--name-only", "--name-status").contains(token)) {
                continue;
            }
            if (token.startsWith("-") || isDangerousGitToken(token) || ++revisions > 1) {
                return false;
            }
        }
        return revisions == 1;
    }

    private boolean containsDeniedPath(List<String> argv, Path cwd) {
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        if (!normalizedCwd.startsWith(workspaceRoot)
                || normalizedCwd.startsWith(protectedDataDirectory)) {
            return true;
        }
        for (String token : argv) {
            if (token.equals("..")) {
                return true;
            }
            if (token.equals("agent.db") || token.contains("agent.db")) {
                return true;
            }
            if (!(token.contains("/") || token.contains("\\"))) {
                continue;
            }
            try {
                Path parsed = Path.of(token);
                Path resolved = parsed.isAbsolute() ? parsed.normalize()
                        : normalizedCwd.resolve(parsed).normalize();
                if (!resolved.startsWith(workspaceRoot)
                        || resolved.startsWith(protectedDataDirectory)) {
                    return true;
                }
            } catch (InvalidPathException exception) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDangerousGitToken(String token) {
        return token.equals("-C") || token.startsWith("--git-dir")
                || token.startsWith("--work-tree") || token.equals("--ext-diff")
                || token.equals("--textconv");
    }
}
