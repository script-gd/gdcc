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
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CProjectBuilderPlaceHolderTest {
    @Test
    public void buildsUsingInjectedZigCompiler(@TempDir Path tempDir) throws IOException {
        // prepare project info
        var projectInfo = new CProjectInfo("testproj", GodotVersion.V451, tempDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        // create builder with fake CCompiler that simulates successful compilation
        var fakeCompiler = new CCompiler() {
            @Override
            public CCompileResult compile(@NotNull Path projectDir, @NotNull List<Path> includeDirs, @NotNull List<Path> cFiles, @NotNull String outputBaseName, @NotNull COptimizationLevel optimizationLevel, @NotNull TargetPlatform targetPlatform) throws IOException {
                // simulate writing an output file
                var out = projectDir.resolve("testproj.dll");
                Files.writeString(out, "dummy");
                return new CCompileResult(true, "ok", List.of(out));
            }
        };

        var builder = new CProjectBuilder(fakeCompiler);

        // init project (should extract includes into tempDir/include)
        builder.initProject(projectInfo);
        assertTrue(Files.exists(tempDir.resolve("include")));

        // Use a simple CCodegen that generates a trivial .c file by delegating to existing CCodegen
        var codegen = new CCodegen();
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var ctx = new CodegenContext(projectInfo, new ClassRegistry(api));
        var module = new LirModule(projectInfo.projectName(), List.of());
        codegen.prepare(ctx, module);
        var result = builder.buildProject(projectInfo, codegen);
        assertTrue(result.success());
        assertEquals(1, result.artifacts().size());
        assertTrue(Files.exists(result.artifacts().getFirst()));
    }
}
