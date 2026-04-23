package dev.superice.gdcc.api;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.build.CProjectBuilder;
import dev.superice.gdcc.backend.c.build.CProjectInfo;
import dev.superice.gdcc.backend.c.gen.CCodegen;
import dev.superice.gdcc.exception.ApiCompileAlreadyRunningException;
import dev.superice.gdcc.exception.ApiCompileTaskNotFoundException;
import dev.superice.gdcc.exception.ApiEntryTypeMismatchException;
import dev.superice.gdcc.exception.ApiModuleAlreadyExistsException;
import dev.superice.gdcc.exception.ApiModuleBusyException;
import dev.superice.gdcc.exception.ApiModuleNotFoundException;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPassManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.frontend.parse.GdScriptParserService;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Consumer;

/// In-memory module registry facade intended for RPC adapters.
///
/// The API package owns remote-facing lifecycle and state orchestration, while frontend/lowering/
/// backend remain the sole compilation fact sources. Step 2 adds virtual-path normalization plus
/// in-memory file/directory CRUD without coupling the facade to any transport framework.
public final class API {
    private static final @NotNull DiagnosticSnapshot EMPTY_DIAGNOSTICS = new DiagnosticSnapshot(List.of());
    private static final @NotNull List<String> GENERATED_FILE_NAMES = List.of(
            "entry.c",
            "engine_method_binds.h",
            "entry.h"
    );
    private static final @NotNull ThreadLocal<CompileTaskState> CURRENT_COMPILE_TASK = new ThreadLocal<>();

    private final @NotNull Clock clock;
    private final @NotNull GdScriptParserService parserService;
    private final @NotNull FrontendLoweringPassManager loweringPassManager;
    private final @NotNull CProjectBuilder projectBuilder;
    private final @NotNull CompileTaskHooks compileTaskHooks;
    private final @NotNull ConcurrentHashMap<String, ManagedModule> modules = new ConcurrentHashMap<>();
    private final @NotNull ConcurrentHashMap<Long, CompileTaskState> compileTasks = new ConcurrentHashMap<>();
    private final @NotNull ConcurrentHashMap<String, Long> activeCompileTasksByModule = new ConcurrentHashMap<>();
    private final @NotNull AtomicLong nextCompileTaskId = new AtomicLong(1);

    static final class CompileTaskHooks {
        private static final @NotNull CompileTaskHooks NONE = new CompileTaskHooks(null);

        private final @Nullable Consumer<CompileTaskSnapshot> afterSnapshotUpdate;

        private CompileTaskHooks(@Nullable Consumer<CompileTaskSnapshot> afterSnapshotUpdate) {
            this.afterSnapshotUpdate = afterSnapshotUpdate;
        }

        static @NotNull CompileTaskHooks none() {
            return NONE;
        }

        static @NotNull CompileTaskHooks afterSnapshotUpdate(@NotNull Consumer<CompileTaskSnapshot> afterSnapshotUpdate) {
            return new CompileTaskHooks(Objects.requireNonNull(
                    afterSnapshotUpdate,
                    "afterSnapshotUpdate must not be null"
            ));
        }

        void afterSnapshotUpdate(@NotNull CompileTaskSnapshot snapshot) {
            if (afterSnapshotUpdate != null) {
                afterSnapshotUpdate.accept(snapshot);
            }
        }
    }

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
        var event = new CompileTaskEvent(category, detail);
        var taskState = CURRENT_COMPILE_TASK.get();
        if (taskState == null) {
            return false;
        }
        taskState.recordEvent(event);
        return true;
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
        var taskState = new CompileTaskState(taskId, normalizedModuleId, clock.instant(), compileTaskHooks);
        compileTasks.put(taskId, taskState);
        try {
            Thread.ofVirtual()
                    .name("gdcc-api-compile-" + taskId)
                    .start(() -> runCompileTask(taskState, normalizedModuleId, managedModule));
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

    /// Compilation runs against a frozen module snapshot so RPC writes after `compile(...)` starts
    /// cannot mutate the exact source set or compile options consumed by this pipeline.
    private @NotNull CompileResult compileFrozenRequest(
            @NotNull ModuleState ownerState,
            @NotNull ModuleState.CompileRequest request,
            @NotNull CompileTaskState taskState
    ) {
        var sourcePaths = displaySourcePaths(request);
        try {
            ownerState.prepareOutputMountRoot(request.compileOptions().outputMountRoot());
        } catch (ApiEntryTypeMismatchException | IllegalArgumentException exception) {
            return new CompileResult(
                    CompileResult.Outcome.CONFIGURATION_FAILED,
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    EMPTY_DIAGNOSTICS,
                    "Compile outputs for module '"
                            + request.moduleId()
                            + "' could not be mounted: "
                            + exception.getMessage(),
                    "",
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
        if (request.failure() != null) {
            return new CompileResult(
                    request.failure().outcome(),
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    EMPTY_DIAGNOSTICS,
                    request.failure().message(),
                    "",
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
        if (request.sourceSnapshots().isEmpty()) {
            return new CompileResult(
                    CompileResult.Outcome.SOURCE_COLLECTION_FAILED,
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    EMPTY_DIAGNOSTICS,
                    "Module '" + request.moduleId() + "' has no .gd source files to compile",
                    "",
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        var projectPath = request.compileOptions().projectPath();
        if (projectPath == null) {
            return new CompileResult(
                    CompileResult.Outcome.CONFIGURATION_FAILED,
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    EMPTY_DIAGNOSTICS,
                    "Compile options for module '" + request.moduleId() + "' must set projectPath before compile",
                    "",
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        var diagnostics = new DiagnosticManager();
        taskState.updateRunningStage(
                CompileTaskSnapshot.Stage.PARSING,
                "Parsing source units",
                0,
                request.sourceSnapshots().size(),
                null
        );
        var units = new ArrayList<FrontendSourceUnit>(request.sourceSnapshots().size());
        for (var index = 0; index < request.sourceSnapshots().size(); index++) {
            var sourceSnapshot = request.sourceSnapshots().get(index);
            taskState.updateRunningStage(
                    CompileTaskSnapshot.Stage.PARSING,
                    "Parsing " + sourceSnapshot.displayPath(),
                    index,
                    request.sourceSnapshots().size(),
                    sourceSnapshot.displayPath()
            );
            units.add(parserService.parseUnit(
                    sourceSnapshot.logicalPath(),
                    sourceSnapshot.source(),
                    diagnostics
            ));
            taskState.updateRunningStage(
                    CompileTaskSnapshot.Stage.PARSING,
                    "Parsed " + sourceSnapshot.displayPath(),
                    index + 1,
                    request.sourceSnapshots().size(),
                    sourceSnapshot.displayPath()
            );
        }
        var parseAndFrontendDiagnostics = diagnostics.snapshot();
        if (parseAndFrontendDiagnostics.hasErrors()) {
            return new CompileResult(
                    CompileResult.Outcome.FRONTEND_FAILED,
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    parseAndFrontendDiagnostics,
                    "Frontend diagnostics blocked compilation",
                    "",
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        var projectPathError = ensureProjectPathUsable(projectPath);
        if (projectPathError != null) {
            return new CompileResult(
                    CompileResult.Outcome.CONFIGURATION_FAILED,
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    parseAndFrontendDiagnostics,
                    projectPathError,
                    "",
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        try {
            var extensionApi = ExtensionApiLoader.loadVersion(request.compileOptions().godotVersion());
            var classRegistry = new ClassRegistry(extensionApi);
            var frontendModule = new FrontendModule(
                    request.moduleName(),
                    units,
                    request.topLevelCanonicalNameMap()
            );
            taskState.updateRunningStage(
                    CompileTaskSnapshot.Stage.LOWERING,
                    "Lowering frontend module",
                    request.sourceSnapshots().size(),
                    request.sourceSnapshots().size(),
                    null
            );
            var lowered = loweringPassManager.lower(frontendModule, classRegistry, diagnostics);
            var frontendDiagnostics = diagnostics.snapshot();
            if (lowered == null || frontendDiagnostics.hasErrors()) {
                return new CompileResult(
                        CompileResult.Outcome.FRONTEND_FAILED,
                        request.compileOptions(),
                        request.topLevelCanonicalNameMap(),
                        sourcePaths,
                        frontendDiagnostics,
                        "Frontend diagnostics blocked compilation",
                        "",
                        List.of(),
                        List.of(),
                        List.of()
                );
            }

            var projectInfo = new CProjectInfo(
                    request.moduleId(),
                    request.compileOptions().godotVersion(),
                    projectPath,
                    request.compileOptions().optimizationLevel(),
                    request.compileOptions().targetPlatform()
            );
            var codegen = new CCodegen();
            taskState.updateRunningStage(
                    CompileTaskSnapshot.Stage.CODEGEN_PREPARE,
                    "Preparing C code generation",
                    request.sourceSnapshots().size(),
                    request.sourceSnapshots().size(),
                    null
            );
            codegen.prepare(
                    new CodegenContext(projectInfo, classRegistry, request.compileOptions().strictMode()),
                    lowered
            );

            taskState.updateRunningStage(
                    CompileTaskSnapshot.Stage.BUILDING_NATIVE,
                    "Building native artifacts",
                    request.sourceSnapshots().size(),
                    request.sourceSnapshots().size(),
                    null
            );
            var buildResult = projectBuilder.buildProject(projectInfo, codegen);
            var generatedFiles = collectGeneratedFiles(projectPath);
            if (!buildResult.success()) {
                return new CompileResult(
                        CompileResult.Outcome.BUILD_FAILED,
                        request.compileOptions(),
                        request.topLevelCanonicalNameMap(),
                        sourcePaths,
                        frontendDiagnostics,
                        "Native build reported failure; see buildLog for details",
                        buildResult.buildLog(),
                        generatedFiles,
                        buildResult.artifacts(),
                        List.of()
                );
            }
            try {
                var outputLinks = ownerState.mountCompileOutputs(
                        request.compileOptions().outputMountRoot(),
                        generatedFiles,
                        buildResult.artifacts()
                );
                return new CompileResult(
                        CompileResult.Outcome.SUCCESS,
                        request.compileOptions(),
                        request.topLevelCanonicalNameMap(),
                        sourcePaths,
                        frontendDiagnostics,
                        null,
                        buildResult.buildLog(),
                        generatedFiles,
                        buildResult.artifacts(),
                        outputLinks
                );
            } catch (ApiEntryTypeMismatchException | IllegalArgumentException exception) {
                return new CompileResult(
                        CompileResult.Outcome.CONFIGURATION_FAILED,
                        request.compileOptions(),
                        request.topLevelCanonicalNameMap(),
                        sourcePaths,
                        frontendDiagnostics,
                        "Compile outputs for module '"
                                + request.moduleId()
                                + "' could not be mounted: "
                                + exception.getMessage(),
                        buildResult.buildLog(),
                        generatedFiles,
                        buildResult.artifacts(),
                        List.of()
                );
            }
        } catch (IOException exception) {
            return new CompileResult(
                    CompileResult.Outcome.BUILD_FAILED,
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    diagnostics.snapshot(),
                    "Build pipeline failed: " + exception.getMessage(),
                    exception.getMessage(),
                    collectGeneratedFiles(projectPath),
                    List.of(),
                    List.of()
            );
        }
    }

    private @Nullable String ensureProjectPathUsable(@NotNull Path projectPath) {
        try {
            Files.createDirectories(projectPath);
            return null;
        } catch (IOException exception) {
            return "Project path '" + projectPath + "' cannot be used as a build directory: " + exception.getMessage();
        }
    }

    private @NotNull List<String> displaySourcePaths(@NotNull ModuleState.CompileRequest request) {
        return request.sourceSnapshots().stream()
                .map(ModuleState.SourceSnapshot::displayPath)
                .toList();
    }

    private void runCompileTask(
            @NotNull CompileTaskState taskState,
            @NotNull String normalizedModuleId,
            @NotNull ManagedModule ownerModule
    ) {
        ModuleState.CompileRequest request = null;
        CURRENT_COMPILE_TASK.set(taskState);
        try {
            try {
                taskState.updateRunningStage(
                        CompileTaskSnapshot.Stage.FREEZING_INPUTS,
                        "Freezing compile inputs",
                        0,
                        0,
                        null
                );
                taskState.updateRunningStage(
                        CompileTaskSnapshot.Stage.COLLECTING_SOURCES,
                        "Collecting .gd sources from module VFS",
                        0,
                        0,
                        null
                );
                request = ownerModule.state().freezeCompileRequest();
                taskState.updateRunningStage(
                        CompileTaskSnapshot.Stage.COLLECTING_SOURCES,
                        "Collected " + request.sourceSnapshots().size() + " source units",
                        0,
                        request.sourceSnapshots().size(),
                        null
                );
                var result = compileFrozenRequest(ownerModule.state(), request, taskState);
                finishCompileTask(taskState, normalizedModuleId, ownerModule, result);
            } catch (Throwable throwable) {
                finishCompileTask(
                        taskState,
                        normalizedModuleId,
                        ownerModule,
                        unexpectedTaskFailure(request, throwable)
                );
            }
        } finally {
            CURRENT_COMPILE_TASK.remove();
        }
    }

    private void finishCompileTask(
            @NotNull CompileTaskState taskState,
            @NotNull String normalizedModuleId,
            @NotNull ManagedModule ownerModule,
            @NotNull CompileResult result
    ) {
        taskState.complete(
                clock.instant(),
                result,
                result.success() ? CompileTaskSnapshot.Stage.FINISHED : taskState.snapshot().stage()
        );
        ownerModule.state().setLastCompileResult(result);
        activeCompileTasksByModule.remove(normalizedModuleId, taskState.taskId());
        ownerModule.endCompile(taskState.taskId());
    }

    private @NotNull CompileResult unexpectedTaskFailure(
            @Nullable ModuleState.CompileRequest request,
            @NotNull Throwable throwable
    ) {
        var projectPath = request == null ? null : request.compileOptions().projectPath();
        var failureMessage = "Compile task failed unexpectedly: " + describeThrowable(throwable);
        return new CompileResult(
                CompileResult.Outcome.BUILD_FAILED,
                request == null ? CompileOptions.defaults() : request.compileOptions(),
                request == null ? Map.of() : request.topLevelCanonicalNameMap(),
                request == null ? List.of() : displaySourcePaths(request),
                EMPTY_DIAGNOSTICS,
                failureMessage,
                failureMessage,
                projectPath == null ? List.of() : collectGeneratedFiles(projectPath),
                List.of(),
                List.of()
        );
    }

    private @NotNull String describeThrowable(@NotNull Throwable throwable) {
        var message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getClass().getSimpleName() + ": " + message;
    }

    private @NotNull List<Path> collectGeneratedFiles(@NotNull Path projectPath) {
        return GENERATED_FILE_NAMES.stream()
                .map(projectPath::resolve)
                .filter(Files::exists)
                .toList();
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

    private static final class CompileTaskState {
        private final long taskId;
        private final @NotNull String moduleId;
        private final @NotNull Instant createdAt;
        private final @NotNull CompileTaskHooks compileTaskHooks;
        private final @NotNull ArrayList<CompileTaskEvent> events = new ArrayList<>();
        private volatile @NotNull CompileTaskSnapshot snapshot;

        private CompileTaskState(
                long taskId,
                @NotNull String moduleId,
                @NotNull Instant createdAt,
                @NotNull CompileTaskHooks compileTaskHooks
        ) {
            this.taskId = taskId;
            this.moduleId = moduleId;
            this.createdAt = createdAt;
            this.compileTaskHooks = compileTaskHooks;
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

        private long taskId() {
            return taskId;
        }

        private @NotNull CompileTaskSnapshot snapshot() {
            return snapshot;
        }

        private synchronized void recordEvent(@NotNull CompileTaskEvent event) {
            events.add(Objects.requireNonNull(event, "event must not be null"));
        }

        private synchronized @Nullable CompileTaskEvent latestEvent() {
            return events.isEmpty() ? null : events.getLast();
        }

        private synchronized @NotNull List<CompileTaskEvent> events() {
            return List.copyOf(events);
        }

        private synchronized void clearEvents() {
            events.clear();
        }

        private void updateRunningStage(
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

        private void complete(
                @NotNull Instant completedAt,
                @NotNull CompileResult result,
                @NotNull CompileTaskSnapshot.Stage completedStage
        ) {
            CompileTaskSnapshot next;
            synchronized (this) {
                var current = snapshot;
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
}
