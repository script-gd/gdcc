package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Negative-path gate for the two-phase compile, always driving the public
/// `ZigCcCompiler.compile(...)` with hand-built inputs: a failing TU or link must fail the
/// round with an empty artifact list, the failure log must contain `Command:` sections for
/// exactly the processes that were started — on a TU failure every TU of the round (parallel
/// siblings run to completion), in `cFiles` slot order, the link section only when the link
/// ran — and stale files under `obj/` must never leak into the link.
///
/// These toy rounds pass no include dirs, so the PCH build deterministically fails
/// (`godot_binding.h` is unreachable) and the round degrades to no-PCH: per the buildLog
/// contract the fixed fallback line leads the log, followed by the PCH-phase Command section,
/// then the TU/link sections. The assertions below anchor exactly that shape.
///
/// CONCURRENT: each method is an independent cold-cache real-zig round (cache root pinned
/// under the per-method `@TempDir`), so methods pack into the shared fork-join pool.
@Execution(ExecutionMode.CONCURRENT)
public class ZigCcCompilerFailureTest {

    @Test
    public void singleTuSyntaxErrorFailsWithCommandSectionAndNoArtifacts(@TempDir Path tempDir) throws IOException {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping compile failure test");
            return;
        }
        var broken = tempDir.resolve("broken.c");
        Files.writeString(broken, "this is not valid C\n");

        var result = newCompiler(tempDir).compile(tempDir, List.of(), List.of(broken), "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty(), "a failed round must not publish artifacts");
        var buildLog = result.buildLog();
        assertTrue(buildLog.startsWith("[gdcc] PCH unavailable this round: pch build failed"),
                () -> "the PCH fallback line leads the log:\n" + buildLog);
        // Sections: the failed PCH build, then the one started TU; the link never appears.
        assertEquals(2, countCommandSections(buildLog), () -> "PCH-phase section plus exactly the started TU:\n" + buildLog);
        var indexPchBuild = buildLog.indexOf("-x c-header");
        var indexBroken = buildLog.indexOf(broken.toAbsolutePath().toString());
        assertTrue(indexPchBuild > 0 && indexBroken > indexPchBuild,
                () -> "the PCH-phase section precedes the TU section:\n" + buildLog);
        assertTrue(buildLog.contains("error:"), () -> "clang's diagnostic is part of the section:\n" + buildLog);
        assertFalse(buildLog.contains("-shared"), () -> "the link step never started and must not appear:\n" + buildLog);
    }

    @Test
    public void failingMiddleTuFailsTheRoundWithAllStartedTuSectionsInOrder(@TempDir Path tempDir) throws IOException {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping compile failure test");
            return;
        }
        var validA = tempDir.resolve("probe_a.c");
        var brokenB = tempDir.resolve("probe_b.c");
        var validC = tempDir.resolve("probe_c.c");
        Files.writeString(validA, "int gdcc_probe_a(void) { return 1; }\n");
        Files.writeString(brokenB, "int gdcc_probe_b(void) { return ; }\n");
        Files.writeString(validC, "int gdcc_probe_c(void) { return 3; }\n");

        var result = newCompiler(tempDir).compile(tempDir, List.of(), List.of(validA, brokenB, validC), "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty());
        var buildLog = result.buildLog();
        assertTrue(buildLog.startsWith("[gdcc] PCH unavailable this round: pch build failed"),
                () -> "the PCH fallback line leads the log:\n" + buildLog);
        // Parallel TU semantics: a failing TU does not stop its siblings — all three TUs are
        // started and run to completion so the failure log is complete; their sections follow
        // the cFiles slot order after the single PCH-phase section; the link never starts.
        assertEquals(4, countCommandSections(buildLog), () -> "PCH-phase section plus every started TU:\n" + buildLog);
        var indexPchBuild = buildLog.indexOf("-x c-header");
        var indexA = buildLog.indexOf(validA.toAbsolutePath().toString());
        var indexB = buildLog.indexOf(brokenB.toAbsolutePath().toString());
        var indexC = buildLog.indexOf(validC.toAbsolutePath().toString());
        assertTrue(indexPchBuild > 0 && indexA > indexPchBuild && indexB > indexA && indexC > indexB,
                () -> "PCH prelude first, then TU sections in cFiles order:\n" + buildLog);
        assertTrue(buildLog.contains("error:"), () -> "the broken TU's diagnostic is part of its section:\n" + buildLog);
        assertFalse(buildLog.contains("-shared"), () -> "a failed TU round never reaches the link step:\n" + buildLog);
    }

    @Test
    public void linkFailureAppendsLinkCommandSectionAfterAllTuSlots(@TempDir Path tempDir) throws IOException {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping link failure test");
            return;
        }
        // Two TUs defining the same non-static symbol: both compile cleanly, the link fails.
        var dupA = tempDir.resolve("dup_a.c");
        var dupB = tempDir.resolve("dup_b.c");
        Files.writeString(dupA, "int gdcc_dup(void) { return 1; }\n");
        Files.writeString(dupB, "int gdcc_dup(void) { return 2; }\n");

        var result = newCompiler(tempDir).compile(tempDir, List.of(), List.of(dupA, dupB), "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty(), "a failed link must not publish artifacts");
        var buildLog = result.buildLog();
        assertTrue(buildLog.startsWith("[gdcc] PCH unavailable this round: pch build failed"),
                () -> "the PCH fallback line leads the log:\n" + buildLog);
        assertEquals(4, countCommandSections(buildLog), () -> "PCH-phase section, both TUs, plus the link:\n" + buildLog);
        var indexA = buildLog.indexOf(dupA.toAbsolutePath().toString());
        var indexB = buildLog.indexOf(dupB.toAbsolutePath().toString());
        var indexLink = buildLog.indexOf("-shared");
        assertTrue(indexA > 0 && indexB > indexA && indexLink > indexB,
                () -> "sections follow the order TU0, TU1, link:\n" + buildLog);
        assertTrue(buildLog.contains("duplicate symbol"), () -> "lld's diagnostic is part of the link section:\n" + buildLog);
    }

    @Test
    public void staleObjectFilesInObjDirectoryNeverEnterTheLink(@TempDir Path tempDir) throws IOException {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping stale object test");
            return;
        }
        var targetPlatform = TargetPlatform.getNativePlatform();
        var zigTarget = ZigCcCompiler.resolveZigTarget(targetPlatform).zigTarget();
        // Garbage at an index this round does not use: a link that globbed obj/ would choke on it.
        var unusedStaleObj = ZigCcCompiler.resolveObjectPath(tempDir, COptimizationLevel.DEBUG, zigTarget, 7, Path.of("stale.c"));
        Files.createDirectories(unusedStaleObj.getParent());
        Files.writeString(unusedStaleObj, "not an object file");
        // Garbage at exactly this round's own object path: recompilation must overwrite it
        // instead of treating the existing file as a reason to skip the TU.
        var ownStaleObj = ZigCcCompiler.resolveObjectPath(tempDir, COptimizationLevel.DEBUG, zigTarget, 0, Path.of("probe_a.c"));
        Files.writeString(ownStaleObj, "not an object file either");

        var validA = tempDir.resolve("probe_a.c");
        Files.writeString(validA, "int gdcc_probe_a(void) { return 1; }\n");
        var result = newCompiler(tempDir).compile(tempDir, List.of(), List.of(validA), "probe", COptimizationLevel.DEBUG, targetPlatform);

        assertTrue(result.success(), () -> "stale obj/ leftovers must not affect the round:\n" + result.buildLog());
        assertTrue(Files.isRegularFile(result.artifacts().getFirst()));
    }

    private static int countCommandSections(String buildLog) {
        var count = 0;
        for (var line : buildLog.split("\n")) {
            if (line.startsWith("Command: ")) {
                count++;
            }
        }
        return count;
    }

    /// Real-zig compiler with only the cache root pinned into the test directory: these rounds
    /// deterministically fail their PCH build (no include dirs), and a parent-process
    /// `GDCC_SHARED_C_COMPILER_CACHE` must not redirect that fallout into the shared cache.
    private static @NotNull CCompiler newCompiler(Path tempDir) {
        var cacheRoot = tempDir.resolve("compiler-cache");
        return new ZigCcCompiler(CProcessLauncher.processBuilder(), ZigUtil::findZig, null, projectDir -> cacheRoot);
    }
}
