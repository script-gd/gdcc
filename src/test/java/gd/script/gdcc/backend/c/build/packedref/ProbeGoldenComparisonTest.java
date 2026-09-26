package gd.script.gdcc.backend.c.build.packedref;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors {@link ProbeGoldenComparison} full-matrix semantics from both directions: every
/// golden case must be present with an equal payload (no case can be silently skipped or
/// diverge), and structural checks (unknown cases, relative order) catch probe-library/golden
/// desync and control-flow breakage.
class ProbeGoldenComparisonTest {

    private static ProbeOutput output(String... lines) {
        return ProbeOutput.parse(String.join("\n", lines) + "\n");
    }

    @Test
    void matchesWhenAllCasesAlign() {
        var golden = output("PROBE|A|1", "PROBE|B|2");
        var actual = output("PROBE|A|1", "PROBE|B|2");

        var comparison = ProbeGoldenComparison.compare(actual, golden);

        assertTrue(comparison.matches(), comparison::describe);
    }

    @Test
    void reportsPayloadMismatchForAnyCase() {
        // Full-matrix anchor: NO case is exempt — a divergence in any golden case must fail.
        var golden = output("PROBE|A|1", "PROBE|B|2");
        var actual = output("PROBE|A|1", "PROBE|B|999");

        var comparison = ProbeGoldenComparison.compare(actual, golden);

        assertFalse(comparison.matches());
        assertEquals(1, comparison.payloadMismatches().size());
        assertTrue(comparison.payloadMismatches().getFirst().contains("B"));
        assertTrue(comparison.describe().contains("payload mismatches"));
    }

    @Test
    void reportsMissingCase() {
        // Full-matrix anchor: a case silently skipped by the run must fail, wherever it sits.
        var golden = output("PROBE|A|1", "PROBE|B|2");
        var actual = output("PROBE|A|1");

        var comparison = ProbeGoldenComparison.compare(actual, golden);

        assertFalse(comparison.matches());
        assertEquals(java.util.List.of("B"), comparison.missingCases());
    }

    @Test
    void reportsUnknownActualCase() {
        var golden = output("PROBE|A|1");
        var actual = output("PROBE|A|1", "PROBE|UNEXPECTED|x");

        var comparison = ProbeGoldenComparison.compare(actual, golden);

        assertFalse(comparison.matches());
        assertEquals(java.util.List.of("UNEXPECTED"), comparison.unknownActualCases());
    }

    @Test
    void reportsOrderViolation() {
        var golden = output("PROBE|A|1", "PROBE|B|2");
        var actual = output("PROBE|B|2", "PROBE|A|1");

        var comparison = ProbeGoldenComparison.compare(actual, golden);

        assertFalse(comparison.matches());
        assertEquals(1, comparison.orderViolations().size());
    }

    @Test
    void truncatedActualKeepsConsistentRelativeOrder() {
        // A run that stopped early (crash) must not additionally report an order violation for
        // the prefix it did emit; only the missing cases are reported.
        var golden = output("PROBE|A|1", "PROBE|B|2");
        var actual = output("PROBE|A|1");

        var comparison = ProbeGoldenComparison.compare(actual, golden);

        assertFalse(comparison.matches());
        assertTrue(comparison.orderViolations().isEmpty());
        assertEquals(java.util.List.of("B"), comparison.missingCases());
    }

    @Test
    void emptyActualReportsEveryGoldenCaseMissing() {
        var golden = output("PROBE|A|1", "PROBE|B|2");
        var actual = output("noise only, no probe lines");

        var comparison = ProbeGoldenComparison.compare(actual, golden);

        assertFalse(comparison.matches());
        assertEquals(java.util.List.of("A", "B"), comparison.missingCases());
        assertTrue(comparison.unknownActualCases().isEmpty());
    }
}
