package dev.superice.gdcc.api;

import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/// Frozen view of one asynchronous compile task.
///
/// `compile(...)` now only starts a task and returns its ID. Callers poll this snapshot by task ID
/// to observe whether the virtual-thread compile is still running and, once complete, to read the
/// final `CompileResult`.
public record CompileTaskSnapshot(
        long taskId,
        @NotNull String moduleId,
        @NotNull State state,
        @NotNull Instant createdAt,
        @Nullable Instant completedAt,
        @Nullable CompileResult result
) {
    public CompileTaskSnapshot {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        switch (state) {
            case RUNNING -> {
                if (completedAt != null) {
                    throw new IllegalArgumentException("completedAt must be null while task is RUNNING");
                }
                if (result != null) {
                    throw new IllegalArgumentException("result must be null while task is RUNNING");
                }
            }
            case SUCCEEDED, FAILED -> {
                Objects.requireNonNull(completedAt, "completedAt must not be null after task completion");
                Objects.requireNonNull(result, "result must not be null after task completion");
            }
        }
    }

    public boolean completed() {
        return state != State.RUNNING;
    }

    public boolean success() {
        return state == State.SUCCEEDED;
    }

    public enum State {
        RUNNING,
        SUCCEEDED,
        FAILED
    }
}
