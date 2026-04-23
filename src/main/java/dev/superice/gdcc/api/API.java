package dev.superice.gdcc.api;

import dev.superice.gdcc.api.task.CompileTaskHooks;
import dev.superice.gdcc.api.task.CompileTaskRunner;
import dev.superice.gdcc.api.task.CompileTaskState;
import dev.superice.gdcc.backend.c.build.CProjectBuilder;
import dev.superice.gdcc.exception.ApiCompileAlreadyRunningException;
import dev.superice.gdcc.exception.ApiCompileTaskNotFoundException;
import dev.superice.gdcc.exception.ApiModuleAlreadyExistsException;
import dev.superice.gdcc.exception.ApiModuleBusyException;
import dev.superice.gdcc.exception.ApiModuleNotFoundException;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPassManager;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
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
    private final @NotNull Clock clock;
    private final @NotNull GdScriptParserService parserService;
    private final @NotNull FrontendLoweringPassManager loweringPassManager;
    private final @NotNull CProjectBuilder projectBuilder;
    private final @NotNull CompileTaskHooks compileTaskHooks;
    private final @NotNull ConcurrentHashMap<String, ManagedModule> modules = new ConcurrentHashMap<>();
    private final @NotNull ConcurrentHashMap<Long, CompileTaskState> compileTasks = new ConcurrentHashMap<>();
    private final @NotNull ConcurrentHashMap<String, Long> activeCompileTasksByModule = new ConcurrentHashMap<>();
    private final @NotNull AtomicLong nextCompileTaskId = new AtomicLong(1);

    public API() {
        this(
                Clock.systemUTC(),
                new GdScriptParserService(),
                new FrontendLoweringPassManager(),
                new CProjectBuilder(),
                CompileTaskHooks.none()
        );
    }

    API(@NotNull Clock clock) {
        this(
                clock,
                new GdScriptParserService(),
                new FrontendLoweringPassManager(),
                new CProjectBuilder(),
                CompileTaskHooks.none()
        );
    }

    API(
            @NotNull Clock clock,
            @NotNull GdScriptParserService parserService,
            @NotNull FrontendLoweringPassManager loweringPassManager,
            @NotNull CProjectBuilder projectBuilder,
            @NotNull CompileTaskHooks compileTaskHooks
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.parserService = Objects.requireNonNull(parserService, "parserService must not be null");
        this.loweringPassManager = Objects.requireNonNull(loweringPassManager, "loweringPassManager must not be null");
        this.projectBuilder = Objects.requireNonNull(projectBuilder, "projectBuilder must not be null");
        this.compileTaskHooks = Objects.requireNonNull(compileTaskHooks, "compileTaskHooks must not be null");
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

    /// Starts one asynchronous compile on a fresh virtual thread and returns the task ID. The
    /// caller must poll `getCompileTask(...)` for progress and completion.
    public long compile(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var managedModule = requireManagedModule(normalizedModuleId);
        var taskId = nextCompileTaskId.getAndIncrement();
        var existingTaskId = activeCompileTasksByModule.putIfAbsent(normalizedModuleId, taskId);
        if (existingTaskId != null) {
            throw new ApiCompileAlreadyRunningException(
                    "Module '" + normalizedModuleId + "' already has active compile task " + existingTaskId
            );
        }
        try {
            managedModule.beginCompile(normalizedModuleId, taskId);
        } catch (RuntimeException exception) {
            activeCompileTasksByModule.remove(normalizedModuleId, taskId);
            throw exception;
        }
        var ownerState = managedModule.state();
        var taskState = new CompileTaskState(taskId, normalizedModuleId, clock.instant(), compileTaskHooks);
        compileTasks.put(taskId, taskState);
        try {
            Thread.ofVirtual()
                    .name("gdcc-api-compile-" + taskId)
                    .start(new CompileTaskRunner(
                            clock,
                            parserService,
                            loweringPassManager,
                            projectBuilder,
                            taskState,
                            () -> freezeCompileTaskRequest(ownerState),
                            result -> {
                                ownerState.setLastCompileResult(result);
                                activeCompileTasksByModule.remove(normalizedModuleId, taskId);
                                managedModule.endCompile(taskId);
                            }
                    ));
        } catch (RuntimeException exception) {
            compileTasks.remove(taskId);
            activeCompileTasksByModule.remove(normalizedModuleId, taskId);
            managedModule.endCompile(taskId);
            throw exception;
        }
        return taskId;
    }

    /// Returns the latest snapshot for one compile task started by `compile(...)`.
    public @NotNull CompileTaskSnapshot getCompileTask(long taskId) {
        return requireCompileTaskState(taskId).snapshot();
    }

    public @Nullable CompileTaskEvent getLatestCompileTaskEvent(long taskId) {
        return requireCompileTaskState(taskId).latestEvent();
    }

    /// Events are returned in append order so clients can treat the list as a stable task log.
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
                ownerState::prepareOutputMountRoot,
                outputs -> ownerState.mountCompileOutputs(
                        request.compileOptions().outputMountRoot(),
                        outputs.generatedFiles(),
                        outputs.artifacts()
                )
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

        /// A compile reserves the module as soon as `compile(...)` accepts the task ID so later
        /// same-module operations cannot overtake it before inputs are frozen.
        private synchronized void beginCompile(@NotNull String moduleId, long taskId) {
            if (deleted) {
                throw new ApiModuleNotFoundException("Module '" + moduleId + "' does not exist");
            }
            queuedCompileTaskId = taskId;
            try {
                while (busy) {
                    awaitTurn();
                }
                if (deleted) {
                    throw new ApiModuleNotFoundException("Module '" + moduleId + "' does not exist");
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

        private synchronized void endCompile(long taskId) {
            if (queuedCompileTaskId == taskId) {
                queuedCompileTaskId = 0;
            }
            if (activeCompileTaskId == taskId) {
                activeCompileTaskId = 0;
            }
            busy = false;
            notifyAll();
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
