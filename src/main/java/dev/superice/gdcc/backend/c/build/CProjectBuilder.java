package dev.superice.gdcc.backend.c.build;

import dev.superice.gdcc.backend.ProjectBuilder;
import dev.superice.gdcc.backend.c.gen.CCodegen;
import dev.superice.gdcc.util.ResourceExtractor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CProjectBuilder implements ProjectBuilder<CProjectInfo, CCodegen, CBuildResult> {
    private static final String INCLUDE_RESOURCE_DIR = "include_451";
    private static final String PROJECT_INCLUDE_DIR_NAME = "include";
    private static final String SHARED_INCLUDE_DIR_NAME = "shared-include";

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
        ResourceExtractor.extract(INCLUDE_RESOURCE_DIR, includeRoot, getClass().getClassLoader());
    }

    @Override
    public CBuildResult buildProject(@NotNull CProjectInfo projectInfo, @NotNull CCodegen codegen) throws IOException {
        var projectPath = projectInfo.projectPath();
        var includeRoot = resolveIncludeRoot(projectPath);
        ResourceExtractor.extract(INCLUDE_RESOURCE_DIR, includeRoot, getClass().getClassLoader());

        // Generate files
        var generated = codegen.generate();

        // Save generated files into project root
        for (var gf : generated) {
            gf.saveTo(projectPath);
        }

        // Gather .c files to compile: all generated .c plus gdextension-lite-one.c from extracted include
        var cFiles = new ArrayList<Path>();
        for (var gf : generated) {
            if (gf.filePath().endsWith(".c")) {
                cFiles.add(projectPath.resolve(gf.filePath()));
            }
        }

        // gdextension-lite-one.c should be under <includeRoot>/gdextension-lite/gdextension-lite-one.c
        var gdextOne = includeRoot.resolve("gdextension-lite").resolve("gdextension-lite-one.c");
        if (Files.exists(gdextOne)) cFiles.add(gdextOne);

        // include dir
        var includeDirs = List.of(
                includeRoot.resolve("gdcc"),
                includeRoot.resolve("gdextension-lite")
        );

        // output name: projectName
        var outputName = projectInfo.projectName() + "_" + projectInfo.getOptimizationLevel().name().toLowerCase() +
                "_" + projectInfo.getTargetPlatform().architecture.name().toLowerCase();

        // optimization level and platform from projectInfo
        var opt = projectInfo.getOptimizationLevel();
        var tp = projectInfo.getTargetPlatform();

        // Delegate compile to CCompiler and return
        return cCompiler.compile(projectPath, includeDirs, cFiles, outputName, opt, tp);
    }

    private @NotNull Path resolveIncludeRoot(@NotNull Path projectPath) throws IOException {
        var normalizedProjectPath = projectPath.toAbsolutePath().normalize();
        if (ignoreSharedInclude) {
            return normalizedProjectPath.resolve(PROJECT_INCLUDE_DIR_NAME);
        }

        var projectParent = normalizedProjectPath.getParent();
        if (projectParent == null) {
            return normalizedProjectPath.resolve(PROJECT_INCLUDE_DIR_NAME);
        }

        var sharedInclude = projectParent.resolve(SHARED_INCLUDE_DIR_NAME);
        if (Files.exists(sharedInclude) && !Files.isDirectory(sharedInclude)) {
            throw new IOException("Shared include path exists but is not a directory: " + sharedInclude);
        }

        if (Files.isDirectory(sharedInclude)) {
            return sharedInclude;
        }
        return normalizedProjectPath.resolve(PROJECT_INCLUDE_DIR_NAME);
    }
}
