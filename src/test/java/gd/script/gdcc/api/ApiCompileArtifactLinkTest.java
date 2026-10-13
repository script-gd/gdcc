package gd.script.gdcc.api;

import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiCompileArtifactLinkTest {
    @Test
    void compileMountsGeneratedFilesAndArtifactsIntoConfiguredOutputRoot(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        var projectPath = tempDir.resolve("artifact-link-project");
        var outputMountRoot = "/rpc-build";

        api.createModule("demo", "Artifact Link Demo");
        api.setCompileOptions(
                "demo",
                ApiCompileTestSupport.compileOptions(
                        projectPath,
                        COptimizationLevel.DEBUG,
                        TargetPlatform.getNativePlatform(),
                        false,
                        outputMountRoot
                )
        );
        api.putFile("demo", "/src/main.gd", validSource("ArtifactLinkDemo"));

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));
        var artifact = result.artifacts().getFirst();

        assertEquals(CompileResult.Outcome.SUCCESS, result.outcome());
        assertEquals(
                List.of(
                        outputMountRoot + "/generated/entry.c",
                        outputMountRoot + "/generated/engine_method_binds.h",
                        outputMountRoot + "/generated/object_fat_ptr_types.h",
                        outputMountRoot + "/generated/entry.h",
                        outputMountRoot + "/artifacts/" + artifact.getFileName()
                ),
                result.outputLinks().stream().map(VfsEntrySnapshot.LinkEntrySnapshot::virtualPath).toList()
        );
        assertEquals(
                List.of(
                        projectPath.resolve("entry.c").toString(),
                        projectPath.resolve("engine_method_binds.h").toString(),
                        projectPath.resolve("object_fat_ptr_types.h").toString(),
                        projectPath.resolve("entry.h").toString(),
                        artifact.toString()
                ),
                result.outputLinks().stream().map(VfsEntrySnapshot.LinkEntrySnapshot::target).toList()
        );
        assertTrue(result.outputLinks().stream().allMatch(link -> link.linkKind() == VfsEntrySnapshot.LinkKind.LOCAL));
        assertTrue(result.outputLinks().stream().noneMatch(VfsEntrySnapshot.LinkEntrySnapshot::broken));

        assertEquals(
                List.of("artifacts", "generated"),
                api.listDirectory("demo", outputMountRoot).stream().map(VfsEntrySnapshot::name).toList()
        );
        assertEquals(
                List.of("engine_method_binds.h", "entry.c", "entry.h", "object_fat_ptr_types.h"),
                api.listDirectory("demo", outputMountRoot + "/generated").stream().map(VfsEntrySnapshot::name).toList()
        );
        assertEquals(
                List.of(artifact.getFileName().toString()),
                api.listDirectory("demo", outputMountRoot + "/artifacts").stream().map(VfsEntrySnapshot::name).toList()
        );

        var generatedLink = assertInstanceOf(
                VfsEntrySnapshot.LinkEntrySnapshot.class,
                api.readEntry("demo", outputMountRoot + "/generated/entry.c")
        );
        assertEquals(projectPath.resolve("entry.c").toString(), generatedLink.target());
        assertEquals(VfsEntrySnapshot.LinkKind.LOCAL, generatedLink.linkKind());

        var artifactLink = assertInstanceOf(
                VfsEntrySnapshot.LinkEntrySnapshot.class,
                api.readEntry("demo", outputMountRoot + "/artifacts/" + artifact.getFileName())
        );
        assertEquals(artifact.toString(), artifactLink.target());
    }

    @Test
    void compileFailsClearlyWhenOutputMountRootIsOccupiedByAFile(@TempDir Path tempDir) {
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);

        api.createModule("demo", "Occupied Output Root Demo");
        api.setCompileOptions("demo", ApiCompileTestSupport.compileOptions(tempDir.resolve("occupied-output-root-project")));
        api.putFile("demo", "/src/main.gd", validSource("OccupiedOutputRootDemo"));
        api.putFile("demo", CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT, "occupied");

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.CONFIGURATION_FAILED, result.outcome());
        assertEquals(
                "Compile outputs for module 'demo' could not be mounted: "
                        + "Path '/__build__' in module 'demo' is a file, not a directory",
                result.failureMessage()
        );
        assertTrue(result.outputLinks().isEmpty());
        assertTrue(result.generatedFiles().isEmpty());
        assertTrue(result.artifacts().isEmpty());
        assertEquals(0, compiler.invocationCount());

        var occupiedEntry = assertInstanceOf(
                VfsEntrySnapshot.FileEntrySnapshot.class,
                api.readEntry("demo", CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT)
        );
        assertEquals(CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT, occupiedEntry.virtualPath());
    }

    @Test
    void sourceInsideManagedOutputDirectoryFailsCompileLoudly(@TempDir Path tempDir) {
        // Managed output directories are compiler-owned: cleanup before republication deletes them
        // without advancing contentVersion, which is only sound while they cannot host sources.
        // A `.gd` entry inside must therefore fail the compile loudly instead of being silently
        // deleted at an unchanged version.
        var compiler = ApiCompileTestSupport.RecordingCompiler.succeeding();
        var api = ApiCompileTestSupport.newApi(compiler);
        var projectPath = tempDir.resolve("guarded-project");
        var outputMountRoot = "/rpc-build";

        api.createModule("demo", "Guarded Demo");
        api.setCompileOptions(
                "demo",
                ApiCompileTestSupport.compileOptions(
                        projectPath,
                        COptimizationLevel.DEBUG,
                        TargetPlatform.getNativePlatform(),
                        false,
                        outputMountRoot
                )
        );
        api.putFile("demo", "/src/main.gd", validSource("GuardedDemo"));
        api.putFile("demo", outputMountRoot + "/generated/Evil.gd", validSource("Evil"));

        var result = ApiCompileTestSupport.awaitResult(api, api.compile("demo"));

        assertEquals(CompileResult.Outcome.CONFIGURATION_FAILED, result.outcome());
        assertTrue(
                result.failureMessage() != null && result.failureMessage().contains("compiler-owned"),
                () -> "expected a compiler-owned output contract failure, got: " + result.failureMessage()
        );
    }

    private static String validSource(String className) {
        return """
                class_name %s
                extends RefCounted
                
                func value() -> int:
                    return 1
                """.formatted(className);
    }
}
