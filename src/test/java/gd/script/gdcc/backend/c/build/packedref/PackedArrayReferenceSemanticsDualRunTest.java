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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Dual-run golden comparison for the Packed*Array reference-semantics migration.
///
/// The same `packed_ref_probes.gd` source runs once under the Godot interpreter and once
/// compiled by gdcc; both sides must reproduce the committed golden file for every case the
/// registry currently asserts. The interpreter side is always validated against the full
/// golden (it is the baseline the golden was locked with), so a wrong `GODOT_BIN` version or
/// a broken probe library fails fast instead of silently re-baselining.
///
/// Cases deferred on a capability gap are registered in {@link PackedRefSemanticsCase} with
/// their gate and reported as skipped per case, keeping the deferred inventory visible in
/// test reports.
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
    /// harness bugs regardless of per-case gating), plus payload equality for every currently
    /// asserted case. Deferred cases run and appear in the transcript but are not asserted.
    @Test
    void gdccRunMatchesGoldenStructureAndEnabledCases() {
        Assumptions.assumeTrue(
                dualRun.gdccOutput() != null,
                "Zig not found; gdcc side was not built (interpreter baseline still validated)"
        );
        var comparison = ProbeGoldenComparison.compare(
                dualRun.gdccOutput(),
                golden,
                PackedRefSemanticsCase.assertedCaseNames()
        );
        assertTrue(
                comparison.matches(),
                () -> "gdcc-compiled run diverges from golden.\n" + comparison.describe()
                        + "\nTranscript: " + PackedRefSemanticsDualRunHarness.TRANSCRIPTS_DIR.resolve("gdcc_stdout.txt")
        );
    }

    /// Per-case granularity: every case re-checks its interpreter payload against the golden;
    /// asserted cases additionally assert gdcc-side payload equality, while gated cases report
    /// as skipped with their deferral reason.
    @TestFactory
    List<DynamicTest> perCaseGoldenAlignment() {
        return PackedRefSemanticsCase.cases().stream()
                .map(probeCase -> DynamicTest.dynamicTest(
                        probeCase.probeName() + " [§2-" + probeCase.matrixRow() + ", " + probeCase.gate() + "]",
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
        if (!probeCase.isAsserted()) {
            Assumptions.abort("gdcc-side golden alignment deferred: " + probeCase.gate());
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
