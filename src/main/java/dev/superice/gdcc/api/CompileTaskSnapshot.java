package dev.superice.gdcc.api;

import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/// Frozen view of one asynchronous compile task.
///
/// `compile(...)` registers one task and returns its ID immediately, even if an earlier same-module
/// operation is still holding the module gate. Callers then poll `getCompileTask(...)` to observe
/// both the coarse task lifecycle and the finer-grained compile stage that is currently executing.
public record CompileTaskSnapshot(
        long taskId,
        @NotNull String moduleId,
        @NotNull State state,
        @NotNull Stage stage,
        @Nullable String stageMessage,
        int completedUnits,
        int totalUnits,
        @Nullable String currentSourcePath,
        long revision,
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
        Objects.requireNonNull(stage, "stage must not be null");
        if (stageMessage != null) {
            stageMessage = StringUtil.requireTrimmedNonBlank(stageMessage, "stageMessage");
        }
        if (completedUnits < 0) {
            throw new IllegalArgumentException("completedUnits must not be negative");
        }
        if (totalUnits < 0) {
            throw new IllegalArgumentException("totalUnits must not be negative");
        }
        if (completedUnits > totalUnits) {
            throw new IllegalArgumentException("completedUnits must not exceed totalUnits");
        }
        if (currentSourcePath != null) {
            currentSourcePath = StringUtil.requireTrimmedNonBlank(currentSourcePath, "currentSourcePath");
        }
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        switch (state) {
            case QUEUED -> {
                if (stage != Stage.QUEUED) {
                    throw new IllegalArgumentException("stage must be QUEUED while task state is QUEUED");
                }
                if (completedAt != null) {
                    throw new IllegalArgumentException("completedAt must be null while task is QUEUED");
                }
                if (result != null) {
                    throw new IllegalArgumentException("result must be null while task is QUEUED");
                }
            }
            case RUNNING -> {
                if (stage == Stage.QUEUED || stage == Stage.FINISHED) {
                    throw new IllegalArgumentException("running task must be in an active compile stage");
                }
                if (completedAt != null) {
                    throw new IllegalArgumentException("completedAt must be null while task is RUNNING");
                }
                if (result != null) {
                    throw new IllegalArgumentException("result must be null while task is RUNNING");
                }
            }
            case SUCCEEDED -> {
                if (stage != Stage.FINISHED) {
                    throw new IllegalArgumentException("successful task must end in FINISHED stage");
                }
                Objects.requireNonNull(completedAt, "completedAt must not be null after task completion");
                Objects.requireNonNull(result, "result must not be null after task completion");
                if (!result.success()) {
                    throw new IllegalArgumentException("successful task must carry a successful result");
                }
            }
            case FAILED -> {
                if (stage == Stage.QUEUED || stage == Stage.FINISHED) {
                    throw new IllegalArgumentException("failed task must preserve the active stage context");
                }
                Objects.requireNonNull(completedAt, "completedAt must not be null after task completion");
                Objects.requireNonNull(result, "result must not be null after task completion");
                if (result.success()) {
                    throw new IllegalArgumentException("failed task must not carry a successful result");
                }
            }
        }
    }

    public boolean completed() {
        return state == State.SUCCEEDED || state == State.FAILED;
    }

    public boolean success() {
        return state == State.SUCCEEDED;
    }

    public enum State {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public enum Stage {
        QUEUED,
        FREEZING_INPUTS,
        COLLECTING_SOURCES,
        PARSING,
        LOWERING,
        CODEGEN_PREPARE,
        BUILDING_NATIVE,
        FINISHED
    }
}
