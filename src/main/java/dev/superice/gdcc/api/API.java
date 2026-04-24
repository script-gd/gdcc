package dev.superice.gdcc.api;

import dev.superice.gdcc.api.cleaner.CompileTaskCleaner;
import dev.superice.gdcc.api.task.CompileTaskHooks;
import dev.superice.gdcc.api.task.CompileTaskRunner;
import dev.superice.gdcc.api.task.CompileTaskState;
import dev.superice.gdcc.backend.c.build.CProjectBuilder;
import dev.superice.gdcc.exception.ApiCompileAlreadyRunningException;
import dev.superice.gdcc.exception.ApiCompileTaskNotFoundException;
import dev.superice.gdcc.exception.ApiModuleAlreadyExistsException;
import dev.superice.gdcc.exception.ApiModuleBusyException;
import dev.superice.gdcc.exception.ApiModuleNotFoundException;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPassManager;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/// In-memory module registry facade intended for RPC adapters.
///
/// The API package owns remote-facing lifecycle and state orchestration, while frontend/lowering/
/// backend remain the sole compilation fact sources. Step 2 adds virtual-path normalization plus
/// in-memory file/directory CRUD without coupling the facade to any transport framework.
public final class API {
    private static final @NotNull Duration DEFAULT_COMPLETED_COMPILE_TASK_TTL = Duration.ofMinutes(30);
    private static final @NotNull Duration DEFAULT_COMPILE_TASK_SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final @NotNull Clock clock;
    private final @NotNull GdScriptParserService parserService;
    private final @NotNull FrontendLoweringPassManager loweringPassManager;
    private final @NotNull CProjectBuilder projectBuilder;
    private final @NotNull CompileTaskHooks compileTaskHooks;
    private final @NotNull ConcurrentHashMap<String, ManagedModule> modules = new ConcurrentHashMap<>();
    private final @NotNull ConcurrentHashMap<Long, CompileTaskState> compileTasks = new ConcurrentHashMap<>();
    private final @NotNull CompileTaskCleaner compileTaskCleaner;
    private final @NotNull AtomicLong nextCompileTaskId = new AtomicLong(1);

    public API() {
        this(
                Clock.systemUTC(),
                new GdScriptParserService(),
                new FrontendLoweringPassManager(),
                new CProjectBuilder(),
                CompileTaskHooks.none(),
                DEFAULT_COMPLETED_COMPILE_TASK_TTL,
                DEFAULT_COMPILE_TASK_SWEEP_INTERVAL
        );
    }

    API(@NotNull Clock clock) {
        this(
                clock,
                new GdScriptParserService(),
                new FrontendLoweringPassManager(),
                new CProjectBuilder(),
                CompileTaskHooks.none(),
                DEFAULT_COMPLETED_COMPILE_TASK_TTL,
                DEFAULT_COMPILE_TASK_SWEEP_INTERVAL
        );
    }

    API(
            @NotNull Clock clock,
            @NotNull GdScriptParserService parserService,
            @NotNull FrontendLoweringPassManager loweringPassManager,
            @NotNull CProjectBuilder projectBuilder,
            @NotNull CompileTaskHooks compileTaskHooks,
            @NotNull Duration completedCompileTaskTtl,
            @NotNull Duration compileTaskSweepInterval
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.parserService = Objects.requireNonNull(parserService, "parserService must not be null");
        this.loweringPassManager = Objects.requireNonNull(loweringPassManager, "loweringPassManager must not be null");
        this.projectBuilder = Objects.requireNonNull(projectBuilder, "projectBuilder must not be null");
        this.compileTaskHooks = Objects.requireNonNull(compileTaskHooks, "compileTaskHooks must not be null");
        compileTaskCleaner = new CompileTaskCleaner(
                clock,
                compileTasks,
                completedCompileTaskTtl,
                compileTaskSweepInterval
        );
    }

    /// Records one event for the compile task currently executing on this thread. This is meant for
    /// frontend/backend code that runs inside the API-managed compile pipeline.
    public static boolean recordCurrentCompileTaskEvent(@NotNull String category, @NotNull String detail) {
        return CompileTaskState.recordCurrentThreadEvent(category, detail);
    }

    public @NotNull ModuleSnapshot createModule(@NotNull String moduleId, @NotNull String moduleName) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var createdState = new ModuleState(
                normalizedModuleId,
                StringUtil.requireTrimmedNonBlank(moduleName, "moduleName"),
                clock
        );
        var existingState = modules.putIfAbsent(normalizedModuleId, new ManagedModule(createdState));
        if (existingState != null) {
            throw new ApiModuleAlreadyExistsException("Module '" + normalizedModuleId + "' already exists");
        }
        return createdState.snapshot();
    }

    public @NotNull ModuleSnapshot getModule(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, ModuleState::snapshot);
    }

    /// Stable ordering keeps RPC list responses predictable even though storage uses a concurrent map.
    public @NotNull List<ModuleSnapshot> listModules() {
        return modules.values().stream()
                .map(ManagedModule::snapshotIfPresent)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ModuleSnapshot::moduleId))
                .toList();
    }

    public @NotNull ModuleSnapshot deleteModule(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var managedModule = modules.get(normalizedModuleId);
        if (managedModule == null) {
            throw new ApiModuleNotFoundException("Module '" + normalizedModuleId + "' does not exist");
        }
        var removedSnapshot = managedModule.reserveDelete(normalizedModuleId);
        try {
            if (!modules.remove(normalizedModuleId, managedModule)) {
                throw new ApiModuleNotFoundException("Module '" + normalizedModuleId + "' does not exist");
            }
            managedModule.finishDelete();
            return removedSnapshot;
        } catch (RuntimeException exception) {
            managedModule.cancelDelete();
            throw exception;
        }
    }

    public @NotNull VfsEntrySnapshot.DirectoryEntrySnapshot createDirectory(
            @NotNull String moduleId,
            @NotNull String path
    ) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                state -> state.createDirectory(VirtualPath.parse(path))
        );
    }

    public @NotNull VfsEntrySnapshot.FileEntrySnapshot putFile(
            @NotNull String moduleId,
            @NotNull String path,
            @NotNull String content
    ) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                state -> state.putFile(VirtualPath.parse(path), content)
        );
    }

    public @NotNull VfsEntrySnapshot.FileEntrySnapshot putFile(
            @NotNull String moduleId,
            @NotNull String path,
            @NotNull String content,
            @NotNull String displayPath
    ) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, state ->
                state.putFile(VirtualPath.parse(path), content, displayPath)
        );
    }

    public @NotNull String readFile(@NotNull String moduleId, @NotNull String path) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                state -> state.readFile(VirtualPath.parse(path))
        );
    }

    public @NotNull VfsEntrySnapshot.LinkEntrySnapshot createLink(
            @NotNull String moduleId,
            @NotNull String path,
            @NotNull VfsEntrySnapshot.LinkKind linkKind,
            @NotNull String target
    ) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, state ->
                state.createLink(VirtualPath.parse(path), linkKind, target)
        );
    }

    public @NotNull VfsEntrySnapshot deletePath(@NotNull String moduleId, @NotNull String path, boolean recursive) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, state ->
                state.deletePath(VirtualPath.parse(path), recursive)
        );
    }

    /// Directory entries are returned in stable lexical order so RPC consumers can diff results.
    public @NotNull List<VfsEntrySnapshot> listDirectory(@NotNull String moduleId, @NotNull String path) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                state -> state.listDirectory(VirtualPath.parse(path))
        );
    }

    public @NotNull VfsEntrySnapshot readEntry(@NotNull String moduleId, @NotNull String path) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                state -> state.readEntry(VirtualPath.parse(path))
        );
    }

    public @NotNull CompileOptions getCompileOptions(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, ModuleState::getCompileOptions);
    }

    public @NotNull CompileOptions setCompileOptions(
            @NotNull String moduleId,
            @NotNull CompileOptions compileOptions
    ) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                state -> state.setCompileOptions(compileOptions)
        );
    }

    public @NotNull Map<String, String> getTopLevelCanonicalNameMap(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(
                normalizedModuleId,
                ModuleState::getTopLevelCanonicalNameMap
        );
    }

    public @NotNull Map<String, String> setTopLevelCanonicalNameMap(
            @NotNull String moduleId,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, state ->
                state.setTopLevelCanonicalNameMap(topLevelCanonicalNameMap)
        );
    }

    public @Nullable CompileResult getLastCompileResult(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        return requireManagedModule(normalizedModuleId).runExclusive(normalizedModuleId, ModuleState::getLastCompileResult);
    }

    /// Publishes one queued compile task immediately, then lets a fresh virtual thread wait for the
    /// module gate and execute the actual compile. The caller must poll `getCompileTask(...)` for
    /// queue progress, running stages, and final completion. Completed tasks stay queryable only
    /// until their retention TTL expires.
    public long compile(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var managedModule = requireManagedModule(normalizedModuleId);
        var taskId = nextCompileTaskId.getAndIncrement();
        var taskState = new CompileTaskState(taskId, normalizedModuleId, clock.instant(), compileTaskHooks);
        compileTasks.put(taskId, taskState);
        try {
            managedModule.enqueueCompile(normalizedModuleId, taskId);
        } catch (RuntimeException exception) {
            compileTasks.remove(taskId);
            throw exception;
        }
        var ownerState = managedModule.state();
        try {
            compileTaskCleaner.ensureRunning();
            var thread = Thread.ofVirtual()
                    .name("gdcc-api-compile-" + taskId)
                    .unstarted(new CompileTaskRunner(
                            clock,
                            parserService,
                            loweringPassManager,
                            projectBuilder,
                            taskState,
                            () -> managedModule.awaitCompileTurn(normalizedModuleId, taskId),
                            () -> freezeCompileTaskRequest(ownerState),
                            result -> {
                                ownerState.setLastCompileResult(result);
                                managedModule.finishCompile(taskId);
                            }
                    ));
            taskState.attachRunnerThread(thread);
            thread.start();
        } catch (RuntimeException exception) {
            compileTasks.remove(taskId);
            managedModule.finishCompile(taskId);
            throw exception;
        }
        return taskId;
    }

    /// Returns the latest snapshot for one compile task started by `compile(...)`. Once the retention
    /// TTL expires, the task behaves as not found.
    public @NotNull CompileTaskSnapshot getCompileTask(long taskId) {
        return requireCompileTaskState(taskId).snapshot();
    }

    /// Requests cancellation for a retained queued or running compile task. Queued tasks release the
    /// module reservation immediately; running tasks are interrupted and complete as canceled once
    /// the compile runner reaches an interruptible point.
    public @NotNull CompileTaskSnapshot cancelCompileTask(long taskId) {
        var taskState = requireCompileTaskState(taskId);
        if (!taskState.requestCancellation()) {
            return taskState.snapshot();
        }

        var snapshot = taskState.snapshot();
        var managedModule = modules.get(snapshot.moduleId());
        if (managedModule != null && managedModule.cancelQueuedCompile(taskId)) {
            var result = canceledResult(snapshot);
            if (taskState.completeCanceled(clock.instant(), result)) {
                managedModule.state().setLastCompileResult(result);
            }
        }
        taskState.interruptRunner();
        return taskState.snapshot();
    }

    /// Returns the latest event for one retained compile task.
    public @Nullable CompileTaskEvent getLatestCompileTaskEvent(long taskId) {
        return requireCompileTaskState(taskId).latestEvent();
    }

    /// Events are returned in append order so clients can treat the list as a stable task log during
    /// the task retention window.
    public @NotNull List<CompileTaskEvent> listCompileTaskEvents(long taskId) {
        return requireCompileTaskState(taskId).events();
    }

    public void clearCompileTaskEvents(long taskId) {
        requireCompileTaskState(taskId).clearEvents();
    }

    private @NotNull ManagedModule requireManagedModule(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var managedModule = modules.get(normalizedModuleId);
        if (managedModule == null) {
            throw new ApiModuleNotFoundException("Module '" + normalizedModuleId + "' does not exist");
        }
        return managedModule;
    }

    private @NotNull String normalizeModuleId(@NotNull String moduleId) {
        return StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
    }

    private @NotNull CompileTaskState requireCompileTaskState(long taskId) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        var taskState = compileTasks.get(taskId);
        if (taskState == null) {
            throw new ApiCompileTaskNotFoundException("Compile task '" + taskId + "' does not exist");
        }
        return taskState;
    }

    private @NotNull CompileTaskRunner.Request freezeCompileTaskRequest(@NotNull ModuleState ownerState) {
        var request = ownerState.freezeCompileRequest();
        return new CompileTaskRunner.Request(
                request.moduleId(),
                request.moduleName(),
                request.compileOptions(),
                request.topLevelCanonicalNameMap(),
                request.sourceSnapshots().stream()
                        .map(sourceSnapshot -> new CompileTaskRunner.SourceSnapshot(
                                sourceSnapshot.displayPath(),
                                sourceSnapshot.logicalPath(),
                                sourceSnapshot.source()
                        ))
                        .toList(),
                request.failure() == null
                        ? null
                        : new CompileTaskRunner.Failure(request.failure().outcome(), request.failure().message()),
                ownerState::validateOutputMountRoot,
                ownerState::prepareOutputPublication,
                outputs -> ownerState.mountCompileOutputs(
                        request.compileOptions().outputMountRoot(),
                        outputs.generatedFiles(),
                        outputs.artifacts()
                )
        );
    }

    private @NotNull CompileResult canceledResult(@NotNull CompileTaskSnapshot snapshot) {
        return new CompileResult(
                CompileResult.Outcome.CANCELED,
                CompileOptions.defaults(),
                Map.of(),
                List.of(),
                new DiagnosticSnapshot(List.of()),
                "Compile task " + snapshot.taskId() + " was canceled",
                "",
                List.of(),
                List.of(),
                List.of()
        );
    }

    /// First implementation favors correctness over read/write concurrency: all same-module API
    /// operations serialize through one gate, while different modules remain independent.
    private static final class ManagedModule {
        private final @NotNull ModuleState state;
        private boolean busy;
        private boolean deleted;
        private long queuedCompileTaskId;
        private long activeCompileTaskId;

        private ManagedModule(@NotNull ModuleState state) {
            this.state = Objects.requireNonNull(state, "state must not be null");
        }

        private @NotNull ModuleState state() {
            return state;
        }

        private synchronized @Nullable ModuleSnapshot snapshotIfPresent() {
            if (deleted) {
                return null;
            }
            // Registry listing is best-effort: once a module is deleted we skip it, otherwise we
            // expose the latest stable state snapshot without publishing a partially deleted entry.
            return state.snapshot();
        }

        /// Queue reservation happens on the caller thread so `compile(...)` can return a visible
        /// task ID immediately while still preventing later same-module operations from overtaking
        /// the pending compile before its inputs are frozen.
        private synchronized void enqueueCompile(@NotNull String moduleId, long taskId) {
            if (deleted) {
                throw new ApiModuleNotFoundException("Module '" + moduleId + "' does not exist");
            }
            var existingTaskId = pendingCompileTaskId();
            if (existingTaskId != 0) {
                var existingStateLabel = queuedCompileTaskId != 0 ? "queued" : "active";
                throw new ApiCompileAlreadyRunningException(
                        "Module '" + moduleId + "' already has " + existingStateLabel + " compile task " + existingTaskId
                );
            }
            queuedCompileTaskId = taskId;
            notifyAll();
        }

        /// The background compile thread waits here until earlier same-module work drains, then it
        /// converts its published queued reservation into the active compile slot.
        private synchronized void awaitCompileTurn(@NotNull String moduleId, long taskId) {
            try {
                while (busy) {
                    awaitTurn();
                }
                if (deleted) {
                    throw new ApiModuleNotFoundException("Module '" + moduleId + "' does not exist");
                }
                if (queuedCompileTaskId != taskId) {
                    throw new IllegalStateException("Compile task " + taskId + " lost its queued reservation");
                }
                busy = true;
                activeCompileTaskId = taskId;
                queuedCompileTaskId = 0;
            } catch (RuntimeException exception) {
                if (queuedCompileTaskId == taskId) {
                    queuedCompileTaskId = 0;
                    notifyAll();
                }
                throw exception;
            }
        }

        private <T> T runExclusive(@NotNull String moduleId, @NotNull Function<ModuleState, T> operation) {
            enterOperation(moduleId);
            try {
                return Objects.requireNonNull(operation, "operation must not be null").apply(state);
            } finally {
                leaveOperation();
            }
        }

        private synchronized @NotNull ModuleSnapshot reserveDelete(@NotNull String moduleId) {
            while (busy && activeCompileTaskId == 0 && queuedCompileTaskId == 0) {
                awaitTurn();
            }
            if (deleted) {
                throw new ApiModuleNotFoundException("Module '" + moduleId + "' does not exist");
            }
            var compileTaskId = pendingCompileTaskId();
            if (compileTaskId != 0) {
                throw new ApiModuleBusyException(
                        "Module '" + moduleId + "' cannot be deleted while compile task "
                                + compileTaskId
                                + " is queued or active"
                );
            }
            busy = true;
            return state.snapshot();
        }

        private synchronized void finishDelete() {
            deleted = true;
            busy = false;
            notifyAll();
        }

        private synchronized void cancelDelete() {
            busy = false;
            notifyAll();
        }

        private synchronized void finishCompile(long taskId) {
            var changed = false;
            if (queuedCompileTaskId == taskId) {
                queuedCompileTaskId = 0;
                changed = true;
            }
            if (activeCompileTaskId == taskId) {
                activeCompileTaskId = 0;
                busy = false;
                changed = true;
            }
            if (changed) {
                notifyAll();
            }
        }

        private synchronized boolean cancelQueuedCompile(long taskId) {
            if (queuedCompileTaskId != taskId) {
                return false;
            }
            queuedCompileTaskId = 0;
            notifyAll();
            return true;
        }

        private synchronized void enterOperation(@NotNull String moduleId) {
            while (busy || queuedCompileTaskId != 0) {
                awaitTurn();
            }
            if (deleted) {
                throw new ApiModuleNotFoundException("Module '" + moduleId + "' does not exist");
            }
            busy = true;
        }

        private synchronized void leaveOperation() {
            busy = false;
            notifyAll();
        }

        private long pendingCompileTaskId() {
            return queuedCompileTaskId != 0 ? queuedCompileTaskId : activeCompileTaskId;
        }

        private void awaitTurn() {
            try {
                wait();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for module operation", exception);
            }
        }
    }

}
