package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Shared helper for instruction generators.
///
/// Centralizes:
/// - variant operand materialization (pack non-Variant to temporary Variant)
/// - Nil -> Variant materialization via the dedicated nullary constructor
/// - argument code rendering with preCode handling and temp-guard validation
final class InsnGenSupport {
    private InsnGenSupport() {
    }

    static void rejectCompilerOnlyVariable(@NotNull CBodyBuilder bodyBuilder,
                                           @NotNull LirVariable variable,
                                           @NotNull String useSite) {
        if (variable.type() instanceof GdCompilerType compilerOnlyType) {
            throw bodyBuilder.invalidInsn(
                    "compiler-only type leaked into " + useSite + " variable '" + variable.id() +
                            "': " + compilerOnlyType.getTypeName()
            );
        }
    }

    static void rejectCompilerOnlyType(@NotNull CBodyBuilder bodyBuilder,
                                       @NotNull GdType type,
                                       @NotNull String useSite) {
        if (type instanceof GdCompilerType compilerOnlyType) {
            throw bodyBuilder.invalidInsn(
                    "compiler-only type leaked into " + useSite + ": " + compilerOnlyType.getTypeName()
            );
        }
    }

    /// `Nil` does not belong to the unary `godot_new_Variant_with_<Type>` family.
    static void packVariantAssign(@NotNull CBodyBuilder bodyBuilder,
                                  @NotNull CBodyBuilder.TargetRef target,
                                  @NotNull LirVariable valueVar) {
        rejectCompilerOnlyVariable(bodyBuilder, valueVar, "Variant pack source");
        if (valueVar.type() instanceof GdNilType) {
            bodyBuilder.callAssign(target, "godot_new_Variant_nil", GdVariantType.VARIANT, List.of());
            return;
        }

        var packFunctionName = bodyBuilder.helper().renderPackFunctionName(valueVar.type());
        bodyBuilder.callAssign(target, packFunctionName, GdVariantType.VARIANT, List.of(bodyBuilder.valueOfVar(valueVar)));
    }

    static void unpackVariantAssign(@NotNull CBodyBuilder bodyBuilder,
                                    @NotNull CBodyBuilder.TargetRef target,
                                    @NotNull GdType targetType,
                                    @NotNull CBodyBuilder.ValueRef variantValue,
                                    @NotNull String useSite) {
        rejectCompilerOnlyType(bodyBuilder, targetType, useSite);
        var unpackFunctionName = bodyBuilder.helper().renderUnpackFunctionName(targetType);
        if (targetType instanceof GdObjectType objectType) {
            // Object unpack materializes a BORROWED fat pointer; destination slot decides retain.
            var variantArg = bodyBuilder.renderArgument(variantValue, false);
            if (variantArg.preCode() != null && !variantArg.preCode().isBlank()) {
                bodyBuilder.appendRaw(variantArg.preCode());
            }
            if (!variantArg.temps().isEmpty()) {
                throw bodyBuilder.invalidInsn("object Variant unpack must not require temporaries at " + useSite);
            }
            bodyBuilder.assignVar(
                    target,
                    bodyBuilder.valueOfExpr(
                            unpackFunctionName + "(" + variantArg.code() + ")",
                            objectType,
                            CBodyBuilder.PtrKind.FAT_PTR
                    )
            );
            return;
        }
        bodyBuilder.callAssign(target, unpackFunctionName, targetType, List.of(variantValue));
    }

    static @NotNull VariantOperand materializeVariantOperand(@NotNull CBodyBuilder bodyBuilder,
                                                             @NotNull LirVariable operandVar,
                                                             @NotNull String tempPrefix) {
        if (operandVar.type() instanceof GdVariantType) {
            return new VariantOperand(bodyBuilder.valueOfVar(operandVar), null);
        }

        var tempVariant = bodyBuilder.newTempVariable(tempPrefix, GdVariantType.VARIANT);
        bodyBuilder.declareTempVar(tempVariant);
        packVariantAssign(bodyBuilder, tempVariant, operandVar);
        return new VariantOperand(tempVariant, tempVariant);
    }

    static @NotNull String renderArgumentCode(@NotNull CBodyBuilder bodyBuilder,
                                              @NotNull CBodyBuilder.ValueRef valueRef,
                                              @NotNull String instructionContext) {
        var rendered = bodyBuilder.renderArgument(valueRef, false);
        if (rendered.preCode() != null && !rendered.preCode().isBlank()) {
            bodyBuilder.appendRaw(rendered.preCode());
        }
        if (!rendered.temps().isEmpty()) {
            throw bodyBuilder.invalidInsn("Unexpected temporary variables in argument code for " +
                    instructionContext + ": " +
                    rendered.temps().stream().map(CBodyBuilder.TempVar::name).toList());
        }
        return rendered.code();
    }

    record VariantOperand(@NotNull CBodyBuilder.ValueRef variantValue,
                          @Nullable CBodyBuilder.TempVar tempVar) {
        VariantOperand {
            Objects.requireNonNull(variantValue);
        }
    }
}
