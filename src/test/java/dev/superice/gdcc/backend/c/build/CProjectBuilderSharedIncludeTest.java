package dev.superice.gdcc.backend.c.build;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.gen.CCodegen;
import dev.superice.gdcc.enums.GodotVersion;
import dev.superice.gdcc.gdextension.ExtensionApiLoader;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CProjectBuilderSharedIncludeTest {

    @Test
    public void initProjectSyncsSharedIncludeAndSkipsProjectInclude(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var sharedIncludeDir = workspaceDir.resolve("shared-include");
        Files.createDirectories(projectDir);
        Files.createDirectories(sharedIncludeDir.resolve("gdcc"));
        Files.writeString(sharedIncludeDir.resolve("gdcc/gdcc_helper.h"), "BROKEN");

        var projectInfo = new CProjectInfo("testproj", GodotVersion.V451, projectDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        var builder = new CProjectBuilder();

        builder.initProject(projectInfo);

        assertFalse(Files.exists(projectDir.resolve("include")));
        assertTrue(Files.isRegularFile(sharedIncludeDir.resolve("gdcc/gdcc_bind.h")));
        assertTrue(Files.isRegularFile(sharedIncludeDir.resolve("gdextension-lite/gdextension-lite-one.c")));
        assertNotEquals("BROKEN", Files.readString(sharedIncludeDir.resolve("gdcc/gdcc_helper.h")).trim());
    }

    @Test
    public void buildProjectUsesSharedIncludePaths(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var sharedIncludeDir = workspaceDir.resolve("shared-include");
        Files.createDirectories(projectDir);
        Files.createDirectories(sharedIncludeDir);

        var projectInfo = new CProjectInfo("testproj", GodotVersion.V451, projectDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        var compiler = new CapturingCompiler();
        var builder = new CProjectBuilder(compiler);

        builder.initProject(projectInfo);
        Files.writeString(projectDir.resolve("stale.c"), "stale");
        var result = builder.buildProject(projectInfo, prepareCodegen(projectInfo));

        var expectedGdcc = sharedIncludeDir.toAbsolutePath().normalize().resolve("gdcc");
        var expectedGdextensionLite = sharedIncludeDir.toAbsolutePath().normalize().resolve("gdextension-lite");
        var expectedGeneratedFiles = List.of(
                projectDir.resolve("entry.c").toAbsolutePath().normalize(),
                projectDir.resolve("engine_method_binds.h").toAbsolutePath().normalize(),
                projectDir.resolve("entry.h").toAbsolutePath().normalize()
        );

        assertTrue(result.success());
        assertEquals(expectedGeneratedFiles, result.generatedFiles().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList());
        assertEquals(List.of(expectedGdcc, expectedGdextensionLite), compiler.includeDirs());
        assertTrue(compiler.cFiles().contains(projectDir.resolve("entry.c").toAbsolutePath().normalize()));
        assertTrue(compiler.cFiles().contains(expectedGdextensionLite.resolve("gdextension-lite-one.c")));
        assertFalse(compiler.cFiles().contains(projectDir.resolve("stale.c").toAbsolutePath().normalize()));
    }

    @Test
    public void ignoreSharedIncludeForcesProjectLocalInclude(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var sharedIncludeDir = workspaceDir.resolve("shared-include");
        Files.createDirectories(projectDir);
        Files.createDirectories(sharedIncludeDir);

        var projectInfo = new CProjectInfo("testproj", GodotVersion.V451, projectDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        var builder = new CProjectBuilder();
        builder.setIgnoreSharedInclude(true);

        builder.initProject(projectInfo);

        assertTrue(Files.isRegularFile(projectDir.resolve("include/gdcc/gdcc_helper.h")));
        assertFalse(Files.exists(sharedIncludeDir.resolve("gdcc/gdcc_helper.h")));
    }

    @Test
    public void fallsBackToProjectLocalIncludeWhenSharedIncludePathIsAFile(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var sharedIncludePath = workspaceDir.resolve("shared-include");
        Files.createDirectories(projectDir);
        Files.createDirectories(sharedIncludePath.getParent());
        Files.writeString(sharedIncludePath, "not-a-directory");

        var projectInfo = new CProjectInfo("testproj", GodotVersion.V451, projectDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        var builder = new CProjectBuilder();

        builder.initProject(projectInfo);

        assertTrue(Files.isRegularFile(projectDir.resolve("include/gdcc/gdcc_helper.h")));
        assertFalse(Files.exists(sharedIncludePath.resolve("gdcc/gdcc_helper.h")));
    }

    private static @NotNull CCodegen prepareCodegen(@NotNull CProjectInfo projectInfo) throws IOException {
        var codegen = new CCodegen();
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var context = new CodegenContext(projectInfo, new ClassRegistry(api));
        codegen.prepare(context, new LirModule(projectInfo.projectName(), List.of()));
        return codegen;
    }

    private static final class CapturingCompiler implements CCompiler {
        private List<Path> includeDirs = List.of();
        private List<Path> cFiles = List.of();

        @Override
        public CCompileResult compile(@NotNull Path projectDir, @NotNull List<Path> includeDirs, @NotNull List<Path> cFiles, @NotNull String outputBaseName, @NotNull COptimizationLevel optimizationLevel, @NotNull TargetPlatform targetPlatform) throws IOException {
            this.includeDirs = includeDirs.stream().map(path -> path.toAbsolutePath().normalize()).toList();
            this.cFiles = cFiles.stream().map(path -> path.toAbsolutePath().normalize()).toList();

            var artifact = projectDir.resolve(targetPlatform.sharedLibraryFileName(outputBaseName));
            Files.writeString(artifact, "dummy");
            return new CCompileResult(true, "ok", List.of(artifact));
        }

        private @NotNull List<Path> includeDirs() {
            return includeDirs;
        }

        private @NotNull List<Path> cFiles() {
            return cFiles;
        }
    }
}
