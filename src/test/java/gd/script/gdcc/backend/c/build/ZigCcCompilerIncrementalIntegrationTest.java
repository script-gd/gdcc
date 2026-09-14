package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.CCodegen;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Real-zig integration gate for the two-phase compile: the same project is built twice with
/// only the entry-level TU rewritten in between — the exact shape of an incremental user-code
/// rebuild where zig's per-TU content cache absorbs the runtime TUs. Both rounds must succeed,
/// the artifact naming contract must hold, and the rebuilt shared library must keep exporting
/// `gdextension_entry`. Build time is intentionally never asserted.
///
/// CONCURRENT: each method owns an independent project under its per-method `@TempDir`.
@Execution(ExecutionMode.CONCURRENT)
public class ZigCcCompilerIncrementalIntegrationTest {

    @Test
    public void rebuildAfterEntryTuChangeSucceedsAndKeepsArtifactNaming(@TempDir Path tempDir) throws IOException {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping incremental compile integration test");
            return;
        }
        var projectDir = tempDir.resolve("project-a");
        Files.createDirectories(projectDir);
        var targetPlatform = TargetPlatform.getNativePlatform();
        var projectInfo = new CProjectInfo("testproj", GodotVersion.V451, projectDir, COptimizationLevel.DEBUG, targetPlatform);

        var first = new CProjectBuilder().buildProject(projectInfo, prepareCodegen(projectInfo));
        assertTrue(first.success(), () -> "first build failed:\n" + first.buildLog());

        var second = recompileAfterEntryTouch(projectDir, projectInfo);
        assertTrue(second.success(), () -> "second build failed:\n" + second.buildLog());

        // The artifact contract is unchanged by the two-phase split: first element is the
        // final shared library with the <module>_<opt>_<arch> name, objects are never published.
        var expectedFileName = targetPlatform.sharedLibraryFileName(outputBaseName(projectInfo));
        assertEquals(expectedFileName, first.artifacts().getFirst().getFileName().toString());
        assertEquals(expectedFileName, second.artifacts().getFirst().getFileName().toString());
        assertTrue(Files.isRegularFile(second.artifacts().getFirst()));
        assertFalse(second.artifacts().stream().anyMatch(path -> path.getFileName().toString().endsWith(".o")),
                "intermediate objects must not enter artifacts: " + second.artifacts());
    }

    @Test
    public void rebuiltArtifactExportsGdextensionEntry(@TempDir Path tempDir) throws IOException {
        assertGdextensionEntryExportedAcrossIncrementalRebuild(tempDir, COptimizationLevel.DEBUG);
    }

    @Test
    public void rebuiltReleaseArtifactKeepsGdextensionEntryExportedUnderThinLto(@TempDir Path tempDir) throws IOException {
        // ThinLTO may internalize symbols that look unreferenced; the GDExtension entry point
        // must stay exported in the release artifact exactly as in debug.
        assertGdextensionEntryExportedAcrossIncrementalRebuild(tempDir, COptimizationLevel.RELEASE);
    }

    private static void assertGdextensionEntryExportedAcrossIncrementalRebuild(@NotNull Path tempDir, @NotNull COptimizationLevel optimizationLevel) throws IOException {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping incremental compile integration test");
            return;
        }
        // The dynamic-symbol check needs an LLVM/GNU nm on an ELF host (zig 0.16 ships no
        // `zig nm` and `zig objdump` cannot dump symbols); anything else aborts like missing zig.
        var nm = findNm();
        if (nm == null || !isElfHost()) {
            Assumptions.abort("llvm-nm/nm on an ELF host is required for the dynamic symbol check");
            return;
        }
        var projectDir = tempDir.resolve("project-a");
        Files.createDirectories(projectDir);
        var projectInfo = new CProjectInfo("testproj", GodotVersion.V451, projectDir, optimizationLevel, TargetPlatform.getNativePlatform());

        var first = new CProjectBuilder().buildProject(projectInfo, prepareCodegen(projectInfo));
        assertTrue(first.success(), () -> "first build failed:\n" + first.buildLog());
        var second = recompileAfterEntryTouch(projectDir, projectInfo);
        assertTrue(second.success(), () -> "second build failed:\n" + second.buildLog());

        var dynamicSymbols = dumpDynamicDefinedSymbols(nm, second.artifacts().getFirst());
        assertTrue(dynamicSymbols.lines().anyMatch(line -> line.endsWith(" T gdextension_entry")),
                () -> "gdextension_entry must stay exported after an incremental rebuild:\n" + dynamicSymbols);
    }

    /// Rewrites only the entry-level TU (a cache-missing content change) and recompiles with
    /// inputs identical to what `CProjectBuilder` collected, driving the public
    /// `ZigCcCompiler.compile(...)` production entry point directly.
    private static @NotNull CCompileResult recompileAfterEntryTouch(@NotNull Path projectDir, @NotNull CProjectInfo projectInfo) throws IOException {
        var entryC = projectDir.resolve("entry.c");
        Files.writeString(entryC, Files.readString(entryC) + "\n/* incremental rebuild probe */\n");
        var includeRoot = projectDir.resolve("include");
        var includeDirs = List.of(includeRoot.resolve("gdcc"), includeRoot.resolve("godot"));
        var cFiles = List.of(
                entryC,
                includeRoot.resolve("godot/godot_binding.c"),
                includeRoot.resolve("gdcc/minicoro.c"),
                includeRoot.resolve("gdcc/gdcc_coroutine.c"));
        return new ZigCcCompiler().compile(projectDir, includeDirs, cFiles, outputBaseName(projectInfo),
                projectInfo.getOptimizationLevel(), projectInfo.getTargetPlatform());
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

    /// Prefers `llvm-nm` (bundled with LLVM toolchains) over binutils `nm`; both accept the
    /// GNU-style `-D --defined-only` flags used by the symbol dump below.
    private static @Nullable String findNm() {
        for (var candidate : List.of("llvm-nm", "nm")) {
            try {
                var probe = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                probe.getInputStream().readAllBytes();
                if (probe.waitFor() == 0) {
                    return candidate;
                }
            } catch (IOException exception) {
                // Candidate not installed; try the next one.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static boolean isElfHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static @NotNull String dumpDynamicDefinedSymbols(@NotNull String nm, @NotNull Path library) throws IOException {
        try {
            var dump = new ProcessBuilder(nm, "-D", "--defined-only", library.toString()).redirectErrorStream(true).start();
            var output = new String(dump.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (dump.waitFor() != 0) {
                throw new IOException("nm failed on " + library + ":\n" + output);
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while dumping symbols of " + library);
        }
    }
}
