package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.PackedRefCNames;
import gd.script.gdcc.type.GdPackedArrayType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Shared emitter for calls whose callee keeps the NATIVE Packed*Array ABI while gdcc-internal
/// storage is Variant-backed (packed_array_reference_semantics_plan.md §4.3.3/§4.3.4/§4.3.6).
/// Applies to builtin-class method wrappers (e.g. `godot_PackedInt32Array_push_back`), builtin
/// constructor wrappers with packed arguments (e.g. `godot_new_Array_with_PackedInt32Array`),
/// generated operator evaluator helpers, and utility wrappers (e.g. `godot_var_to_bytes`).
///
/// Adaptation rules per call position:
/// - packed parameter positions pass `gdcc_packed_<slug>_internal_ptr(<address of the Variant
///   storage>)`, so builtin methods / index / operator evaluation mutate the shared array in place;
/// - packed results are received into a raw struct temporary that is immediately wrapped into the
///   target Variant slot via `gdcc_packed_<slug>_wrap_temp` (whitelist (c) — the produced array is
///   a fresh, independent identity) and the temporary is destroyed inside `wrap_temp`;
/// - every other position keeps the generic by-value / by-pointer rendering.
///
/// All call sites here are fixed-arity: vararg tails are a caller-side contract violation because
/// no packed-involving native wrapper is vararg.
public final class PackedNativeAbiCallSupport {
    private PackedNativeAbiCallSupport() {
    }

    /// True when at least one call position (return or any parameter) crosses the packed
    /// native-ABI boundary and therefore needs this emitter instead of the generic call path.
    public static boolean requiresPackedAdaptation(@NotNull GdType returnType,
                                                   @NotNull List<GdType> paramTypes) {
        if (returnType instanceof GdPackedArrayType) {
            return true;
        }
        for (var paramType : paramTypes) {
            if (paramType instanceof GdPackedArrayType) {
                return true;
            }
        }
        return false;
    }

    /// Emits `calleeName(args...)` with per-position packed adaptation.
    ///
    /// @param target     result slot, or `DiscardRef` for statement-position calls; for non-void
    ///        calls the fresh result is consumed as OWNED (move into slot, or destroy on discard)
    /// @param paramTypes ABI-side parameter types, positionally aligned with `argValues`; the
    ///        instance receiver of a builtin method call is simply the leading entry
    public static void emitCall(@NotNull CBodyBuilder bodyBuilder,
                                @NotNull CBodyBuilder.TargetRef target,
                                @NotNull String calleeName,
                                @NotNull GdType returnType,
                                @NotNull List<GdType> paramTypes,
                                @NotNull List<CBodyBuilder.ValueRef> argValues) {
        Objects.requireNonNull(bodyBuilder, "bodyBuilder must not be null");
        Objects.requireNonNull(target, "target must not be null");
        if (paramTypes.size() != argValues.size()) {
            throw bodyBuilder.invalidInsn("Packed native-ABI call '" + calleeName + "' argument count mismatch: "
                    + paramTypes.size() + " parameters vs " + argValues.size() + " arguments");
        }

        var argCodes = new ArrayList<String>(argValues.size());
        for (var i = 0; i < argValues.size(); i++) {
            argCodes.add(renderArgCode(bodyBuilder, calleeName, i, paramTypes.get(i), argValues.get(i)));
        }
        var callExpr = calleeName + "(" + String.join(", ", argCodes) + ")";

        if (target instanceof CBodyBuilder.DiscardRef) {
            emitDiscardedCall(bodyBuilder, callExpr, returnType);
            return;
        }
        if (returnType instanceof GdVoidType) {
            throw bodyBuilder.invalidInsn("Packed native-ABI call '" + calleeName
                    + "' has void return but a result target was provided");
        }
        if (returnType instanceof GdPackedArrayType packedReturnType) {
            // The wrapper yields a fresh native struct; wrap it into the Variant slot in one move.
            var rawResultName = bodyBuilder.newTempVariable("packed_native_ret", returnType).name();
            bodyBuilder.appendLine(PackedRefCNames.rawStructCType(packedReturnType) + " " + rawResultName
                    + " = " + callExpr + ";");
            bodyBuilder.moveOwnedCallIntoSlot(
                    target,
                    PackedRefCNames.wrapTempExpr(packedReturnType, "&" + rawResultName),
                    returnType
            );
            return;
        }
        bodyBuilder.moveOwnedCallIntoSlot(target, callExpr, returnType);
    }

    /// Packed positions render the Variant-internal pointer; all other positions reuse the
    /// standard argument rendering (by value for primitives / fat objects, by address otherwise).
    private static @NotNull String renderArgCode(@NotNull CBodyBuilder bodyBuilder,
                                                 @NotNull String calleeName,
                                                 int index,
                                                 @NotNull GdType paramType,
                                                 @NotNull CBodyBuilder.ValueRef argValue) {
        var rendered = bodyBuilder.renderArgument(argValue, false);
        if (rendered.preCode() != null && !rendered.preCode().isBlank()) {
            bodyBuilder.appendRaw(rendered.preCode());
        }
        if (!rendered.temps().isEmpty()) {
            throw bodyBuilder.invalidInsn("Packed native-ABI call '" + calleeName + "' argument #" + (index + 1)
                    + " unexpectedly requires temporaries: " + rendered.temps());
        }
        if (paramType instanceof GdPackedArrayType packedParamType) {
            if (!(argValue.type() instanceof GdPackedArrayType)) {
                // A Variant/other-typed argument would reach the internal-pointer getter with an
                // unverified payload kind, which is engine-level UB (plan §7.5); the frontend must
                // unpack/check first instead of routing through this emitter.
                throw bodyBuilder.invalidInsn("Packed native-ABI call '" + calleeName + "' argument #" + (index + 1)
                        + " must be statically packed (got '" + argValue.type().getTypeName() + "')");
            }
            return PackedRefCNames.internalPtrExpr(packedParamType, rendered.code());
        }
        return rendered.code();
    }

    /// Discard handling: void calls emit plainly; packed results are received into a raw struct
    /// temp destroyed right away; other destroyable results follow the ordinary temp+destroy path.
    private static void emitDiscardedCall(@NotNull CBodyBuilder bodyBuilder,
                                          @NotNull String callExpr,
                                          @NotNull GdType returnType) {
        if (returnType instanceof GdVoidType) {
            bodyBuilder.appendLine(callExpr + ";");
            return;
        }
        if (returnType instanceof GdPackedArrayType packedReturnType) {
            var rawDiscardName = bodyBuilder.newTempVariable("packed_native_discard", returnType).name();
            bodyBuilder.appendLine(PackedRefCNames.rawStructCType(packedReturnType) + " " + rawDiscardName
                    + " = " + callExpr + ";");
            bodyBuilder.appendLine(PackedRefCNames.rawStructCType(packedReturnType) + "_destroy(&"
                    + rawDiscardName + ");");
            return;
        }
        var discardTemp = bodyBuilder.newTempVariable("discard", returnType, callExpr);
        bodyBuilder.declareTempVar(discardTemp);
        bodyBuilder.destroyTempVar(discardTemp);
    }
}
