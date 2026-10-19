package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.ProjectBuilder;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.util.ResourceExtractor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static java.time.Duration.ofNanos;

public class CProjectBuilder implements ProjectBuilder<CProjectInfo, CCodegen, CBuildResult> {
    private static final String INCLUDE_RESOURCE_DIR = "include_451";
    private static final String PROJECT_INCLUDE_DIR_NAME = "include";
    private static final String SHARED_INCLUDE_DIR_NAME = "shared-include";
    static final String SHARED_INCLUDE_ENV = "GDCC_SHARED_INCLUDE";
    private static final String GDCC_INCLUDE_DIR_NAME = "gdcc";
    private static final String GODOT_INCLUDE_DIR_NAME = "godot";
    /// Package-visible so incremental-build tests mirror the exact runtime TU list instead of
    /// hardcoding a copy that silently drifts when runtime sources are added or removed.
    static final String GODOT_RUNTIME_SOURCE_PATH = GODOT_INCLUDE_DIR_NAME + "/godot_binding.c";
    /// GDCC-owned runtime translation units (coroutine + hot-reload thunk support). Extracted
    /// with the rest of the `gdcc/**` tree and compiled alongside `godot_binding.c`.
    /// Contract: doc/gdcc_runtime_lib.md §Coroutine Runtime, §HRX (gdcc_hrx).
    static final List<String> GDCC_RUNTIME_SOURCE_PATHS = List.of(
            GDCC_INCLUDE_DIR_NAME + "/minicoro.c",
            GDCC_INCLUDE_DIR_NAME + "/gdcc_coroutine.c",
            GDCC_INCLUDE_DIR_NAME + "/gdcc_hrx.c"
    );

    /// Backend availability probes are deliberately cheap and side-effect-free: they never spawn
    /// a process or a compile, never install a bundle or load native code, and never touch the
    /// caller's interrupt status, so UI/backend pickers can poll freely. Each probe answers
    /// "could a build with this backend start right now", not "will it succeed".
    private static final Map<CCompilerKind, BooleanSupplier> AVAILABILITY_PROBES = Map.of(
            CCompilerKind.ZIG_CC, ZigUtil::isZigDiscoverable,
            CCompilerKind.TINY_CC, TinyCcBundle::isHostBundlePresent
    );

    /// One compiler per kind, consulted per build round via [CProjectInfo.getCCompilerKind].
    /// Selection is immutable per round: this single builder serves all concurrent tasks, so a
    /// mutable compiler slot would race between rounds. Unused in the forced-compiler shape.
    private final @NotNull EnumMap<CCompilerKind, CCompiler> compilers;
    /// Test/embedding shape: a single compiler that every build uses regardless of the requested
    /// kind. `null` selects the dispatching shape.
    private final @Nullable CCompiler forcedCompiler;
    private boolean ignoreSharedInclude;
    private final @NotNull Map<String, String> environment;

    public CProjectBuilder() {
        this(defaultCompilers(), System.getenv());
    }

    public CProjectBuilder(@NotNull CCompiler cCompiler) {
        this(cCompiler, System.getenv());
    }

    CProjectBuilder(@NotNull CCompiler cCompiler, @NotNull Map<String, String> environment) {
        forcedCompiler = Objects.requireNonNull(cCompiler, "cCompiler must not be null");
        compilers = new EnumMap<>(CCompilerKind.class);
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        ignoreSharedInclude = false;
    }

    /// Dispatching-shape seam: the map must cover every [CCompilerKind] so a build can never
    /// reach dispatch with an unregistered kind. Tests inject fakes here to observe dispatch.
    CProjectBuilder(@NotNull Map<CCompilerKind, CCompiler> compilers, @NotNull Map<String, String> environment) {
        this.compilers = new EnumMap<>(CCompilerKind.class);
        for (var kind : CCompilerKind.values()) {
            this.compilers.put(
                    kind,
                    Objects.requireNonNull(compilers.get(kind), "compilers must contain an entry for " + kind)
            );
        }
        forcedCompiler = null;
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        ignoreSharedInclude = false;
    }

    private static @NotNull Map<CCompilerKind, CCompiler> defaultCompilers() {
        // Both backends are constructed eagerly; their expensive parts (zig discovery, bundle
        // resolution, native loading) stay lazy and only run for the backend a round selects.
        var compilers = new EnumMap<CCompilerKind, CCompiler>(CCompilerKind.class);
        compilers.put(CCompilerKind.ZIG_CC, new ZigCcCompiler());
        compilers.put(CCompilerKind.TINY_CC, new TinyCcCompiler());
        return compilers;
    }

    /// The backends that could start a build on this host right now, in enum declaration order.
    public static @NotNull List<CCompilerKind> availableCCompilerKinds() {
        return availableCCompilerKinds(AVAILABILITY_PROBES);
    }

    static @NotNull List<CCompilerKind> availableCCompilerKinds(
            @NotNull Map<CCompilerKind, BooleanSupplier> availabilityProbes
    ) {
        var available = new ArrayList<CCompilerKind>();
        for (var kind : CCompilerKind.values()) {
            var probe = availabilityProbes.get(kind);
            if (probe != null && probe.getAsBoolean()) {
                available.add(kind);
            }
        }
        return List.copyOf(available);
    }

    public boolean isIgnoreSharedInclude() {
        return ignoreSharedInclude;
    }

    public void setIgnoreSharedInclude(boolean ignoreSharedInclude) {
        this.ignoreSharedInclude = ignoreSharedInclude;
    }

    @Override
    public void initProject(@NotNull CProjectInfo projectInfo) throws IOException {
        var projectPath = projectInfo.projectPath();
        var includeRoot = resolveIncludeRoot(projectPath);
        extractRuntimeIncludes(includeRoot);
    }

    @Override
    public CBuildResult buildProject(@NotNull CProjectInfo projectInfo, @NotNull CCodegen codegen) throws IOException {
        var totalStart = System.nanoTime();
        var projectPath = projectInfo.projectPath();
        var includeRoot = resolveIncludeRoot(projectPath);
        var includeStart = System.nanoTime();
        extractRuntimeIncludes(includeRoot);
        var includeDuration = elapsedSince(includeStart);

        var codegenStart = System.nanoTime();
        var generated = codegen.generate();
        var codegenDuration = elapsedSince(codegenStart);

        var generatedFileWriteStart = System.nanoTime();
        var generatedFiles = new ArrayList<Path>(generated.size());
        // These writes are deliberately outside the per-project build lock: the compiler's
        // lock-held cache-key hashing assumes generated files do not change mid-round, which
        // holds because a project output directory is built by one task at a time. Two tasks
        // targeting the same projectPath concurrently could publish an object compiled from a
        // newer write under the older content's cache key — an accepted risk, not guarded
        // against.
        for (var gf : generated) {
            generatedFiles.add(gf.saveTo(projectPath));
        }
        var generatedFileWriteDuration = elapsedSince(generatedFileWriteStart);

        var compileInputStart = System.nanoTime();
        // Native compiler inputs are exactly this codegen pass plus the fixed runtime sources.
        // ResourceExtractor intentionally leaves stale files alone, so old vendor files are never scanned.
        var cFiles = new ArrayList<Path>();
        for (var generatedFile : generatedFiles) {
            if (generatedFile.getFileName().toString().endsWith(".c")) {
                cFiles.add(generatedFile);
            }
        }

        var godotRuntimeSource = includeRoot.resolve(GODOT_RUNTIME_SOURCE_PATH);
        if (!Files.isRegularFile(godotRuntimeSource)) {
            throw new IOException("Required Godot runtime binding source is missing: " + godotRuntimeSource);
        }
        cFiles.add(godotRuntimeSource);
        for (var gdccRuntimeSourcePath : GDCC_RUNTIME_SOURCE_PATHS) {
            var gdccRuntimeSource = includeRoot.resolve(gdccRuntimeSourcePath);
            if (!Files.isRegularFile(gdccRuntimeSource)) {
                throw new IOException("Required GDCC runtime source is missing: " + gdccRuntimeSource);
            }
            cFiles.add(gdccRuntimeSource);
        }

        // include dir
        var includeDirs = List.of(
                includeRoot.resolve(GDCC_INCLUDE_DIR_NAME),
                includeRoot.resolve(GODOT_INCLUDE_DIR_NAME)
        );

        // output name: projectName
        var outputName = projectInfo.projectName() + "_" + projectInfo.getOptimizationLevel().name().toLowerCase() +
                "_" + projectInfo.getTargetPlatform().architecture.name().toLowerCase();

        // optimization level and platform from projectInfo
        var opt = projectInfo.getOptimizationLevel();
        var tp = projectInfo.getTargetPlatform();

        var compileInputCollectionDuration = elapsedSince(compileInputStart);
        var nativeCompileStart = System.nanoTime();
        // The forced shape deliberately ignores the requested kind: pinning a compiler must pin
        // it for every project info, which is what existing single-compiler callers rely on.
        var compiler = forcedCompiler != null ? forcedCompiler : compilers.get(projectInfo.getCCompilerKind());
        var compileResult = compiler.compile(projectPath, includeDirs, cFiles, outputName, opt, tp, projectInfo.isStripNativeSymbols());
        var nativeCompileDuration = elapsedSince(nativeCompileStart);
        var timing = new CBuildResult.Timing(
                includeDuration,
                codegenDuration,
                generatedFileWriteDuration,
                compileInputCollectionDuration,
                nativeCompileDuration,
                elapsedSince(totalStart)
        );
        return new CBuildResult(compileResult, generatedFiles, timing);
    }

    @NotNull Path resolveIncludeRoot(@NotNull Path projectPath) {
        return resolveIncludeRoot(projectPath, ignoreSharedInclude, environment);
    }

    static @NotNull Path resolveIncludeRoot(
            @NotNull Path projectPath,
            boolean ignoreSharedInclude,
            @NotNull Map<String, String> environment
    ) {
        var normalizedProjectPath = projectPath.toAbsolutePath().normalize();
        if (ignoreSharedInclude) {
            return normalizedProjectPath.resolve(PROJECT_INCLUDE_DIR_NAME);
        }

        var envIncludeValue = environment.get(SHARED_INCLUDE_ENV);
        if (envIncludeValue != null && !envIncludeValue.isBlank()) {
            try {
                var envIncludeDir = Path.of(envIncludeValue).toAbsolutePath().normalize();
                Files.createDirectories(envIncludeDir);
                if (Files.isDirectory(envIncludeDir)) {
                    return envIncludeDir;
                }
            } catch (IOException | InvalidPathException exception) {
                // Fall back to the sibling/project-local include rule below.
            }
        }

        var projectParent = normalizedProjectPath.getParent();
        if (projectParent == null) {
            return normalizedProjectPath.resolve(PROJECT_INCLUDE_DIR_NAME);
        }

        var sharedInclude = projectParent.resolve(SHARED_INCLUDE_DIR_NAME);
        if (Files.isDirectory(sharedInclude)) {
            return sharedInclude.toAbsolutePath().normalize();
        }
        return normalizedProjectPath.resolve(PROJECT_INCLUDE_DIR_NAME);
    }

    private void extractRuntimeIncludes(@NotNull Path includeRoot) throws IOException {
        var loader = getClass().getClassLoader();
        var resources = ResourceExtractor.listResourceFilesRecursively(INCLUDE_RESOURCE_DIR, loader).stream()
                .filter(resource -> resource.startsWith(GDCC_INCLUDE_DIR_NAME + "/")
                        || resource.startsWith(GODOT_INCLUDE_DIR_NAME + "/"))
                .toList();
        ResourceExtractor.extractSpecific(INCLUDE_RESOURCE_DIR, resources, includeRoot, loader);
    }

    private static @NotNull Duration elapsedSince(long startNanos) {
        return ofNanos(System.nanoTime() - startNanos);
    }
}
