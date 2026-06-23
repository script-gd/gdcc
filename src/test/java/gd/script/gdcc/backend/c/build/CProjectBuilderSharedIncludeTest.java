package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdVoidType;
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
    private static final Path STALE_VENDOR_RUNTIME_SOURCE = Path.of("gdextension-lite", "gdextension-lite-one.c");

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
        assertTrue(Files.isRegularFile(sharedIncludeDir.resolve("gdcc/gdcc_builtin_ctor.h")));
        assertTrue(Files.isRegularFile(sharedIncludeDir.resolve("gdcc/gdcc_intrinsic.h")));
        assertTrue(Files.isRegularFile(sharedIncludeDir.resolve("godot/godot_interface.h")));
        assertTrue(Files.isRegularFile(sharedIncludeDir.resolve("godot/godot_fixed_binding.h")));
        assertTrue(Files.isRegularFile(sharedIncludeDir.resolve("godot/godot_binding.c")));
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
        var staleVendorFile = sharedIncludeDir.resolve(STALE_VENDOR_RUNTIME_SOURCE);
        Files.createDirectories(staleVendorFile.getParent());
        Files.writeString(staleVendorFile, "historical vendor runtime");
        Files.writeString(projectDir.resolve("stale.c"), "stale");
        var result = builder.buildProject(projectInfo, prepareCodegen(projectInfo));

        var expectedGdcc = sharedIncludeDir.toAbsolutePath().normalize().resolve("gdcc");
        var expectedGodot = sharedIncludeDir.toAbsolutePath().normalize().resolve("godot");
        var expectedGeneratedFiles = List.of(
                projectDir.resolve("entry.c").toAbsolutePath().normalize(),
                projectDir.resolve("engine_method_binds.h").toAbsolutePath().normalize(),
                projectDir.resolve("entry.h").toAbsolutePath().normalize()
        );

        assertTrue(result.success());
        assertEquals(expectedGeneratedFiles, result.generatedFiles().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList());
        assertEquals(List.of(expectedGdcc, expectedGodot), compiler.includeDirs());
        assertTrue(compiler.cFiles().contains(projectDir.resolve("entry.c").toAbsolutePath().normalize()));
        assertTrue(compiler.cFiles().contains(expectedGodot.resolve("godot_binding.c")));
        assertFalse(compiler.cFiles().contains(staleVendorFile.toAbsolutePath().normalize()));
        assertFalse(compiler.cFiles().contains(projectDir.resolve("stale.c").toAbsolutePath().normalize()));
        assertFalse(compiler.includeDirs().contains(projectDir.toAbsolutePath().normalize()));
    }

    @Test
    public void buildProjectWithModuleLocalBindingKeepsNativeInputsStable(@TempDir Path tempDir) throws IOException {
        var workspaceDir = tempDir.resolve("workspace");
        var projectDir = workspaceDir.resolve("project-a");
        var sharedIncludeDir = workspaceDir.resolve("shared-include");
        Files.createDirectories(projectDir);
        Files.createDirectories(sharedIncludeDir);

        var projectInfo = new CProjectInfo("testproj", GodotVersion.V451, projectDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        var compiler = new CapturingCompiler();
        var builder = new ModuleLocalGodotBindingFixtureProjectBuilder(compiler);

        builder.initProject(projectInfo);
        var staleVendorFile = sharedIncludeDir.resolve(STALE_VENDOR_RUNTIME_SOURCE);
        Files.createDirectories(staleVendorFile.getParent());
        Files.writeString(staleVendorFile, "historical vendor runtime");
        Files.writeString(projectDir.resolve("extra.c"), "stale generated c");
        var result = builder.buildProject(projectInfo, prepareCodegenWithFunction(projectInfo));

        var expectedGdcc = sharedIncludeDir.toAbsolutePath().normalize().resolve("gdcc");
        var expectedGodot = sharedIncludeDir.toAbsolutePath().normalize().resolve("godot");
        var expectedGeneratedFiles = List.of(
                projectDir.resolve("entry.c").toAbsolutePath().normalize(),
                projectDir.resolve("engine_method_binds.h").toAbsolutePath().normalize(),
                projectDir.resolve("entry.h").toAbsolutePath().normalize()
        );

        assertTrue(result.success());
        assertEquals(expectedGeneratedFiles, result.generatedFiles().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList());
        assertEquals(List.of(expectedGdcc, expectedGodot), compiler.includeDirs());
        assertEquals(
                List.of(
                        projectDir.resolve("entry.c").toAbsolutePath().normalize(),
                        expectedGodot.resolve("godot_binding.c")
                ),
                compiler.cFiles()
        );
        assertFalse(compiler.cFiles().contains(staleVendorFile.toAbsolutePath().normalize()));
        assertFalse(compiler.cFiles().contains(projectDir.resolve("extra.c").toAbsolutePath().normalize()));
        assertFalse(compiler.includeDirs().contains(projectDir.toAbsolutePath().normalize()));

        var bindHeader = Files.readString(projectDir.resolve("engine_method_binds.h"));
        assertTrue(bindHeader.contains("static inline godot_int godot_Probe_READY(void)"), bindHeader);
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

        assertTrue(Files.isRegularFile(projectDir.resolve("include/gdcc/gdcc_builtin_ctor.h")));
        assertTrue(Files.isRegularFile(projectDir.resolve("include/gdcc/gdcc_helper.h")));
        assertTrue(Files.isRegularFile(projectDir.resolve("include/gdcc/gdcc_intrinsic.h")));
        assertTrue(Files.isRegularFile(projectDir.resolve("include/godot/godot_binding.c")));
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

        assertTrue(Files.isRegularFile(projectDir.resolve("include/gdcc/gdcc_builtin_ctor.h")));
        assertTrue(Files.isRegularFile(projectDir.resolve("include/gdcc/gdcc_helper.h")));
        assertTrue(Files.isRegularFile(projectDir.resolve("include/gdcc/gdcc_intrinsic.h")));
        assertTrue(Files.isRegularFile(projectDir.resolve("include/godot/godot_binding.c")));
        assertFalse(Files.exists(sharedIncludePath.resolve("gdcc/gdcc_helper.h")));
    }

    private static @NotNull CCodegen prepareCodegen(@NotNull CProjectInfo projectInfo) throws IOException {
        var codegen = new CCodegen();
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var context = new CodegenContext(projectInfo, new ClassRegistry(api));
        codegen.prepare(context, new LirModule(projectInfo.projectName(), List.of()));
        return codegen;
    }

    private static @NotNull CCodegen prepareCodegenWithFunction(@NotNull CProjectInfo projectInfo) throws IOException {
        var codegen = new CCodegen();
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var context = new CodegenContext(projectInfo, new ClassRegistry(api));
        var clazz = new LirClassDef("ModuleLocalBuildInputSmoke", "RefCounted");
        var function = new LirFunctionDef("touch_module_local");
        function.setHidden(true);
        function.setReturnType(GdVoidType.VOID);
        var entry = new LirBasicBlock("entry");
        entry.setTerminator(new ReturnInsn(null));
        function.addBasicBlock(entry);
        function.setEntryBlockId("entry");
        clazz.addFunction(function);
        codegen.prepare(context, new LirModule(projectInfo.projectName(), List.of(clazz)));
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
