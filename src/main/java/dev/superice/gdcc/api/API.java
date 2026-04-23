package dev.superice.gdcc.api;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.build.CProjectBuilder;
import dev.superice.gdcc.backend.c.build.CProjectInfo;
import dev.superice.gdcc.backend.c.gen.CCodegen;
import dev.superice.gdcc.exception.ApiCompileAlreadyRunningException;
import dev.superice.gdcc.exception.ApiCompileTaskNotFoundException;
import dev.superice.gdcc.exception.ApiModuleAlreadyExistsException;
import dev.superice.gdcc.exception.ApiModuleNotFoundException;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.lowering.FrontendLoweringPassManager;
import dev.superice.gdcc.frontend.parse.FrontendModule;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    private final @NotNull Clock clock;
    private final @NotNull GdScriptParserService parserService;
    private final @NotNull FrontendLoweringPassManager loweringPassManager;
    private final @NotNull CProjectBuilder projectBuilder;
    private final @NotNull ConcurrentHashMap<String, ModuleState> modules = new ConcurrentHashMap<>();
    private final @NotNull ConcurrentHashMap<Long, CompileTaskState> compileTasks = new ConcurrentHashMap<>();
    private final @NotNull ConcurrentHashMap<String, Long> activeCompileTasksByModule = new ConcurrentHashMap<>();
    private final @NotNull AtomicLong nextCompileTaskId = new AtomicLong(1);

    public API() {
        this(Clock.systemUTC(), new GdScriptParserService(), new FrontendLoweringPassManager(), new CProjectBuilder());
    }

    API(@NotNull Clock clock) {
        this(clock, new GdScriptParserService(), new FrontendLoweringPassManager(), new CProjectBuilder());
    }

    API(
            @NotNull Clock clock,
            @NotNull GdScriptParserService parserService,
            @NotNull FrontendLoweringPassManager loweringPassManager,
            @NotNull CProjectBuilder projectBuilder
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.parserService = Objects.requireNonNull(parserService, "parserService must not be null");
        this.loweringPassManager = Objects.requireNonNull(loweringPassManager, "loweringPassManager must not be null");
        this.projectBuilder = Objects.requireNonNull(projectBuilder, "projectBuilder must not be null");
    }

    public @NotNull ModuleSnapshot createModule(@NotNull String moduleId, @NotNull String moduleName) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var createdState = new ModuleState(
                normalizedModuleId,
                StringUtil.requireTrimmedNonBlank(moduleName, "moduleName"),
                clock
        );
        var existingState = modules.putIfAbsent(normalizedModuleId, createdState);
        if (existingState != null) {
            throw new ApiModuleAlreadyExistsException("Module '" + normalizedModuleId + "' already exists");
        }
        return createdState.snapshot();
    }

    public @NotNull ModuleSnapshot getModule(@NotNull String moduleId) {
        return requireModuleState(moduleId).snapshot();
    }

    /// Stable ordering keeps RPC list responses predictable even though storage uses a concurrent map.
    public @NotNull List<ModuleSnapshot> listModules() {
        return modules.values().stream()
                .map(ModuleState::snapshot)
                .sorted(Comparator.comparing(ModuleSnapshot::moduleId))
                .toList();
    }

    public @NotNull ModuleSnapshot deleteModule(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var removedState = modules.remove(normalizedModuleId);
        if (removedState == null) {
            throw new ApiModuleNotFoundException("Module '" + normalizedModuleId + "' does not exist");
        }
        return removedState.snapshot();
    }

    public @NotNull VfsEntrySnapshot.DirectoryEntrySnapshot createDirectory(
            @NotNull String moduleId,
            @NotNull String path
    ) {
        return requireModuleState(moduleId).createDirectory(VirtualPath.parse(path));
    }

    public @NotNull VfsEntrySnapshot.FileEntrySnapshot putFile(
            @NotNull String moduleId,
            @NotNull String path,
            @NotNull String content
    ) {
        return requireModuleState(moduleId).putFile(VirtualPath.parse(path), content);
    }

    public @NotNull VfsEntrySnapshot.FileEntrySnapshot putFile(
            @NotNull String moduleId,
            @NotNull String path,
            @NotNull String content,
            @NotNull String displayPath
    ) {
        return requireModuleState(moduleId).putFile(VirtualPath.parse(path), content, displayPath);
    }

    public @NotNull String readFile(@NotNull String moduleId, @NotNull String path) {
        return requireModuleState(moduleId).readFile(VirtualPath.parse(path));
    }

    public @NotNull VfsEntrySnapshot.LinkEntrySnapshot createLink(
            @NotNull String moduleId,
            @NotNull String path,
            @NotNull VfsEntrySnapshot.LinkKind linkKind,
            @NotNull String target
    ) {
        return requireModuleState(moduleId).createLink(VirtualPath.parse(path), linkKind, target);
    }

    public @NotNull VfsEntrySnapshot deletePath(@NotNull String moduleId, @NotNull String path, boolean recursive) {
        return requireModuleState(moduleId).deletePath(VirtualPath.parse(path), recursive);
    }

    /// Directory entries are returned in stable lexical order so RPC consumers can diff results.
    public @NotNull List<VfsEntrySnapshot> listDirectory(@NotNull String moduleId, @NotNull String path) {
        return requireModuleState(moduleId).listDirectory(VirtualPath.parse(path));
    }

    public @NotNull VfsEntrySnapshot readEntry(@NotNull String moduleId, @NotNull String path) {
        return requireModuleState(moduleId).readEntry(VirtualPath.parse(path));
    }

    public @NotNull CompileOptions getCompileOptions(@NotNull String moduleId) {
        return requireModuleState(moduleId).getCompileOptions();
    }

    public @NotNull CompileOptions setCompileOptions(
            @NotNull String moduleId,
            @NotNull CompileOptions compileOptions
    ) {
        return requireModuleState(moduleId).setCompileOptions(compileOptions);
    }

    public @NotNull Map<String, String> getTopLevelCanonicalNameMap(@NotNull String moduleId) {
        return requireModuleState(moduleId).getTopLevelCanonicalNameMap();
    }

    public @NotNull Map<String, String> setTopLevelCanonicalNameMap(
            @NotNull String moduleId,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        return requireModuleState(moduleId).setTopLevelCanonicalNameMap(topLevelCanonicalNameMap);
    }

    public @Nullable CompileResult getLastCompileResult(@NotNull String moduleId) {
        return requireModuleState(moduleId).getLastCompileResult();
    }

    /// Starts one asynchronous compile on a fresh virtual thread and returns the task ID. The
    /// caller must poll `getCompileTask(...)` for progress and completion.
    public long compile(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var moduleState = requireModuleState(normalizedModuleId);
        var taskId = nextCompileTaskId.getAndIncrement();
        var existingTaskId = activeCompileTasksByModule.putIfAbsent(normalizedModuleId, taskId);
        if (existingTaskId != null) {
            throw new ApiCompileAlreadyRunningException(
                    "Module '" + normalizedModuleId + "' already has active compile task " + existingTaskId
            );
        }

        ModuleState.CompileRequest request;
        try {
            request = moduleState.freezeCompileRequest();
        } catch (RuntimeException exception) {
            activeCompileTasksByModule.remove(normalizedModuleId, taskId);
            throw exception;
        }

        var taskState = new CompileTaskState(taskId, normalizedModuleId, clock.instant());
        compileTasks.put(taskId, taskState);
        try {
            Thread.ofVirtual()
                    .name("gdcc-api-compile-" + taskId)
                    .start(() -> runCompileTask(taskState, normalizedModuleId, moduleState, request));
        } catch (RuntimeException exception) {
            compileTasks.remove(taskId);
            activeCompileTasksByModule.remove(normalizedModuleId, taskId);
            throw exception;
        }
        return taskId;
    }

    /// Returns the latest snapshot for one compile task started by `compile(...)`.
    public @NotNull CompileTaskSnapshot getCompileTask(long taskId) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        var taskState = compileTasks.get(taskId);
        if (taskState == null) {
            throw new ApiCompileTaskNotFoundException("Compile task '" + taskId + "' does not exist");
        }
        return taskState.snapshot();
    }

    private @NotNull ModuleState requireModuleState(@NotNull String moduleId) {
        var normalizedModuleId = normalizeModuleId(moduleId);
        var moduleState = modules.get(normalizedModuleId);
        if (moduleState == null) {
            throw new ApiModuleNotFoundException("Module '" + normalizedModuleId + "' does not exist");
        }
        return moduleState;
    }

    private @NotNull String normalizeModuleId(@NotNull String moduleId) {
        return StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
    }

    /// Compilation runs against a frozen module snapshot so RPC writes after `compile(...)` starts
    /// cannot mutate the exact source set or compile options consumed by this pipeline.
    private @NotNull CompileResult compileFrozenRequest(@NotNull ModuleState.CompileRequest request) {
        var sourcePaths = displaySourcePaths(request);
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
                    List.of()
            );
        }

        var diagnostics = new DiagnosticManager();
        var units = request.sourceSnapshots().stream()
                .map(sourceSnapshot -> parserService.parseUnit(
                        sourceSnapshot.logicalPath(),
                        sourceSnapshot.source(),
                        diagnostics
                ))
                .toList();
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
            codegen.prepare(
                    new CodegenContext(projectInfo, classRegistry, request.compileOptions().strictMode()),
                    lowered
            );

            var buildResult = projectBuilder.buildProject(projectInfo, codegen);
            var generatedFiles = collectGeneratedFiles(projectPath);
            return new CompileResult(
                    buildResult.success() ? CompileResult.Outcome.SUCCESS : CompileResult.Outcome.BUILD_FAILED,
                    request.compileOptions(),
                    request.topLevelCanonicalNameMap(),
                    sourcePaths,
                    frontendDiagnostics,
                    buildResult.success() ? null : "Native build reported failure; see buildLog for details",
                    buildResult.buildLog(),
                    generatedFiles,
                    buildResult.artifacts()
            );
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
            @NotNull ModuleState ownerState,
            @NotNull ModuleState.CompileRequest request
    ) {
        try {
            var result = compileFrozenRequest(request);
            finishCompileTask(taskState, normalizedModuleId, ownerState, result);
        } catch (Throwable throwable) {
            finishCompileTask(taskState, normalizedModuleId, ownerState, unexpectedTaskFailure(request, throwable));
        }
    }

    private void finishCompileTask(
            @NotNull CompileTaskState taskState,
            @NotNull String normalizedModuleId,
            @NotNull ModuleState ownerState,
            @NotNull CompileResult result
    ) {
        taskState.complete(clock.instant(), result);
        if (modules.get(normalizedModuleId) == ownerState) {
            ownerState.setLastCompileResult(result);
        }
        activeCompileTasksByModule.remove(normalizedModuleId, taskState.taskId());
    }

    private @NotNull CompileResult unexpectedTaskFailure(
            @NotNull ModuleState.CompileRequest request,
            @NotNull Throwable throwable
    ) {
        var projectPath = request.compileOptions().projectPath();
        var failureMessage = "Compile task failed unexpectedly: " + describeThrowable(throwable);
        return new CompileResult(
                CompileResult.Outcome.BUILD_FAILED,
                request.compileOptions(),
                request.topLevelCanonicalNameMap(),
                displaySourcePaths(request),
                EMPTY_DIAGNOSTICS,
                failureMessage,
                failureMessage,
                projectPath == null ? List.of() : collectGeneratedFiles(projectPath),
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

    private static final class CompileTaskState {
        private final long taskId;
        private final @NotNull String moduleId;
        private final @NotNull Instant createdAt;
        private volatile @NotNull CompileTaskSnapshot snapshot;

        private CompileTaskState(long taskId, @NotNull String moduleId, @NotNull Instant createdAt) {
            this.taskId = taskId;
            this.moduleId = moduleId;
            this.createdAt = createdAt;
            snapshot = new CompileTaskSnapshot(taskId, moduleId, CompileTaskSnapshot.State.RUNNING, createdAt, null, null);
        }

        private long taskId() {
            return taskId;
        }

        private @NotNull CompileTaskSnapshot snapshot() {
            return snapshot;
        }

        private synchronized void complete(@NotNull Instant completedAt, @NotNull CompileResult result) {
            snapshot = new CompileTaskSnapshot(
                    taskId,
                    moduleId,
                    result.success() ? CompileTaskSnapshot.State.SUCCEEDED : CompileTaskSnapshot.State.FAILED,
                    createdAt,
                    completedAt,
                    result
            );
        }
    }
}
