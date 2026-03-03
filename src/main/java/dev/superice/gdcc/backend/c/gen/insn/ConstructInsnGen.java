package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.ConstructArrayInsn;
import dev.superice.gdcc.lir.insn.ConstructBuiltinInsn;
import dev.superice.gdcc.lir.insn.ConstructDictionaryInsn;
import dev.superice.gdcc.lir.insn.ConstructionInstruction;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdPackedArrayType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/// C code generator for construct instructions:
/// - construct_builtin
/// - construct_array
/// - construct_dictionary
public final class ConstructInsnGen implements CInsnGen<ConstructionInstruction> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(
                GdInstruction.CONSTRUCT_BUILTIN,
                GdInstruction.CONSTRUCT_ARRAY,
                GdInstruction.CONSTRUCT_DICTIONARY
        );
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        var resultVar = resolveResultVariable(bodyBuilder, instruction);
        var target = bodyBuilder.targetOfVar(resultVar);

        try {
            switch (instruction) {
                case ConstructBuiltinInsn(_, var args) -> {
                    var ctorArgs = resolveConstructorArguments(bodyBuilder, args);
                    bodyBuilder.helper().builtinBuilder().constructBuiltin(bodyBuilder, target, ctorArgs);
                }
                case ConstructArrayInsn(_, var className) -> {
                    switch (resultVar.type()) {
                        case GdArrayType arrayType -> {
                            validateArrayTypeHint(bodyBuilder, className, arrayType);
                            bodyBuilder.helper().builtinBuilder().constructBuiltin(bodyBuilder, target, List.of());
                        }
                        case GdPackedArrayType _ -> {
                            validatePackedArrayTypeHint(bodyBuilder, className);
                            bodyBuilder.helper().builtinBuilder().constructBuiltin(bodyBuilder, target, List.of());
                        }
                        default -> throw bodyBuilder.invalidInsn(
                                "Result variable ID '" + resultVar.id() + "' must be Array or Packed*Array type"
                        );
                    }
                }
                case ConstructDictionaryInsn(_, var keyClassName, var valueClassName) -> {
                    if (!(resultVar.type() instanceof GdDictionaryType dictionaryType)) {
                        throw bodyBuilder.invalidInsn("Result variable ID '" + resultVar.id() + "' must be Dictionary type");
                    }
                    validateDictionaryTypeHint(bodyBuilder, keyClassName, valueClassName, dictionaryType);
                    bodyBuilder.helper().builtinBuilder().constructBuiltin(bodyBuilder, target, List.of());
                }
                default -> throw bodyBuilder.invalidInsn(
                        "Unsupported construction instruction: " + instruction.opcode().opcode()
                );
            }
        } catch (IllegalArgumentException ex) {
            throw bodyBuilder.invalidInsn(ex.getMessage());
        }
    }

    private @NotNull LirVariable resolveResultVariable(@NotNull CBodyBuilder bodyBuilder,
                                                       @NotNull ConstructionInstruction instruction) {
        var resultId = instruction.resultId();
        if (resultId == null) {
            throw bodyBuilder.invalidInsn("Construction instruction missing result variable ID");
        }
        var resultVar = bodyBuilder.func().getVariableById(resultId);
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' does not exist");
        }
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' cannot be a reference");
        }
        return resultVar;
    }

    private @NotNull List<CBodyBuilder.ValueRef> resolveConstructorArguments(@NotNull CBodyBuilder bodyBuilder,
                                                                             @NotNull List<LirInstruction.Operand> operands) {
        var args = new ArrayList<CBodyBuilder.ValueRef>(operands.size());
        for (var i = 0; i < operands.size(); i++) {
            var operand = operands.get(i);
            if (!(operand instanceof LirInstruction.VariableOperand(var variableId))) {
                throw bodyBuilder.invalidInsn("construct_builtin argument #" + (i + 1) + " must be a variable operand");
            }
            var variable = bodyBuilder.func().getVariableById(variableId);
            if (variable == null) {
                throw bodyBuilder.invalidInsn("construct_builtin argument variable ID '" + variableId + "' not found");
            }
            args.add(bodyBuilder.valueOfVar(variable));
        }
        return args;
    }

    private void validateArrayTypeHint(@NotNull CBodyBuilder bodyBuilder,
                                       String className,
                                       @NotNull GdArrayType resultType) {
        var expectedElementType = resolveContainerTypeHint(
                bodyBuilder,
                className,
                "construct_array"
        );
        var actualElementType = resultType.getValueType();
        if (!hasSameRenderedTypeName(bodyBuilder, expectedElementType, actualElementType)) {
            throw bodyBuilder.invalidInsn(
                    "construct_array type mismatch: operand element type '" +
                            renderTypeName(bodyBuilder, expectedElementType) +
                            "' does not match result variable element type '" +
                            renderTypeName(bodyBuilder, actualElementType) + "'"
            );
        }
    }

    private void validatePackedArrayTypeHint(@NotNull CBodyBuilder bodyBuilder,
                                             String className) {
        if (className == null) {
            return;
        }
        throw bodyBuilder.invalidInsn(
                "construct_array for Packed*Array must not provide class_name; " +
                        "packed array construction is inferred from result variable type"
        );
    }

    private void validateDictionaryTypeHint(@NotNull CBodyBuilder bodyBuilder,
                                            String keyClassName,
                                            String valueClassName,
                                            @NotNull GdDictionaryType resultType) {
        var expectedKeyType = resolveContainerTypeHint(
                bodyBuilder,
                keyClassName,
                "construct_dictionary key"
        );
        var expectedValueType = resolveContainerTypeHint(
                bodyBuilder,
                valueClassName,
                "construct_dictionary value"
        );
        if (!hasSameRenderedTypeName(bodyBuilder, expectedKeyType, resultType.getKeyType())) {
            throw bodyBuilder.invalidInsn(
                    "construct_dictionary key type mismatch: operand key type '" +
                            renderTypeName(bodyBuilder, expectedKeyType) +
                            "' does not match result variable key type '" +
                            renderTypeName(bodyBuilder, resultType.getKeyType()) + "'"
            );
        }
        if (!hasSameRenderedTypeName(bodyBuilder, expectedValueType, resultType.getValueType())) {
            throw bodyBuilder.invalidInsn(
                    "construct_dictionary value type mismatch: operand value type '" +
                            renderTypeName(bodyBuilder, expectedValueType) +
                            "' does not match result variable value type '" +
                            renderTypeName(bodyBuilder, resultType.getValueType()) + "'"
            );
        }
    }

    private @NotNull GdType resolveContainerTypeHint(@NotNull CBodyBuilder bodyBuilder,
                                                     String textType,
                                                     @NotNull String hintLabel) {
        if (textType == null) {
            return GdVariantType.VARIANT;
        }
        if (textType.isBlank()) {
            throw bodyBuilder.invalidInsn(hintLabel + " must not be blank");
        }
        var parsedType = ClassRegistry.tryParseTextType(textType);
        if (parsedType == null) {
            throw bodyBuilder.invalidInsn(hintLabel + " '" + textType + "' is not a valid type");
        }
        return parsedType;
    }

    private boolean hasSameRenderedTypeName(@NotNull CBodyBuilder bodyBuilder,
                                            @NotNull GdType expectedType,
                                            @NotNull GdType actualType) {
        return renderTypeName(bodyBuilder, expectedType).equals(renderTypeName(bodyBuilder, actualType));
    }

    private @NotNull String renderTypeName(@NotNull CBodyBuilder bodyBuilder, @NotNull GdType type) {
        return bodyBuilder.helper().renderGdTypeName(type);
    }
}
