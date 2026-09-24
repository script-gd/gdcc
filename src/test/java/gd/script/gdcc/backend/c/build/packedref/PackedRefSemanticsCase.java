package gd.script.gdcc.backend.c.build.packedref;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Registry linking every `PROBE|<CASE>` emitted by `packed_ref_probes.gd` to its row in
/// packed_array_reference_semantics_plan.md §2 and to the phase at which the gdcc-compiled
/// side is expected to match the interpreter golden.
///
/// Ordering is contractual: entries follow the fixed `PackedRefProbes.run_all` execution
/// order, which is also the golden file's line order.
///
/// Phase semantics (plan §6/§8):
/// - `A`: enabled now — current gdcc already matches the golden, so the dual-run test asserts
///   it from Phase A onward;
/// - `C`: blocked on the Phase C backend storage-model switch;
/// - `D`: blocked on the Phase D frontend gate/route adjustments (§5);
/// - `F`: scheduled for the Phase F full acceptance (plan §8 pins rows 23 and 24 there).
public record PackedRefSemanticsCase(
        @NotNull String probeName,
        @NotNull String matrixRow,
        @NotNull EnablePhase enablePhase
) {
    /// Phase at which a case's gdcc-side golden alignment becomes mandatory.
    public enum EnablePhase {
        A, C, D, F
    }

    public PackedRefSemanticsCase {
        Objects.requireNonNull(probeName, "probeName must not be null");
        Objects.requireNonNull(matrixRow, "matrixRow must not be null");
        Objects.requireNonNull(enablePhase, "enablePhase must not be null");
        if (!probeName.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("probeName must match [A-Z0-9_]+ (ProbeOutput contract): " + probeName);
        }
    }

    /// All registered cases in `run_all`/golden order.
    public static @NotNull List<PackedRefSemanticsCase> cases() {
        return CASES;
    }

    /// Names of cases whose gdcc-side output is asserted against the golden from Phase A on.
    public static @NotNull Set<String> phaseAEnabledCaseNames() {
        var enabled = new LinkedHashSet<String>();
        for (var probeCase : CASES) {
            if (probeCase.enablePhase() == EnablePhase.A) {
                enabled.add(probeCase.probeName());
            }
        }
        return Set.copyOf(enabled);
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

    private static final List<PackedRefSemanticsCase> CASES = List.of(
            new PackedRefSemanticsCase("LOCAL_ALIAS", "1", EnablePhase.C),
            new PackedRefSemanticsCase("PARAM_VISIBILITY", "2", EnablePhase.C),
            new PackedRefSemanticsCase("SCRIPT_PROPERTY", "3", EnablePhase.A),
            new PackedRefSemanticsCase("TYPED_ARRAY_ELEMENT", "5", EnablePhase.A),
            new PackedRefSemanticsCase("DICT_VALUE", "6", EnablePhase.A),
            new PackedRefSemanticsCase("BUILTIN_PROPERTY_MUTATION", "7a", EnablePhase.D),
            new PackedRefSemanticsCase("BUILTIN_PROPERTY_REASSIGN", "7b", EnablePhase.A),
            new PackedRefSemanticsCase("PLUS_EQUALS_REBIND", "8", EnablePhase.A),
            new PackedRefSemanticsCase("DUPLICATE", "9", EnablePhase.A),
            new PackedRefSemanticsCase("SIGNAL_ARG", "10", EnablePhase.C),
            new PackedRefSemanticsCase("FOR_ITER", "12", EnablePhase.C),
            new PackedRefSemanticsCase("APPEND_ARRAY_ALIAS", "13", EnablePhase.C),
            new PackedRefSemanticsCase("RESIZE_ALIAS", "14", EnablePhase.C),
            new PackedRefSemanticsCase("INDEX_WRITE_ALIAS", "15", EnablePhase.C),
            new PackedRefSemanticsCase("VARIANT_IDENTITY", "16", EnablePhase.C),
            new PackedRefSemanticsCase("PARAM_DEFAULT_SHARED", "17", EnablePhase.A),
            new PackedRefSemanticsCase("ELEMENT_REBIND", "18", EnablePhase.A),
            new PackedRefSemanticsCase("STRING_ITER_ELEMENTS", "19", EnablePhase.A),
            new PackedRefSemanticsCase("IN_MEMBERSHIP", "20", EnablePhase.A),
            new PackedRefSemanticsCase("EQUALITY", "21", EnablePhase.C),
            new PackedRefSemanticsCase("DICT_KEY_HASH", "21-hash", EnablePhase.C),
            new PackedRefSemanticsCase("AS_SAME_FAMILY", "22", EnablePhase.C),
            new PackedRefSemanticsCase("CORO_AWAIT", "23", EnablePhase.F),
            new PackedRefSemanticsCase("SIGNAL_MULTI", "24", EnablePhase.F),
            new PackedRefSemanticsCase("DYNAMIC_VARIANT_MUTATION", "dyn-variant", EnablePhase.D),
            // STATIC_VAR（§2-4）在现行 gdcc 下编译期 fail-closed（静态 bare 属性 route 的
            // 值语义 carrier promotion 被 static-terminal 合同拒绝），置于伴随库单独编译；
            // 预计由 Phase C 存储切换 + Phase D 谓词改造共同解锁，登记 Phase D。
            new PackedRefSemanticsCase("STATIC_VAR", "4", EnablePhase.D),
            // LAMBDA_CAPTURE（§2-11）同样编译期 fail-closed（CAPTURE binding 的 mutating 调用
            // 在 direct-slot alias 发布处被否决，与 family 无关）；Variant 模型下捕获即共享
            // 身份、无需 alias 发布，预计由 Phase D 的 route 改造解锁，登记 Phase D。
            new PackedRefSemanticsCase("LAMBDA_CAPTURE", "11", EnablePhase.D)
    );
}
