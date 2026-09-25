package gd.script.gdcc.backend.c.build.packedref;

import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Dual-run golden comparison for the Packed*Array reference-semantics behavior contract
/// (semantics matrix + documented ABI exceptions).
///
/// The same `packed_ref_probes.gd` source runs once under the Godot interpreter and once
/// compiled by gdcc; both sides must reproduce the committed golden file in full. Truth
/// sources: the golden file owns case PAYLOADS and is always validated against the
/// interpreter side first (it is the baseline the golden was locked with), while
/// {@link PackedRefSemanticsGoldenInventoryTest} anchors the case inventory
/// (names + order, Godot-independent) so coverage cannot silently shrink.
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
        var comparison = ProbeGoldenComparison.compare(dualRun.interpreterOutput(), golden);
        assertTrue(
                comparison.matches(),
                () -> "Interpreter run diverges from golden.\n" + comparison.describe()
                        + "\nTranscript: " + PackedRefSemanticsDualRunHarness.TRANSCRIPTS_DIR.resolve("interpreter_stdout.txt")
        );
    }

    /// The gdcc side must reproduce the same full golden: every case present, every payload
    /// equal, no unknown cases, golden relative order. This is the executable form of the
    /// behavior matrix plus the documented ABI exceptions.
    @Test
    void gdccRunMatchesGolden() {
        Assumptions.assumeTrue(
                dualRun.gdccOutput() != null,
                "Zig not found; gdcc side was not built (interpreter baseline still validated)"
        );
        var comparison = ProbeGoldenComparison.compare(dualRun.gdccOutput(), golden);
        assertTrue(
                comparison.matches(),
                () -> "gdcc-compiled run diverges from golden.\n" + comparison.describe()
                        + "\nTranscript: " + PackedRefSemanticsDualRunHarness.TRANSCRIPTS_DIR.resolve("gdcc_stdout.txt")
        );
    }

    /// Per-case granularity in golden order: every case re-checks its interpreter payload
    /// against the golden and asserts gdcc-side payload equality.
    @TestFactory
    List<DynamicTest> perCaseGoldenAlignment() {
        return golden.caseNames().stream()
                .map(caseName -> DynamicTest.dynamicTest(
                        caseName,
                        () -> assertCaseAlignment(caseName)
                ))
                .toList();
    }

    private static void assertCaseAlignment(String caseName) {
        var expectedPayload = golden.requirePayload(caseName);
        assertEquals(
                expectedPayload,
                dualRun.interpreterOutput().requirePayload(caseName),
                "Interpreter payload diverges from golden for case " + caseName
        );
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
