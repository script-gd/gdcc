package gd.script.gdcc.backend.c.build.packedref;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors {@link PackedRefSemanticsCase} to the plan contract: the registry must cover the
/// §2 matrix rows exactly (each row maps to at least one case), stay in sync with the
/// committed golden resource, and keep the plan-mandated phase assignments for the rows
/// pinned to Phase D (7a + dynamic Variant receiver) and Phase F (23, 24).
class PackedRefSemanticsCaseRegistryTest {

    /// §2 matrix row labels that must each be covered by exactly one registered case.
    /// ("21" is split into EQUALITY and the "21-hash" sub-case; "7" splits into 7a/7b.)
    private static final Set<String> MATRIX_ROWS = Set.of(
            "1", "2", "3", "4", "5", "6", "7a", "7b", "8", "9", "10", "11", "12", "13", "14",
            "15", "16", "17", "18", "19", "20", "21", "21-hash", "22", "23", "24"
    );

    @Test
    void coversEveryMatrixRowExactlyOnce() {
        var coveredRows = PackedRefSemanticsCase.cases().stream()
                .map(PackedRefSemanticsCase::matrixRow)
                .collect(Collectors.toSet());

        assertEquals(MATRIX_ROWS.size(), PackedRefSemanticsCase.cases().size() - 1,
                "registry should contain one case per matrix row plus the dynamic Variant receiver case");
        assertEquals(MATRIX_ROWS, coveredRows.stream().filter(row -> !row.equals("dyn-variant")).collect(Collectors.toSet()));
        assertTrue(coveredRows.contains("dyn-variant"));
    }

    @Test
    void probeNamesAreUniqueAndFollowParserContract() {
        var seen = new HashSet<String>();
        for (var probeCase : PackedRefSemanticsCase.cases()) {
            assertTrue(seen.add(probeCase.probeName()), "duplicate probe name: " + probeCase.probeName());
            assertTrue(probeCase.probeName().matches("[A-Z0-9_]+"), "probe name violates ProbeOutput contract: " + probeCase.probeName());
        }
    }

    @Test
    void rejectsMalformedProbeName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PackedRefSemanticsCase("lowercase", "1", PackedRefSemanticsCase.EnablePhase.A)
        );
    }

    @Test
    void goldenResourceContainsExactlyTheRegisteredCasesInOrder() throws IOException {
        // Cross-anchor between the two harness inputs: a case added to the probe library but
        // not to the golden (or vice versa) must fail here, before any Godot run.
        var golden = PackedRefSemanticsDualRunHarness.loadGolden();

        assertEquals(
                PackedRefSemanticsCase.cases().stream().map(PackedRefSemanticsCase::probeName).toList(),
                golden.caseNames()
        );
    }

    @Test
    void phaseAEnabledSetMatchesRegistryPhaseFlags() {
        var expected = PackedRefSemanticsCase.cases().stream()
                .filter(probeCase -> probeCase.enablePhase() == PackedRefSemanticsCase.EnablePhase.A)
                .map(PackedRefSemanticsCase::probeName)
                .collect(Collectors.toSet());

        assertEquals(expected, PackedRefSemanticsCase.phaseAEnabledCaseNames());
        assertFalse(expected.isEmpty(), "Phase A must enable the currently passing baseline cases");
    }

    @Test
    void phaseAEnabledSetMatchesPlanPhaseAStatus() {
        // Pins the exact Phase A enabled inventory recorded in plan §6 "Phase A 状态" — a case
        // silently flipped from A to C would otherwise shrink the asserted baseline unnoticed.
        assertEquals(
                Set.of(
                        "SCRIPT_PROPERTY", "TYPED_ARRAY_ELEMENT", "DICT_VALUE",
                        "BUILTIN_PROPERTY_REASSIGN", "PLUS_EQUALS_REBIND", "DUPLICATE",
                        "PARAM_DEFAULT_SHARED", "ELEMENT_REBIND", "STRING_ITER_ELEMENTS",
                        "IN_MEMBERSHIP"
                ),
                PackedRefSemanticsCase.phaseAEnabledCaseNames()
        );
    }

    @Test
    void compileBlockedCasesAreRegisteredAndNotPhaseAEnabled() {
        // The companion-module split contract: a compile-blocked case must stay registered
        // (golden coverage continues on the interpreter side) and must never be part of the
        // Phase A enabled set. Currency of the blocked set itself is enforced by the harness
        // at runtime (an unexpected successful compile is reported in the transcript).
        for (var caseName : PackedRefSemanticsDualRunHarness.GDCC_COMPILE_BLOCKED_CASE_NAMES) {
            var probeCase = PackedRefSemanticsCase.requireCase(caseName);
            assertNotEquals(
                    PackedRefSemanticsCase.EnablePhase.A,
                    probeCase.enablePhase(),
                    "compile-blocked case must not be Phase A enabled: " + caseName
            );
        }
    }

    @Test
    void planMandatedPhaseAssignmentsArePinned() {
        // Plan §6 Phase D acceptance: 7a and the dynamic Variant receiver case; STATIC_VAR and
        // LAMBDA_CAPTURE are compile-blocked on §5 frontend route surfaces (fail-closed static
        // bare-property promotion / CAPTURE alias publication), so they are pinned to D as well.
        assertEquals(PackedRefSemanticsCase.EnablePhase.D, PackedRefSemanticsCase.requireCase("BUILTIN_PROPERTY_MUTATION").enablePhase());
        assertEquals(PackedRefSemanticsCase.EnablePhase.D, PackedRefSemanticsCase.requireCase("DYNAMIC_VARIANT_MUTATION").enablePhase());
        assertEquals(PackedRefSemanticsCase.EnablePhase.D, PackedRefSemanticsCase.requireCase("STATIC_VAR").enablePhase());
        assertEquals(PackedRefSemanticsCase.EnablePhase.D, PackedRefSemanticsCase.requireCase("LAMBDA_CAPTURE").enablePhase());
        // Plan §6 Phase F: full acceptance enables rows 23 and 24.
        assertEquals(PackedRefSemanticsCase.EnablePhase.F, PackedRefSemanticsCase.requireCase("CORO_AWAIT").enablePhase());
        assertEquals(PackedRefSemanticsCase.EnablePhase.F, PackedRefSemanticsCase.requireCase("SIGNAL_MULTI").enablePhase());
        // Every remaining disabled case belongs to the Phase C storage-model switch batch.
        for (var probeCase : PackedRefSemanticsCase.cases()) {
            if (probeCase.enablePhase() == PackedRefSemanticsCase.EnablePhase.A) {
                continue;
            }
            var name = probeCase.probeName();
            if (name.equals("BUILTIN_PROPERTY_MUTATION") || name.equals("DYNAMIC_VARIANT_MUTATION")
                    || name.equals("STATIC_VAR") || name.equals("LAMBDA_CAPTURE")
                    || name.equals("CORO_AWAIT") || name.equals("SIGNAL_MULTI")) {
                continue;
            }
            assertEquals(
                    PackedRefSemanticsCase.EnablePhase.C,
                    probeCase.enablePhase(),
                    "disabled case outside the D/F pinned set must belong to Phase C: " + name
            );
        }
    }
}
