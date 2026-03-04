package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Shared helper for instruction generators.
///
/// Centralizes:
/// - variant operand materialization (pack non-Variant to temporary Variant)
/// - argument code rendering with preCode handling and temp-guard validation
final class InsnGenSupport {
    private InsnGenSupport() {
    }

    static @NotNull VariantOperand materializeVariantOperand(@NotNull CBodyBuilder bodyBuilder,
                                                             @NotNull LirVariable operandVar,
                                                             @NotNull String tempPrefix) {
        if (operandVar.type() instanceof GdVariantType) {
            return new VariantOperand(bodyBuilder.valueOfVar(operandVar), null);
        }

        var tempVariant = bodyBuilder.newTempVariable(tempPrefix, GdVariantType.VARIANT);
        bodyBuilder.declareTempVar(tempVariant);
        var packFunctionName = bodyBuilder.helper().renderPackFunctionName(operandVar.type());
        bodyBuilder.callAssign(
                tempVariant,
                packFunctionName,
                GdVariantType.VARIANT,
                List.of(bodyBuilder.valueOfVar(operandVar))
        );
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
