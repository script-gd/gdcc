package dev.superice.gdcc.api.task;

import dev.superice.gdcc.api.CompileResult;
import dev.superice.gdcc.api.CompileTaskEvent;
import dev.superice.gdcc.api.CompileTaskSnapshot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Mutable state holder for one compile task plus thread-local event routing.
public final class CompileTaskState {
    private static final @NotNull ThreadLocal<CompileTaskState> CURRENT_TASK = new ThreadLocal<>();

    private final long taskId;
    private final @NotNull String moduleId;
    private final @NotNull Instant createdAt;
    private final @NotNull CompileTaskHooks compileTaskHooks;
    private final @NotNull ArrayList<CompileTaskEvent> events = new ArrayList<>();
    private volatile @NotNull CompileTaskSnapshot snapshot;
    private volatile boolean cancellationRequested;
    private volatile @Nullable Thread runnerThread;

    public CompileTaskState(
            long taskId,
            @NotNull String moduleId,
            @NotNull Instant createdAt,
            @NotNull CompileTaskHooks compileTaskHooks
    ) {
        this.taskId = taskId;
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.compileTaskHooks = Objects.requireNonNull(compileTaskHooks, "compileTaskHooks must not be null");
        snapshot = new CompileTaskSnapshot(
                taskId,
                moduleId,
                CompileTaskSnapshot.State.QUEUED,
                CompileTaskSnapshot.Stage.QUEUED,
                "Queued for execution",
                0,
                0,
                null,
                1,
                createdAt,
                null,
                null
        );
    }

    /// Records one event for the compile task currently bound to this thread.
    public static boolean recordCurrentThreadEvent(@NotNull String category, @NotNull String detail) {
        var event = new CompileTaskEvent(category, detail);
        var taskState = CURRENT_TASK.get();
        if (taskState == null) {
            return false;
        }
        taskState.recordEvent(event);
        return true;
    }

    static void bindCurrentThread(@NotNull CompileTaskState taskState) {
        CURRENT_TASK.set(Objects.requireNonNull(taskState, "taskState must not be null"));
    }

    static void clearCurrentThread() {
        CURRENT_TASK.remove();
    }

    public @NotNull CompileTaskSnapshot snapshot() {
        return snapshot;
    }

    public synchronized @Nullable CompileTaskEvent latestEvent() {
        return events.isEmpty() ? null : events.getLast();
    }

    public synchronized @NotNull List<CompileTaskEvent> events() {
        return List.copyOf(events);
    }

    public synchronized void clearEvents() {
        events.clear();
    }

    public void attachRunnerThread(@NotNull Thread thread) {
        runnerThread = Objects.requireNonNull(thread, "thread must not be null");
    }

    public synchronized boolean requestCancellation() {
        if (snapshot.completed()) {
            return false;
        }
        cancellationRequested = true;
        return true;
    }

    public boolean cancellationRequested() {
        return cancellationRequested;
    }

    public void interruptRunner() {
        var thread = runnerThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    public boolean expiredAt(@NotNull Instant now, @NotNull Duration ttl) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        var current = snapshot;
        return current.completed()
                && !Objects.requireNonNull(current.completedAt(), "completedAt must not be null")
                .plus(ttl)
                .isAfter(now);
    }

    synchronized void recordEvent(@NotNull CompileTaskEvent event) {
        events.add(Objects.requireNonNull(event, "event must not be null"));
    }

    void updateRunningStage(
            @NotNull CompileTaskSnapshot.Stage stage,
            @Nullable String stageMessage,
            int completedUnits,
            int totalUnits,
            @Nullable String currentSourcePath
    ) {
        var next = nextRunningSnapshot(stage, stageMessage, completedUnits, totalUnits, currentSourcePath);
        if (next == null) {
            return;
        }
        compileTaskHooks.afterSnapshotUpdate(next);
    }

    boolean complete(
            @NotNull Instant completedAt,
            @NotNull CompileResult result,
            @NotNull CompileTaskSnapshot.Stage completedStage
    ) {
        CompileTaskSnapshot next;
        synchronized (this) {
            var current = snapshot;
            if (current.completed()) {
                return false;
            }
            next = new CompileTaskSnapshot(
                    taskId,
                    moduleId,
                    result.success() ? CompileTaskSnapshot.State.SUCCEEDED : CompileTaskSnapshot.State.FAILED,
                    completedStage,
                    result.success() ? "Compile completed successfully" : current.stageMessage(),
                    result.success() ? current.totalUnits() : current.completedUnits(),
                    current.totalUnits(),
                    result.success() ? null : current.currentSourcePath(),
                    current.revision() + 1,
                    createdAt,
                    completedAt,
                    result
            );
            snapshot = next;
        }
        compileTaskHooks.afterSnapshotUpdate(next);
        return true;
    }

    public boolean completeCanceled(@NotNull Instant completedAt, @NotNull CompileResult result) {
        CompileTaskSnapshot next;
        synchronized (this) {
            var current = snapshot;
            if (current.completed()) {
                return false;
            }
            next = new CompileTaskSnapshot(
                    taskId,
                    moduleId,
                    CompileTaskSnapshot.State.CANCELED,
                    current.stage(),
                    "Compile task canceled",
                    current.completedUnits(),
                    current.totalUnits(),
                    current.currentSourcePath(),
                    current.revision() + 1,
                    createdAt,
                    completedAt,
                    result
            );
            snapshot = next;
        }
        compileTaskHooks.afterSnapshotUpdate(next);
        return true;
    }

    private synchronized @Nullable CompileTaskSnapshot nextRunningSnapshot(
            @NotNull CompileTaskSnapshot.Stage stage,
            @Nullable String stageMessage,
            int completedUnits,
            int totalUnits,
            @Nullable String currentSourcePath
    ) {
        var current = snapshot;
        if (current.completed()) {
            throw new IllegalStateException("Completed compile task cannot return to RUNNING state");
        }
        var next = new CompileTaskSnapshot(
                taskId,
                moduleId,
                CompileTaskSnapshot.State.RUNNING,
                stage,
                stageMessage,
                completedUnits,
                totalUnits,
                currentSourcePath,
                current.revision() + 1,
                createdAt,
                null,
                null
        );
        if (sameProgress(current, next)) {
            return null;
        }
        snapshot = next;
        return next;
    }

    private boolean sameProgress(
            @NotNull CompileTaskSnapshot current,
            @NotNull CompileTaskSnapshot next
    ) {
        return current.state() == next.state()
                && current.stage() == next.stage()
                && Objects.equals(current.stageMessage(), next.stageMessage())
                && current.completedUnits() == next.completedUnits()
                && current.totalUnits() == next.totalUnits()
                && Objects.equals(current.currentSourcePath(), next.currentSourcePath());
    }
}
