package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.ProjectBuilder;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.util.ResourceExtractor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.time.Duration.ofNanos;

public class CProjectBuilder implements ProjectBuilder<CProjectInfo, CCodegen, CBuildResult> {
    private static final String INCLUDE_RESOURCE_DIR = "include_451";
    private static final String PROJECT_INCLUDE_DIR_NAME = "include";
    private static final String SHARED_INCLUDE_DIR_NAME = "shared-include";
    private static final String GDCC_INCLUDE_DIR_NAME = "gdcc";
    private static final String GODOT_INCLUDE_DIR_NAME = "godot";
    private static final String GODOT_RUNTIME_SOURCE_PATH = GODOT_INCLUDE_DIR_NAME + "/godot_binding.c";
    /// GDCC-owned runtime translation units (coroutine support). Extracted with the rest of
    /// the `gdcc/**` tree and compiled alongside `godot_binding.c`.
    /// Contract: doc/gdcc_runtime_lib.md §Coroutine Runtime.
    private static final List<String> GDCC_RUNTIME_SOURCE_PATHS = List.of(
            GDCC_INCLUDE_DIR_NAME + "/minicoro.c",
            GDCC_INCLUDE_DIR_NAME + "/gdcc_coroutine.c"
    );

    private CCompiler cCompiler;
    private boolean ignoreSharedInclude;

    public CProjectBuilder() {
        this.cCompiler = new ZigCcCompiler();
        this.ignoreSharedInclude = false;
    }

    // For tests - allow injecting a fake/compiler wrapper
    public CProjectBuilder(@NotNull CCompiler cCompiler) {
        this.cCompiler = cCompiler;
        this.ignoreSharedInclude = false;
    }

    public CCompiler getCCompiler() {
        return cCompiler;
    }

    public void setCCompiler(CCompiler cCompiler) {
        this.cCompiler = cCompiler;
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
        var compileResult = cCompiler.compile(projectPath, includeDirs, cFiles, outputName, opt, tp);
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

    private @NotNull Path resolveIncludeRoot(@NotNull Path projectPath) {
        var normalizedProjectPath = projectPath.toAbsolutePath().normalize();
        if (ignoreSharedInclude) {
            return normalizedProjectPath.resolve(PROJECT_INCLUDE_DIR_NAME);
        }

        var projectParent = normalizedProjectPath.getParent();
        if (projectParent == null) {
            return normalizedProjectPath.resolve(PROJECT_INCLUDE_DIR_NAME);
        }

        var sharedInclude = projectParent.resolve(SHARED_INCLUDE_DIR_NAME);
        if (Files.isDirectory(sharedInclude)) {
            return sharedInclude;
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
