package gd.script.gdcc.backend.c.build.packedref;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors {@link ProbeGoldenComparison} semantics from both directions, including the
/// disable-list contract: unchecked cases must be ignored for payload/missing checks (they
/// are scheduled for later phases), while structural checks (unknown cases, order) apply
/// unconditionally so harness desync cannot hide behind the disabled inventory.
class ProbeGoldenComparisonTest {

    private static ProbeOutput output(String... lines) {
        return ProbeOutput.parse(String.join("\n", lines) + "\n");
    }

    @Test
    void matchesWhenAllCheckedCasesAlign() {
        var golden = output("PROBE|A|1", "PROBE|B|2");
        var actual = output("PROBE|A|1", "PROBE|B|2");

        var comparison = ProbeGoldenComparison.compare(actual, golden, Set.of("A", "B"));

        assertTrue(comparison.matches(), comparison::describe);
    }

    @Test
    void reportsPayloadMismatchForCheckedCase() {
        var golden = output("PROBE|A|1");
        var actual = output("PROBE|A|2");

        var comparison = ProbeGoldenComparison.compare(actual, golden, Set.of("A"));

        assertFalse(comparison.matches());
        assertEquals(1, comparison.payloadMismatches().size());
        assertTrue(comparison.payloadMismatches().getFirst().contains("A"));
        assertTrue(comparison.describe().contains("payload mismatches"));
    }

    @Test
    void ignoresPayloadMismatchForUncheckedCase() {
        // Disable-list anchor: a divergent but phase-disabled case must not fail the comparison.
        var golden = output("PROBE|A|1", "PROBE|B|2");
        var actual = output("PROBE|A|1", "PROBE|B|999");

        var comparison = ProbeGoldenComparison.compare(actual, golden, Set.of("A"));

        assertTrue(comparison.matches(), comparison::describe);
    }

    @Test
    void reportsMissingCheckedCase() {
        var golden = output("PROBE|A|1", "PROBE|B|2");
        var actual = output("PROBE|A|1");

        var comparison = ProbeGoldenComparison.compare(actual, golden, Set.of("B"));

        assertFalse(comparison.matches());
        assertEquals(java.util.List.of("B"), comparison.missingCheckedCases());
    }

    @Test
    void ignoresMissingUncheckedCase() {
        var golden = output("PROBE|A|1", "PROBE|B|2");
        var actual = output("PROBE|A|1");

        var comparison = ProbeGoldenComparison.compare(actual, golden, Set.of("A"));

        assertTrue(comparison.matches(), comparison::describe);
    }

    @Test
    void reportsCheckedCaseMissingFromGoldenAsConfigError() {
        var golden = output("PROBE|A|1");
        var actual = output("PROBE|A|1");

        var comparison = ProbeGoldenComparison.compare(actual, golden, Set.of("UNREGISTERED"));

        assertFalse(comparison.matches());
        assertEquals(java.util.List.of("UNREGISTERED"), comparison.checkedCasesMissingFromGolden());
    }

    @Test
    void reportsUnknownActualCaseEvenWhenAllCheckedPass() {
        var golden = output("PROBE|A|1");
        var actual = output("PROBE|A|1", "PROBE|UNEXPECTED|x");

        var comparison = ProbeGoldenComparison.compare(actual, golden, Set.of("A"));

        assertFalse(comparison.matches());
        assertEquals(java.util.List.of("UNEXPECTED"), comparison.unknownActualCases());
    }

    @Test
    void reportsOrderViolation() {
        var golden = output("PROBE|A|1", "PROBE|B|2");
        var actual = output("PROBE|B|2", "PROBE|A|1");

        var comparison = ProbeGoldenComparison.compare(actual, golden, Set.of("A", "B"));

        assertFalse(comparison.matches());
        assertEquals(1, comparison.orderViolations().size());
    }

    @Test
    void truncatedActualKeepsConsistentRelativeOrder() {
        // A run that stopped early (crash) must not additionally report an order violation for
        // the prefix it did emit; only the missing checked case is reported.
        var golden = output("PROBE|A|1", "PROBE|B|2");
        var actual = output("PROBE|A|1");

        var comparison = ProbeGoldenComparison.compare(actual, golden, Set.of("B"));

        assertFalse(comparison.matches());
        assertTrue(comparison.orderViolations().isEmpty());
        assertEquals(java.util.List.of("B"), comparison.missingCheckedCases());
    }
}
