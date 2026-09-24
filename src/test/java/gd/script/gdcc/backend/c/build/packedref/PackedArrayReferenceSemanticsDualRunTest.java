package gd.script.gdcc.backend.c.build.packedref;

import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Dual-run golden comparison for the Packed*Array reference-semantics migration
/// (packed_array_reference_semantics_plan.md §6 Phase A, §8 acceptance mapping).
///
/// The same `packed_ref_probes.gd` source runs once under the Godot interpreter and once
/// compiled by gdcc; both sides must reproduce the committed golden file for every Phase-A
/// enabled case. The interpreter side is always validated against the full golden (it is the
/// baseline the golden was locked with), so a wrong `GODOT_BIN` version or a broken probe
/// library fails fast instead of silently re-baselining.
///
/// Cases whose gdcc-side alignment is scheduled for Phase C/D/F are registered in
/// {@link PackedRefSemanticsCase} with their phase and reported as skipped per case, keeping
/// the disabled inventory visible in test reports.
///
/// Gating: skipped via JUnit assumptions when `GODOT_BIN` is missing; the gdcc side is
/// additionally skipped when Zig is unavailable (the interpreter baseline still runs).
public class PackedArrayReferenceSemanticsDualRunTest {

    private static PackedRefSemanticsDualRunHarness.DualRunResult dualRun;
    private static ProbeOutput golden;

    @BeforeAll
    static void runBothSides() throws IOException, InterruptedException {
        var godotBinary = GodotGdextensionTestRunner.findGodotBinaryFromEnv();
        Assumptions.assumeTrue(
                godotBinary != null,
                "GODOT_BIN not found; skipping Packed*Array reference-semantics dual-run test"
        );
        golden = PackedRefSemanticsDualRunHarness.loadGolden();
        dualRun = PackedRefSemanticsDualRunHarness.runBothSides(godotBinary);
    }

    /// The interpreter side must reproduce the full golden exactly — all cases, payloads and
    /// emission order. This re-validates the baseline on every run (engine version drift on
    /// the machine surfaces here rather than corrupting comparisons against the gdcc side).
    @Test
    void interpreterRunMatchesGolden() {
        var comparison = ProbeGoldenComparison.compare(dualRun.interpreterOutput(), golden, Set.copyOf(golden.caseNames()));
        assertTrue(
                comparison.matches(),
                () -> "Interpreter run diverges from golden.\n" + comparison.describe()
                        + "\nTranscript: " + PackedRefSemanticsDualRunHarness.TRANSCRIPTS_DIR.resolve("interpreter_stdout.txt")
        );
    }

    /// Structural checks for the gdcc side (unknown cases, duplicates and emission order are
    /// harness bugs regardless of per-case phase), plus payload equality for the Phase-A
    /// enabled subset. Divergent-but-disabled cases run and appear in the transcript but are
    /// not asserted here.
    @Test
    void gdccRunMatchesGoldenStructureAndEnabledCases() {
        Assumptions.assumeTrue(
                dualRun.gdccOutput() != null,
                "Zig not found; gdcc side was not built (interpreter baseline still validated)"
        );
        // Migration tripwire: once the companion module's constructs stop being fail-closed
        // (Phase C/D work), this fails and its cases must be migrated back into the main probe
        // library (and their registry phases re-evaluated).
        assertFalse(
                dualRun.gdccBlockedModuleCompiled(),
                "Compile-blocked companion module unexpectedly compiled; migrate "
                        + PackedRefSemanticsDualRunHarness.GDCC_COMPILE_BLOCKED_CASE_NAMES
                        + " back into the main probe library"
        );
        var comparison = ProbeGoldenComparison.compare(
                dualRun.gdccOutput(),
                golden,
                PackedRefSemanticsCase.phaseAEnabledCaseNames()
        );
        assertTrue(
                comparison.matches(),
                () -> "gdcc-compiled run diverges from golden.\n" + comparison.describe()
                        + "\nTranscript: " + PackedRefSemanticsDualRunHarness.TRANSCRIPTS_DIR.resolve("gdcc_stdout.txt")
        );
    }

    /// Per-case granularity: every case re-checks its interpreter payload against the golden;
    /// Phase-A-enabled cases additionally assert gdcc-side payload equality, while C/D/F cases
    /// report as skipped with their scheduled phase.
    @TestFactory
    List<DynamicTest> perCaseGoldenAlignment() {
        return PackedRefSemanticsCase.cases().stream()
                .map(probeCase -> DynamicTest.dynamicTest(
                        probeCase.probeName() + " [§2-" + probeCase.matrixRow() + ", Phase " + probeCase.enablePhase() + "]",
                        () -> assertCaseAlignment(probeCase)
                ))
                .toList();
    }

    private static void assertCaseAlignment(PackedRefSemanticsCase probeCase) {
        var caseName = probeCase.probeName();
        var expectedPayload = golden.requirePayload(caseName);
        assertEquals(
                expectedPayload,
                dualRun.interpreterOutput().requirePayload(caseName),
                "Interpreter payload diverges from golden for case " + caseName
        );
        if (probeCase.enablePhase() != PackedRefSemanticsCase.EnablePhase.A) {
            Assumptions.abort("gdcc-side golden alignment scheduled for Phase " + probeCase.enablePhase());
        }
        Assumptions.assumeTrue(
                dualRun.gdccOutput() != null,
                "Zig not found; gdcc side was not built (interpreter baseline still validated)"
        );
        var gdccOutput = dualRun.gdccOutput();
        assertTrue(
                gdccOutput.hasCase(caseName),
                () -> "gdcc-compiled run is missing case " + caseName + ".\ngdcc PROBE lines:\n"
                        + String.join("\n", gdccOutput.probeLines())
        );
        assertEquals(
                expectedPayload,
                gdccOutput.requirePayload(caseName),
                "gdcc-compiled payload diverges from golden for case " + caseName
        );
    }
}
