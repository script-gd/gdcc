package gd.script.gdcc.backend.c.build.packedref;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Registry linking every `PROBE|<CASE>` emitted by `packed_ref_probes.gd` to its row in the
/// Packed*Array reference-semantics behavior matrix and to the assertion gate that decides
/// whether the dual-run test must hold the gdcc-compiled side to the interpreter golden today.
///
/// Ordering is contractual: entries follow the fixed `PackedRefProbes.run_all` execution
/// order, which is also the golden file's line order.
///
/// Gates describe capability gaps in semantic terms, not schedule: a case is either asserted
/// now, or deferred with the concrete reason it cannot yet be held to the golden.
public record PackedRefSemanticsCase(
        @NotNull String probeName,
        @NotNull String matrixRow,
        @NotNull AssertionGate gate,
        boolean baseline
) {
    /// Whether the dual-run test must assert gdcc-side golden equality for a case today —
    /// and if not, which capability gap currently defers it.
    public enum AssertionGate {
        /// gdcc-compiled output must match the interpreter golden.
        ASSERTED,
        /// Deferred on the frontend writeback route/gate rework: builtin-engine-property
        /// mutation must stop persisting through the getter copy, dynamic-Variant writeback
        /// gates must flip for packed carriers, and the fail-closed static/lambda routes must
        /// unlock (compile-blocked companion cases).
        DEFERRED_FRONTEND_WRITEBACK_ROUTES,
        /// Deferred to the full-matrix acceptance sweep: coroutine/signal combination coverage
        /// completes the matrix once every constituent route is proven.
        DEFERRED_FULL_MATRIX_ACCEPTANCE
    }

    public PackedRefSemanticsCase {
        Objects.requireNonNull(probeName, "probeName must not be null");
        Objects.requireNonNull(matrixRow, "matrixRow must not be null");
        Objects.requireNonNull(gate, "gate must not be null");
        if (!probeName.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("probeName must match [A-Z0-9_]+ (ProbeOutput contract): " + probeName);
        }
    }

    /// True when the dual-run test must hold this case's gdcc output to the golden today.
    public boolean isAsserted() {
        return gate == AssertionGate.ASSERTED;
    }

    /// All registered cases in `run_all`/golden order.
    public static @NotNull List<PackedRefSemanticsCase> cases() {
        return CASES;
    }

    /// Names of cases whose gdcc-side output is asserted against the golden today.
    public static @NotNull Set<String> assertedCaseNames() {
        var enabled = new LinkedHashSet<String>();
        for (var probeCase : CASES) {
            if (probeCase.isAsserted()) {
                enabled.add(probeCase.probeName());
            }
        }
        return Set.copyOf(enabled);
    }

    /// Names of the regression-floor cases: the set that has been golden-aligned since the
    /// harness was introduced. It must never shrink — a case silently leaving the floor would
    /// otherwise hide a baseline regression behind the growing asserted set.
    public static @NotNull Set<String> baselineCaseNames() {
        var baselineNames = new LinkedHashSet<String>();
        for (var probeCase : CASES) {
            if (probeCase.baseline()) {
                baselineNames.add(probeCase.probeName());
            }
        }
        return Set.copyOf(baselineNames);
    }

    /// Looks up a case by probe name, throwing `IllegalArgumentException` when unregistered.
    public static @NotNull PackedRefSemanticsCase requireCase(@NotNull String probeName) {
        for (var probeCase : CASES) {
            if (probeCase.probeName().equals(probeName)) {
                return probeCase;
            }
        }
        throw new IllegalArgumentException("Unregistered probe case: " + probeName);
    }

    private static PackedRefSemanticsCase asserted(@NotNull String probeName, @NotNull String matrixRow) {
        return new PackedRefSemanticsCase(probeName, matrixRow, AssertionGate.ASSERTED, false);
    }

    private static PackedRefSemanticsCase baseline(@NotNull String probeName, @NotNull String matrixRow) {
        return new PackedRefSemanticsCase(probeName, matrixRow, AssertionGate.ASSERTED, true);
    }

    private static final List<PackedRefSemanticsCase> CASES = List.of(
            asserted("LOCAL_ALIAS", "1"),
            asserted("PARAM_VISIBILITY", "2"),
            baseline("SCRIPT_PROPERTY", "3"),
            baseline("TYPED_ARRAY_ELEMENT", "5"),
            baseline("DICT_VALUE", "6"),
            new PackedRefSemanticsCase("BUILTIN_PROPERTY_MUTATION", "7a", AssertionGate.DEFERRED_FRONTEND_WRITEBACK_ROUTES, false),
            baseline("BUILTIN_PROPERTY_REASSIGN", "7b"),
            baseline("PLUS_EQUALS_REBIND", "8"),
            baseline("DUPLICATE", "9"),
            asserted("SIGNAL_ARG", "10"),
            asserted("FOR_ITER", "12"),
            asserted("APPEND_ARRAY_ALIAS", "13"),
            asserted("RESIZE_ALIAS", "14"),
            asserted("INDEX_WRITE_ALIAS", "15"),
            asserted("VARIANT_IDENTITY", "16"),
            baseline("PARAM_DEFAULT_SHARED", "17"),
            baseline("ELEMENT_REBIND", "18"),
            baseline("STRING_ITER_ELEMENTS", "19"),
            baseline("IN_MEMBERSHIP", "20"),
            asserted("EQUALITY", "21"),
            asserted("DICT_KEY_HASH", "21-hash"),
            asserted("AS_SAME_FAMILY", "22"),
            new PackedRefSemanticsCase("CORO_AWAIT", "23", AssertionGate.DEFERRED_FULL_MATRIX_ACCEPTANCE, false),
            new PackedRefSemanticsCase("SIGNAL_MULTI", "24", AssertionGate.DEFERRED_FULL_MATRIX_ACCEPTANCE, false),
            new PackedRefSemanticsCase("DYNAMIC_VARIANT_MUTATION", "dyn-variant", AssertionGate.DEFERRED_FRONTEND_WRITEBACK_ROUTES, false),
            // STATIC_VAR（§2-4）：静态 bare 属性 route 的可写 promotion 被 static-terminal 合同
            // fail-closed（编译期），置于伴随库单独编译；route 改造落地后迁回主库。
            new PackedRefSemanticsCase("STATIC_VAR", "4", AssertionGate.DEFERRED_FRONTEND_WRITEBACK_ROUTES, false),
            // LAMBDA_CAPTURE（§2-11）：对 CAPTURE binding 的 mutating 调用在 direct-slot alias
            // 发布处被否决（编译期）；route 改造落地后迁回主库。
            new PackedRefSemanticsCase("LAMBDA_CAPTURE", "11", AssertionGate.DEFERRED_FRONTEND_WRITEBACK_ROUTES, false)
    );
}
