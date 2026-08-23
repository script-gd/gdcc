package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors the coroutine runtime compile wiring contract
/// (doc/gdcc_runtime_lib.md §Coroutine Runtime): `gdcc/minicoro.c` and
/// `gdcc/gdcc_coroutine.c` are extracted with the `gdcc/**` tree and appended to the
/// native compiler inputs right after `godot/godot_binding.c`.
public class CProjectBuilderCoroutineRuntimeInputTest {

    @Test
    public void buildProjectAppendsCoroutineRuntimeSourcesAfterGodotBinding(@TempDir Path tempDir) throws IOException {
        var projectDir = tempDir.resolve("project-a");
        Files.createDirectories(projectDir);

        var projectInfo = new CProjectInfo("testproj", GodotVersion.V451, projectDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());
        var compiler = new CapturingCompiler();
        var builder = new CProjectBuilder(compiler);

        var result = builder.buildProject(projectInfo, prepareCodegen(projectInfo));

        var includeRoot = projectDir.resolve("include").toAbsolutePath().normalize();
        var expectedGdcc = includeRoot.resolve("gdcc");
        assertTrue(result.success());
        // The runtime sources must actually exist in the extracted include tree.
        assertTrue(Files.isRegularFile(expectedGdcc.resolve("minicoro.c")));
        assertTrue(Files.isRegularFile(expectedGdcc.resolve("gdcc_coroutine.c")));
        assertTrue(Files.isRegularFile(expectedGdcc.resolve("minicoro.h")));
        assertTrue(Files.isRegularFile(expectedGdcc.resolve("gdcc_coroutine.h")));
        // ...and enter the native compiler inputs in a fixed order right after godot_binding.c.
        assertEquals(
                List.of(
                        projectDir.resolve("entry.c").toAbsolutePath().normalize(),
                        includeRoot.resolve("godot/godot_binding.c"),
                        expectedGdcc.resolve("minicoro.c"),
                        expectedGdcc.resolve("gdcc_coroutine.c")
                ),
                compiler.cFiles()
        );
    }

    private static @NotNull CCodegen prepareCodegen(@NotNull CProjectInfo projectInfo) throws IOException {
        var codegen = new CCodegen();
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var context = new CodegenContext(projectInfo, new ClassRegistry(api));
        codegen.prepare(context, new LirModule(projectInfo.projectName(), List.of()));
        return codegen;
    }

    private static final class CapturingCompiler implements CCompiler {
        private List<Path> cFiles = List.of();

        @Override
        public CCompileResult compile(@NotNull Path projectDir, @NotNull List<Path> includeDirs, @NotNull List<Path> cFiles, @NotNull String outputBaseName, @NotNull COptimizationLevel optimizationLevel, @NotNull TargetPlatform targetPlatform) throws IOException {
            this.cFiles = cFiles.stream().map(path -> path.toAbsolutePath().normalize()).toList();
            var artifact = projectDir.resolve(targetPlatform.sharedLibraryFileName(outputBaseName));
            Files.writeString(artifact, "dummy");
            return new CCompileResult(true, "ok", List.of(artifact));
        }

        private @NotNull List<Path> cFiles() {
            return cFiles;
        }
    }
}
