package dev.superice.gdcc.backend.c.build;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.ProjectBuilder;
import dev.superice.gdcc.backend.c.gen.CCodegen;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.util.ResourceExtractor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CProjectBuilder implements ProjectBuilder<CProjectInfo, CCodegen, CBuildResult> {
    private CCompiler cCompiler;

    public CProjectBuilder() {
        this.cCompiler = new ZigCcCompiler();
    }

    // For tests - allow injecting a fake/compiler wrapper
    public CProjectBuilder(@NotNull CCompiler cCompiler) {
        this.cCompiler = cCompiler;
    }

    public CCompiler getCCompiler() {
        return cCompiler;
    }

    public void setCCompiler(CCompiler cCompiler) {
        this.cCompiler = cCompiler;
    }

    @Override
    public void initProject(@NotNull CProjectInfo projectInfo) throws IOException {
        var projectPath = projectInfo.projectPath();
        var includeDir = projectPath.resolve("include");
        // extract all files under resource folder include_451 into project include dir
        ResourceExtractor.extract("include_451", includeDir, getClass().getClassLoader());
    }

    @Override
    public CBuildResult buildProject(@NotNull CProjectInfo projectInfo, @NotNull CCodegen codegen) throws IOException {
        var projectPath = projectInfo.projectPath();

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

        // gdextension-lite-one.c should be under include/gdextension-lite/gdextension-lite-one.c
        var gdextOne = projectPath.resolve("include").resolve("gdextension-lite").resolve("gdextension-lite-one.c");
        if (Files.exists(gdextOne)) cFiles.add(gdextOne);

        // include dir
        var includeDirs = List.of(
                projectPath.resolve("include/gdcc"),
                projectPath.resolve("include/gdextension-lite")
        );

        // output name: projectName
        var outputName = projectInfo.projectName();

        // optimization level and platform from projectInfo
        var opt = projectInfo.getOptimizationLevel();
        var tp = projectInfo.getTargetPlatform();

        // Delegate compile to CCompiler and return
        return cCompiler.compile(projectPath, includeDirs, cFiles, outputName, opt, tp);
    }
}
