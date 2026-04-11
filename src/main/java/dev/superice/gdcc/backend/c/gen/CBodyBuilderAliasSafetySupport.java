package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Shared alias-safety classifier for destroyable non-object slot writes.
///
/// The contract is intentionally sound over minimal:
/// - only directly provable disjoint storage pairs are `PROVEN_NO_ALIAS`
/// - every unproven or future value/target surface stays `MAY_ALIAS`
/// - classification is phrased only in terms of the current sealed `ValueRef` / `TargetRef` surface,
///   so future additions have to update this file instead of silently falling through a builder-local default
final class CBodyBuilderAliasSafetySupport {
    private CBodyBuilderAliasSafetySupport() {
    }

    /// Answers whether one overwrite must stage an independent stable carrier before destroying the
    /// destination slot. This keeps the entire alias-safety policy in one place:
    /// - overwrite preconditions (`!__prepare__`, `canDestroyOldValue`, destroyable non-object target)
    /// - source provenance restrictions (`BORROWED` + copy helper available)
    /// - the actual no-alias vs may-alias proof on the current sealed source/target surface
    static boolean requiresStableCarrier(
            boolean inPrepareBlock,
            boolean canDestroyOldValue,
            @NotNull CBodyBuilder.TargetRef target,
            @NotNull CBodyBuilder.ValueRef value,
            boolean hasCopyHelper
    ) {
        if (inPrepareBlock || !canDestroyOldValue) {
            return false;
        }
        var targetType = target.type();
        if (!targetType.isDestroyable() || targetType instanceof GdObjectType) {
            return false;
        }
        if (value.ownership() != CBodyBuilder.OwnershipKind.BORROWED || !hasCopyHelper) {
            return false;
        }
        return classifyNonObjectSlotWriteAliasSafety(target, value) == NonObjectSlotWriteAliasSafety.MAY_ALIAS;
    }

    static @NotNull NonObjectSlotWriteAliasSafety classifyNonObjectSlotWriteAliasSafety(
            @NotNull CBodyBuilder.TargetRef target,
            @NotNull CBodyBuilder.ValueRef value
    ) {
        return switch (Objects.requireNonNull(target, "target must not be null")) {
            case CBodyBuilder.VarTargetRef varTargetRef -> classifyVarTargetAliasSafety(
                    varTargetRef,
                    Objects.requireNonNull(value, "value must not be null")
            );
            case CBodyBuilder.TempVar tempVar -> classifyTempTargetAliasSafety(
                    tempVar,
                    Objects.requireNonNull(value, "value must not be null")
            );
            case CBodyBuilder.ExprTargetRef _ -> classifyExprTargetAliasSafety(
                    Objects.requireNonNull(value, "value must not be null")
            );
            case CBodyBuilder.DiscardRef _ -> throw new IllegalStateException("DiscardRef is not assignable");
        };
    }

    private static @NotNull NonObjectSlotWriteAliasSafety classifyVarTargetAliasSafety(
            @NotNull CBodyBuilder.VarTargetRef target,
            @NotNull CBodyBuilder.ValueRef value
    ) {
        return switch (value) {
            case CBodyBuilder.VarValue varValue -> {
                if (Objects.equals(varValue.variable(), target.variable()) || varValue.variable().ref()) {
                    yield NonObjectSlotWriteAliasSafety.MAY_ALIAS;
                }
                yield NonObjectSlotWriteAliasSafety.PROVEN_NO_ALIAS;
            }
            case CBodyBuilder.AddressableExprValue _ -> NonObjectSlotWriteAliasSafety.MAY_ALIAS;
            case CBodyBuilder.ExprValue _ -> NonObjectSlotWriteAliasSafety.PROVEN_NO_ALIAS;
            case CBodyBuilder.StringNamePtrLiteralValue _,
                 CBodyBuilder.StringPtrLiteralValue _,
                 CBodyBuilder.CStringLiteralValue _ -> NonObjectSlotWriteAliasSafety.PROVEN_NO_ALIAS;
            case CBodyBuilder.TempVar _ -> NonObjectSlotWriteAliasSafety.PROVEN_NO_ALIAS;
        };
    }

    private static @NotNull NonObjectSlotWriteAliasSafety classifyTempTargetAliasSafety(
            @NotNull CBodyBuilder.TempVar target,
            @NotNull CBodyBuilder.ValueRef value
    ) {
        return switch (value) {
            case CBodyBuilder.TempVar tempVar -> tempVar == target
                    ? NonObjectSlotWriteAliasSafety.MAY_ALIAS
                    : NonObjectSlotWriteAliasSafety.PROVEN_NO_ALIAS;
            case CBodyBuilder.VarValue varValue -> varValue.variable().ref()
                    ? NonObjectSlotWriteAliasSafety.MAY_ALIAS
                    : NonObjectSlotWriteAliasSafety.PROVEN_NO_ALIAS;
            case CBodyBuilder.AddressableExprValue _ -> NonObjectSlotWriteAliasSafety.MAY_ALIAS;
            case CBodyBuilder.ExprValue _ -> NonObjectSlotWriteAliasSafety.PROVEN_NO_ALIAS;
            case CBodyBuilder.StringNamePtrLiteralValue _,
                 CBodyBuilder.StringPtrLiteralValue _,
                 CBodyBuilder.CStringLiteralValue _ -> NonObjectSlotWriteAliasSafety.PROVEN_NO_ALIAS;
        };
    }

    private static @NotNull NonObjectSlotWriteAliasSafety classifyExprTargetAliasSafety(
            @NotNull CBodyBuilder.ValueRef value
    ) {
        return switch (value) {
            case CBodyBuilder.ExprValue _,
                 CBodyBuilder.StringNamePtrLiteralValue _,
                 CBodyBuilder.StringPtrLiteralValue _,
                 CBodyBuilder.CStringLiteralValue _ -> NonObjectSlotWriteAliasSafety.PROVEN_NO_ALIAS;
            case CBodyBuilder.VarValue _,
                 CBodyBuilder.AddressableExprValue _,
                 CBodyBuilder.TempVar _ -> NonObjectSlotWriteAliasSafety.MAY_ALIAS;
        };
    }

    enum NonObjectSlotWriteAliasSafety {
        PROVEN_NO_ALIAS,
        MAY_ALIAS
    }
}
