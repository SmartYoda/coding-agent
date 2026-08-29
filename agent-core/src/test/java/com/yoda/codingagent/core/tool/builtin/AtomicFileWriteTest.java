package com.yoda.codingagent.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.safety.WorkspaceGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileWriteTest {

    @Test
    void moveFailurePreservesOriginalAndDeletesCreatedTemporaryFile(@TempDir Path temp)
            throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = Files.createDirectory(temp.resolve("state"));
        Path original = Files.writeString(workspace.resolve("value.txt"), "original");
        WorkspaceGuard guard = new WorkspaceGuard(workspace, state);
        Path target = guard.resolveCreateOrReplaceTarget("value.txt");

        AgentException failure = assertThrows(AgentException.class,
                () -> FileToolSupport.writeAtomically(guard, target, "replacement", true,
                        (temporary, ignoredTarget, ignoredReplace) -> {
                            assertEquals("replacement", Files.readString(temporary));
                            throw new IOException("injected move failure");
                        }));

        assertEquals(ErrorCode.FILE_IO_ERROR, failure.errorCode());
        assertEquals("original", Files.readString(original));
        try (var files = Files.list(workspace)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".coding-agent-")));
        }
    }
}
