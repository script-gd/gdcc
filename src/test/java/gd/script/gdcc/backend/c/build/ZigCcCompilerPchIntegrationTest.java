package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Real-zig integration gate for the PCH cache, gated on [ZigUtil.findZig] exactly like the
/// other native integration tests. The cache root is pinned inside `@TempDir` through the
/// package-private cache-root seam, so the shared compiler cache and the shared include tree
/// are never touched (`CProjectBuilder.setIgnoreSharedInclude(true)` + project-local include
/// root). Anchors:
/// - an entry-only rebuild reuses the installed PCH (same key dir, pch file untouched);
/// - tampering with one include-tree header changes the key and rebuilds the PCH;
/// - a corrupted installed PCH fails the reuse probe, is deleted and rebuilt once
///   (self-heal), and the round still succeeds WITH PCH (no fallback line);
/// - an mtime-only change of a transitively included header (external churn; production
///   extraction prevents it) degrades safely to no-PCH instead of failing the build;
/// - the PCH-built artifact loads in Godot when `GODOT_BIN` is configured.
public class ZigCcCompilerPchIntegrationTest {

    @Test
    public void pchReusedAcrossRebuildsAndRebuiltAfterHeaderTamper(@TempDir Path tempDir) throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping PCH integration test");
            return;
        }
        var projectDir = tempDir.resolve("project-a");
        Files.createDirectories(projectDir);
        var cacheRoot = tempDir.resolve("compiler-cache");
        var projectInfo = new CProjectInfo("testproj", GodotVersion.V451, projectDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        var first = buildProject(projectInfo, cacheRoot);
        assertTrue(first.success(), () -> "first build failed:\n" + first.buildLog());
        assertFalse(first.buildLog().contains("PCH unavailable"), () -> "PCH must engage on the first build:\n" + first.buildLog());

        var firstKeyDir = singleKeyDir(cacheRoot);
        var pchFile = firstKeyDir.resolve("gdcc_godot_prefix.pch");
        assertTrue(Files.isRegularFile(firstKeyDir.resolve(".ready")), "marker must be published: " + firstKeyDir);
        assertTrue(Files.isRegularFile(firstKeyDir.resolve("gdcc_godot_prefix.h")));
        var pchContent = Files.readAllBytes(pchFile);
        assertTrue(pchContent.length > 0, "a real pch was installed");

        // Entry-TU-only rebuild (the incremental user-code shape): same key, same pch file.
        touchEntryTu(projectDir);
        var second = compileProject(projectDir, projectInfo, cacheRoot);
        assertTrue(second.success(), () -> "incremental build failed:\n" + second.buildLog());
        assertFalse(second.buildLog().contains("PCH unavailable"), () -> "an entry-only change must keep PCH:\n" + second.buildLog());
        assertEquals(firstKeyDir, singleKeyDir(cacheRoot), "an entry-only change must reuse the same key");
        assertArrayEquals(pchContent, Files.readAllBytes(pchFile), "the installed pch is reused, not rebuilt");

        // Tampering with one include-tree header changes the key and rebuilds the pch into a
        // new key directory (the stale entry is left alone; capacity management is backlog).
        var tamperedHeader = projectDir.resolve("include/godot/godot_macros.h");
        Files.writeString(tamperedHeader, Files.readString(tamperedHeader) + "\n/* pch key tamper */\n");
        var third = compileProject(projectDir, projectInfo, cacheRoot);
        assertTrue(third.success(), () -> "post-tamper build failed:\n" + third.buildLog());
        assertFalse(third.buildLog().contains("PCH unavailable"), () -> "a header change rebuilds the PCH, it does not fall back:\n" + third.buildLog());
        var keyDirs = keyDirs(cacheRoot);
        assertEquals(2, keyDirs.size(), () -> "the tamper must produce a second key: " + keyDirs);
        var secondKeyDir = keyDirs.stream().filter(dir -> !dir.equals(firstKeyDir)).findFirst().orElseThrow();
        assertTrue(Files.isRegularFile(secondKeyDir.resolve(".ready")), "the new key's entry was installed");

        // The PCH-built artifact loads and runs in Godot when a binary is configured.
        runInGodotIfAvailable(third.artifacts());
    }

    @Test
    public void corruptedInstalledPchIsHealedAndReused(@TempDir Path tempDir) throws IOException {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping PCH integration test");
            return;
        }
        var projectDir = tempDir.resolve("project-a");
        Files.createDirectories(projectDir);
        var cacheRoot = tempDir.resolve("compiler-cache");
        var projectInfo = new CProjectInfo("testproj", GodotVersion.V451, projectDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        var first = buildProject(projectInfo, cacheRoot);
        assertTrue(first.success(), () -> "first build failed:\n" + first.buildLog());
        var keyDir = singleKeyDir(cacheRoot);
        var pchFile = keyDir.resolve("gdcc_godot_prefix.pch");
        var originalSize = Files.size(pchFile);

        // Corrupt the installed pch in place: marker and prefix header stay present, so the
        // poison can only be discovered by the reuse probe (clang rejects a truncated pch).
        var garbage = new byte[]{1, 2, 3, 4};
        Files.write(pchFile, garbage);

        var healed = compileProject(projectDir, projectInfo, cacheRoot);
        assertTrue(healed.success(), () -> "the healed build failed:\n" + healed.buildLog());
        assertFalse(healed.buildLog().contains("PCH unavailable"),
                () -> "self-heal must recover PCH instead of falling back:\n" + healed.buildLog());
        assertEquals(keyDir, singleKeyDir(cacheRoot), "the heal rebuilds into the same key directory");
        assertEquals(originalSize, Files.size(pchFile), "the corrupted pch was rebuilt (zig reproduces the identical entry)");
        assertFalse(java.util.Arrays.equals(garbage, Files.readAllBytes(pchFile)), "the garbage bytes are gone");
        assertTrue(Files.isRegularFile(keyDir.resolve(".ready")), "the healed entry is fully published");
    }

    @Test
    public void transitiveHeaderMtimeTouchDegradesSafelyInsteadOfFailing(@TempDir Path tempDir) throws IOException {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping PCH integration test");
            return;
        }
        var projectDir = tempDir.resolve("project-a");
        Files.createDirectories(projectDir);
        var cacheRoot = tempDir.resolve("compiler-cache");
        var projectInfo = new CProjectInfo("testproj", GodotVersion.V451, projectDir, COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        var first = buildProject(projectInfo, cacheRoot);
        assertTrue(first.success(), () -> "first build failed:\n" + first.buildLog());

        // Touch ONLY the mtime of a transitively included header — content (and therefore the
        // key) is unchanged. clang rejects the installed pch, and zig's content cache replays
        // the stale-mtime rebuild, so the round degrades to no-PCH: the documented safety net
        // for external mtime churn, never a build failure. Production runs do not hit this
        // path because resource extraction compares content before replacing (see
        // ResourceExtractorTest.testJarExtractionSkipsIdenticalFilesWithoutTouchingMtime).
        var header = projectDir.resolve("include/godot/godot_abi.h");
        Files.setLastModifiedTime(header, FileTime.from(Instant.now().plusSeconds(5)));

        var touched = compileProject(projectDir, projectInfo, cacheRoot);
        assertTrue(touched.success(), () -> "an mtime-only change must degrade, never fail the build:\n" + touched.buildLog());
        assertTrue(touched.buildLog().startsWith("[gdcc] PCH unavailable this round:"),
                () -> "the safety-net fallback line leads the log:\n" + touched.buildLog());
        assertTrue(Files.isRegularFile(touched.artifacts().getFirst()), "the artifact is still produced");
    }

    /// First build through the real `CProjectBuilder` pipeline with a production compiler
    /// whose cache root is pinned to the test directory.
    private static @NotNull CBuildResult buildProject(@NotNull CProjectInfo projectInfo, @NotNull Path cacheRoot) throws IOException {
        var builder = new CProjectBuilder(newPchCompiler(cacheRoot));
        builder.setIgnoreSharedInclude(true);
        return builder.buildProject(projectInfo, prepareCodegen(projectInfo));
    }

    /// Recompiles with inputs identical to what `CProjectBuilder` collected, mirroring the
    /// incremental-rebuild shape used by the incremental integration test.
    private static @NotNull CCompileResult compileProject(@NotNull Path projectDir, @NotNull CProjectInfo projectInfo, @NotNull Path cacheRoot) throws IOException {
        var includeRoot = projectDir.resolve("include");
        var includeDirs = List.of(includeRoot.resolve("gdcc"), includeRoot.resolve("godot"));
        var cFiles = List.of(
                projectDir.resolve("entry.c"),
                includeRoot.resolve("godot/godot_binding.c"),
                includeRoot.resolve("gdcc/minicoro.c"),
                includeRoot.resolve("gdcc/gdcc_coroutine.c"));
        return newPchCompiler(cacheRoot).compile(projectDir, includeDirs, cFiles, outputBaseName(projectInfo),
                projectInfo.getOptimizationLevel(), projectInfo.getTargetPlatform());
    }

    /// Production-shaped compiler (real launcher, real zig discovery, real version probe) with
    /// only the cache root pinned to the test directory.
    private static @NotNull CCompiler newPchCompiler(@NotNull Path cacheRoot) {
        return new ZigCcCompiler(CProcessLauncher.processBuilder(), ZigUtil::findZig, null, projectDir -> cacheRoot);
    }

    private static void touchEntryTu(@NotNull Path projectDir) throws IOException {
        var entryC = projectDir.resolve("entry.c");
        Files.writeString(entryC, Files.readString(entryC) + "\n/* incremental rebuild probe */\n");
    }

    private static @NotNull Path singleKeyDir(@NotNull Path cacheRoot) throws IOException {
        var keyDirs = keyDirs(cacheRoot);
        assertEquals(1, keyDirs.size(), () -> "expected exactly one pch key dir: " + keyDirs);
        return keyDirs.getFirst();
    }

    private static @NotNull List<Path> keyDirs(@NotNull Path cacheRoot) throws IOException {
        try (var entries = Files.list(cacheRoot.resolve("pch"))) {
            return entries.filter(Files::isDirectory).toList();
        }
    }

    /// Minimal Godot validation: the project carries the PCH-built library and a bare scene
    /// script; a broken library makes Godot log the dynamic-loader failure before the script
    /// ever prints the stop signal.
    private static void runInGodotIfAvailable(@NotNull List<Path> artifacts) throws IOException, InterruptedException {
        if (GodotGdextensionTestRunner.findGodotBinaryFromEnv() == null) {
            return;
        }
        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                artifacts,
                List.of(),
                new GodotGdextensionTestRunner.TestScriptSpec("""
                        extends Node

                        func _ready() -> void:
                            print("Test stop.")
                        """)));
        var runResult = runner.run(true);
        var output = runResult.combinedOutput();
        assertFalse(output.contains("Can't open dynamic library"), () -> "the PCH-built library failed to load:\n" + output);
        assertTrue(runResult.stopSignalSeen(), () -> "the Godot run did not complete:\n" + output);
    }

    /// Mirrors the output-base-name rule of `CProjectBuilder.buildProject(...)`.
    private static @NotNull String outputBaseName(@NotNull CProjectInfo projectInfo) {
        return projectInfo.projectName() + "_" + projectInfo.getOptimizationLevel().name().toLowerCase(Locale.ROOT)
                + "_" + projectInfo.getTargetPlatform().architecture.name().toLowerCase(Locale.ROOT);
    }

    private static @NotNull CCodegen prepareCodegen(@NotNull CProjectInfo projectInfo) throws IOException {
        var codegen = new CCodegen();
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var context = new CodegenContext(projectInfo, new ClassRegistry(api));
        codegen.prepare(context, new LirModule(projectInfo.projectName(), List.of()));
        return codegen;
    }
}
