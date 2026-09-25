package gd.script.gdcc.backend.c.build.packedref;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Anchors {@link PackedRefSemanticsCase} to the harness contract: the registry must cover the
/// behavior-matrix rows exactly (each row maps to at least one case), stay in sync with the
/// committed golden resource, keep the regression-floor set intact, and pin every deferred
/// case to its semantic gate.
class PackedRefSemanticsCaseRegistryTest {

    /// Behavior-matrix row labels that must each be covered by exactly one registered case.
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
                () -> new PackedRefSemanticsCase("lowercase", "1", PackedRefSemanticsCase.AssertionGate.ASSERTED, false)
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
    void assertedSetMatchesRegistryGates() {
        var expected = PackedRefSemanticsCase.cases().stream()
                .filter(PackedRefSemanticsCase::isAsserted)
                .map(PackedRefSemanticsCase::probeName)
                .collect(Collectors.toSet());

        assertEquals(expected, PackedRefSemanticsCase.assertedCaseNames());
        assertFalse(expected.isEmpty(), "the harness must always assert its golden-aligned set");
    }

    @Test
    void regressionFloorNeverShrinks() {
        // The regression floor: cases golden-aligned since the harness was introduced. A case
        // silently leaving the floor would hide a baseline regression behind the growing
        // asserted set, so the exact inventory is pinned here.
        assertEquals(
                Set.of(
                        "SCRIPT_PROPERTY", "TYPED_ARRAY_ELEMENT", "DICT_VALUE",
                        "BUILTIN_PROPERTY_REASSIGN", "PLUS_EQUALS_REBIND", "DUPLICATE",
                        "PARAM_DEFAULT_SHARED", "ELEMENT_REBIND", "STRING_ITER_ELEMENTS",
                        "IN_MEMBERSHIP"
                ),
                PackedRefSemanticsCase.baselineCaseNames()
        );
        // The floor must stay asserted: baseline membership without assertion is a contract bug.
        for (var probeCase : PackedRefSemanticsCase.cases()) {
            if (probeCase.baseline()) {
                assertTrue(probeCase.isAsserted(), "baseline case must stay asserted: " + probeCase.probeName());
            }
        }
    }

    @Test
    void assertedSetCoversRegressionFloorAndStorageModelCases() {
        // The current assertion set: the regression floor plus every case whose behavior the
        // Variant-backed storage model unlocked. Deferred cases stay visible but unasserted.
        assertEquals(
                Set.of(
                        "SCRIPT_PROPERTY", "TYPED_ARRAY_ELEMENT", "DICT_VALUE",
                        "BUILTIN_PROPERTY_REASSIGN", "PLUS_EQUALS_REBIND", "DUPLICATE",
                        "PARAM_DEFAULT_SHARED", "ELEMENT_REBIND", "STRING_ITER_ELEMENTS",
                        "IN_MEMBERSHIP",
                        "LOCAL_ALIAS", "PARAM_VISIBILITY", "SIGNAL_ARG", "FOR_ITER",
                        "APPEND_ARRAY_ALIAS", "RESIZE_ALIAS", "INDEX_WRITE_ALIAS",
                        "VARIANT_IDENTITY", "EQUALITY", "DICT_KEY_HASH", "AS_SAME_FAMILY"
                ),
                PackedRefSemanticsCase.assertedCaseNames()
        );
        var deferred = new HashSet<>(PackedRefSemanticsCase.cases().stream().map(PackedRefSemanticsCase::probeName).toList());
        deferred.removeAll(PackedRefSemanticsCase.assertedCaseNames());
        assertEquals(
                Set.of("BUILTIN_PROPERTY_MUTATION", "DYNAMIC_VARIANT_MUTATION", "STATIC_VAR",
                        "LAMBDA_CAPTURE", "CORO_AWAIT", "SIGNAL_MULTI"),
                deferred
        );
    }

    @Test
    void compileBlockedCasesAreRegisteredAndDeferred() {
        // The companion-module split contract: a compile-blocked case must stay registered
        // (golden coverage continues on the interpreter side) and must never be part of the
        // asserted set. Currency of the blocked set itself is enforced by the harness at
        // runtime (an unexpected successful compile is reported in the transcript).
        for (var caseName : PackedRefSemanticsDualRunHarness.GDCC_COMPILE_BLOCKED_CASE_NAMES) {
            assertFalse(
                    PackedRefSemanticsCase.requireCase(caseName).isAsserted(),
                    "compile-blocked case must stay deferred: " + caseName
            );
        }
    }

    @Test
    void deferredCasesPinTheirSemanticGate() {
        // Frontend writeback route/gate rework: builtin-engine-property mutation must stop
        // persisting; the dynamic-Variant gate must flip; the compile-blocked static/lambda
        // routes must unlock.
        for (var name : Set.of("BUILTIN_PROPERTY_MUTATION", "DYNAMIC_VARIANT_MUTATION", "STATIC_VAR", "LAMBDA_CAPTURE")) {
            assertEquals(
                    PackedRefSemanticsCase.AssertionGate.DEFERRED_FRONTEND_WRITEBACK_ROUTES,
                    PackedRefSemanticsCase.requireCase(name).gate(),
                    "gate mismatch for " + name
            );
        }
        // Full-matrix acceptance: the coroutine/signal combination rows.
        for (var name : Set.of("CORO_AWAIT", "SIGNAL_MULTI")) {
            assertEquals(
                    PackedRefSemanticsCase.AssertionGate.DEFERRED_FULL_MATRIX_ACCEPTANCE,
                    PackedRefSemanticsCase.requireCase(name).gate(),
                    "gate mismatch for " + name
            );
        }
        // Every case outside the deferred groups must be asserted — a silently deferred case
        // would shrink the golden-aligned inventory unnoticed.
        for (var probeCase : PackedRefSemanticsCase.cases()) {
            if (probeCase.gate() != PackedRefSemanticsCase.AssertionGate.DEFERRED_FRONTEND_WRITEBACK_ROUTES
                    && probeCase.gate() != PackedRefSemanticsCase.AssertionGate.DEFERRED_FULL_MATRIX_ACCEPTANCE) {
                assertEquals(
                        PackedRefSemanticsCase.AssertionGate.ASSERTED,
                        probeCase.gate(),
                        "case outside the deferred groups must be asserted: " + probeCase.probeName()
                );
            }
        }
    }
}
