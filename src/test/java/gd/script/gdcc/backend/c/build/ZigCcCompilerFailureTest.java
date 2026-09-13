package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
public class ZigCcCompilerFailureTest {

    @Test
    public void singleTuSyntaxErrorFailsWithCommandSectionAndNoArtifacts(@TempDir Path tempDir) throws IOException {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping compile failure test");
            return;
        }
        var broken = tempDir.resolve("broken.c");
        Files.writeString(broken, "this is not valid C\n");

        var result = new ZigCcCompiler().compile(tempDir, List.of(), List.of(broken), "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty(), "a failed round must not publish artifacts");
        var buildLog = result.buildLog();
        assertTrue(buildLog.startsWith("Command: "), () -> "failure log opens with the started command section:\n" + buildLog);
        assertEquals(1, countCommandSections(buildLog), () -> "exactly the one started TU may appear:\n" + buildLog);
        assertTrue(buildLog.contains(broken.toAbsolutePath().toString()));
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

        var result = new ZigCcCompiler().compile(tempDir, List.of(), List.of(validA, brokenB, validC), "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty());
        var buildLog = result.buildLog();
        // Parallel TU semantics: a failing TU does not stop its siblings — all three TUs are
        // started and run to completion so the failure log is complete; their sections follow
        // the cFiles slot order; the link step is never started.
        assertEquals(3, countCommandSections(buildLog), () -> "every started TU gets a section:\n" + buildLog);
        var indexA = buildLog.indexOf(validA.toAbsolutePath().toString());
        var indexB = buildLog.indexOf(brokenB.toAbsolutePath().toString());
        var indexC = buildLog.indexOf(validC.toAbsolutePath().toString());
        assertTrue(indexA >= 0 && indexB > indexA && indexC > indexB,
                () -> "TU sections keep the cFiles order:\n" + buildLog);
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

        var result = new ZigCcCompiler().compile(tempDir, List.of(), List.of(dupA, dupB), "probe", COptimizationLevel.DEBUG, TargetPlatform.getNativePlatform());

        assertFalse(result.success());
        assertTrue(result.artifacts().isEmpty(), "a failed link must not publish artifacts");
        var buildLog = result.buildLog();
        assertEquals(3, countCommandSections(buildLog), () -> "both TUs plus the link were started:\n" + buildLog);
        var indexA = buildLog.indexOf(dupA.toAbsolutePath().toString());
        var indexB = buildLog.indexOf(dupB.toAbsolutePath().toString());
        var indexLink = buildLog.indexOf("-shared");
        assertTrue(indexA >= 0 && indexB > indexA && indexLink > indexB,
                () -> "sections follow the slot order TU0, TU1, link:\n" + buildLog);
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
        var result = new ZigCcCompiler().compile(tempDir, List.of(), List.of(validA), "probe", COptimizationLevel.DEBUG, targetPlatform);

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
}
