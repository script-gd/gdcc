package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.IsInstanceOfInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.resolver.ScopeTypeTextSupport;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.util.TypeTestFoldResult;
import gd.script.gdcc.util.TypeTestFoldUtil;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Objects;

/// Backend codegen for the unified `is_instance_of` LIR surface (GDScript `is` / `is not`).
///
/// Contract:
/// - single LIR opcode; all path choice lives here (no frontend multi-instruction recipes)
/// - static fold reuses {@link TypeTestFoldUtil} as insurance for still-emitted LIR
/// - `Variant` target is the top type: fold true for any operand (never dispatch / never NIL enum)
/// - unresolved legal object type names always take the runtime ClassDB path and never fold
/// - unused result (`resultId == null`) is a no-op after type_name validation
/// - null / freed objects are false for non-Variant targets (never reuse unpack null→true)
/// - parameterized containers compare typed metadata via dedicated helpers, not bare ARRAY enum
public final class IsInstanceOfInsnGen implements CInsnGen<IsInstanceOfInsn> {
    private final OperatorResolver resolver = new OperatorResolver();

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.IS_INSTANCE_OF);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var typeName = insn.typeName().trim();
        if (typeName.isEmpty()) {
            throw bodyBuilder.invalidInsn("is_instance_of type_name must not be empty");
        }

        // Pure predicate with no side effects: unused result is a validated no-op.
        if (insn.resultId() == null) {
            return;
        }

        var valueVariable = requireVariable(bodyBuilder, insn.valueId(), "value");
        var resultVariable = requireVariable(bodyBuilder, insn.resultId(), "result");
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, valueVariable, "is_instance_of value");
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, resultVariable, "is_instance_of result");
        if (!(resultVariable.type() instanceof GdBoolType)) {
            throw bodyBuilder.invalidInsn(
                    "is_instance_of result '" + resultVariable.id() + "' must be bool, got '" +
                            resultVariable.type().getTypeName() + "'"
            );
        }

        var registry = bodyBuilder.classRegistry();
        var resolvedTarget = registry.tryResolveDeclaredType(typeName);
        // Only legal bare identifiers may degrade to unresolved object targets (mirrors frontend).
        // Malformed names fail closed via invalidInsn instead of the ClassDB runtime path.
        var unresolvedObjectTarget = resolvedTarget == null
                && !ScopeTypeTextSupport.looksStructuredTypeText(typeName)
                && ClassRegistry.isLegalGodotIdentifier(typeName)
                && ClassRegistry.tryParseStrictTextType(typeName, registry) == null;

        if (resolvedTarget == null && !unresolvedObjectTarget) {
            throw bodyBuilder.invalidInsn(
                    "is_instance_of type_name '" + typeName + "' cannot be resolved for codegen"
            );
        }

        if (!unresolvedObjectTarget) {
            var folded = TypeTestFoldUtil.fold(
                    registry,
                    valueVariable.type(),
                    Objects.requireNonNull(resolvedTarget)
            );
            if (folded != TypeTestFoldResult.RUNTIME_OPEN) {
                bodyBuilder.assignExpr(
                        bodyBuilder.targetOfVar(resultVariable),
                        Boolean.toString(folded == TypeTestFoldResult.TRUE),
                        GdBoolType.BOOL
                );
                return;
            }
        }

        if (unresolvedObjectTarget) {
            emitObjectRuntimePath(bodyBuilder, resultVariable, valueVariable, typeName);
            return;
        }

        var target = Objects.requireNonNull(resolvedTarget);
        switch (target) {
            case GdObjectType objectTarget -> {
                if (valueVariable.type() instanceof GdObjectType
                        && bodyBuilder.classRegistry().checkAssignable(valueVariable.type(), objectTarget)) {
                    emitProvenUpcastNullCheck(bodyBuilder, resultVariable, valueVariable);
                } else {
                    emitObjectRuntimePath(bodyBuilder, resultVariable, valueVariable, objectTarget.getTypeName());
                }
                return;
            }
            case GdArrayType arrayTarget when !arrayTarget.isGenericArray() -> {
                emitTypedArrayPath(bodyBuilder, resultVariable, valueVariable, arrayTarget);
                return;
            }
            case GdDictionaryType dictionaryTarget when !dictionaryTarget.isGenericDictionary() -> {
                emitTypedDictionaryPath(bodyBuilder, resultVariable, valueVariable, dictionaryTarget);
                return;
            }
            default -> {
            }
        }
        if (isNonParameterizedBuiltinTarget(target)) {
            emitBuiltinVariantTypePath(bodyBuilder, resultVariable, valueVariable, target);
            return;
        }

        throw bodyBuilder.invalidInsn(
                "is_instance_of does not support target type '" + target.getTypeName() +
                        "' with value type '" + valueVariable.type().getTypeName() + "'"
        );
    }

    private static boolean isNonParameterizedBuiltinTarget(@NotNull GdType target) {
        if (target instanceof GdObjectType || target instanceof GdVariantType || target instanceof GdNilType) {
            return false;
        }
        if (target instanceof GdArrayType arrayType) {
            return arrayType.isGenericArray();
        }
        if (target instanceof GdDictionaryType dictionaryType) {
            return dictionaryType.isGenericDictionary();
        }
        return target.getGdExtensionType() != null;
    }

    private void emitBuiltinVariantTypePath(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull LirVariable resultVariable,
            @NotNull LirVariable valueVariable,
            @NotNull GdType targetType
    ) {
        var expectedTypeLiteral = resolver.resolveVariantTypeEnumLiteral(bodyBuilder, targetType);
        // Non-Variant ordinary values still pack once so get_type is the single runtime predicate.
        var operand = InsnGenSupport.materializeVariantOperand(bodyBuilder, valueVariable, "is_type");
        var variantCode = InsnGenSupport.renderArgumentCode(
                bodyBuilder,
                operand.variantValue(),
                "is_instance_of builtin"
        );
        bodyBuilder.assignExpr(
                bodyBuilder.targetOfVar(resultVariable),
                "(godot_variant_get_type(" + addressOf(variantCode) + ") == " + expectedTypeLiteral + ")",
                GdBoolType.BOOL
        );
        if (operand.tempVar() != null) {
            bodyBuilder.destroyTempVar(operand.tempVar());
        }
    }

    private void emitObjectRuntimePath(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull LirVariable resultVariable,
            @NotNull LirVariable valueVariable,
            @NotNull String expectedClassName
    ) {
        var expectedClassLiteral = CBodyBuilder.renderStaticStringNameLiteral(expectedClassName);
        var valueType = valueVariable.type();
        if (valueType instanceof GdObjectType) {
            var objectCode = bodyBuilder.valueOfVar(valueVariable).generateCode();
            var rawOperand = bodyBuilder.renderNullQueryRawOperand(objectCode);
            bodyBuilder.assignExpr(
                    bodyBuilder.targetOfVar(resultVariable),
                    "gdcc_is_instance_of_object_raw_and_id(" + rawOperand + ", " + objectCode +
                            ".instance_id, " + expectedClassLiteral + ")",
                    GdBoolType.BOOL
            );
            return;
        }
        if (valueType instanceof GdVariantType || valueType instanceof GdNilType) {
            var operand = InsnGenSupport.materializeVariantOperand(bodyBuilder, valueVariable, "is_obj");
            var variantCode = InsnGenSupport.renderArgumentCode(
                    bodyBuilder,
                    operand.variantValue(),
                    "is_instance_of object"
            );
            bodyBuilder.assignExpr(
                    bodyBuilder.targetOfVar(resultVariable),
                    "gdcc_is_instance_of_object_variant(" + addressOf(variantCode) + ", " +
                            expectedClassLiteral + ")",
                    GdBoolType.BOOL
            );
            if (operand.tempVar() != null) {
                bodyBuilder.destroyTempVar(operand.tempVar());
            }
            return;
        }
        // Exact non-object ordinary value can never be an object class at runtime
        // (including UNRESOLVED_OBJECT targets: the target contract forbids true-folding, not a
        // useless runtime call).
        bodyBuilder.assignExpr(bodyBuilder.targetOfVar(resultVariable), "false", GdBoolType.BOOL);
    }

    /// Static type proves inheritance (same or upcast), so the only false path is null.
    private void emitProvenUpcastNullCheck(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull LirVariable resultVariable,
            @NotNull LirVariable valueVariable
    ) {
        var objectCode = bodyBuilder.valueOfVar(valueVariable).generateCode();
        bodyBuilder.assignExpr(
                bodyBuilder.targetOfVar(resultVariable),
                "!(" + bodyBuilder.renderObjectIsNullExpr(objectCode) + ")",
                GdBoolType.BOOL
        );
    }

    private void emitTypedArrayPath(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull LirVariable resultVariable,
            @NotNull LirVariable valueVariable,
            @NotNull GdArrayType targetArray
    ) {
        var helper = bodyBuilder.helper();
        var expectedBuiltin = helper.renderTypedArrayGuardBuiltinTypeLiteral(targetArray);
        var expectedClass = helper.renderTypedArrayGuardClassNameExpr(targetArray);
        var valueType = valueVariable.type();

        if (valueType instanceof GdArrayType) {
            var arrayCode = bodyBuilder.valueOfVar(valueVariable).generateCode();
            bodyBuilder.assignExpr(
                    bodyBuilder.targetOfVar(resultVariable),
                    "gdcc_is_instance_of_typed_array(" + addressOf(arrayCode) + ", " + expectedBuiltin +
                            ", " + expectedClass + ")",
                    GdBoolType.BOOL
            );
            return;
        }
        if (valueType instanceof GdVariantType) {
            var operand = InsnGenSupport.materializeVariantOperand(bodyBuilder, valueVariable, "is_tarr");
            var variantCode = InsnGenSupport.renderArgumentCode(
                    bodyBuilder,
                    operand.variantValue(),
                    "is_instance_of typed array"
            );
            bodyBuilder.assignExpr(
                    bodyBuilder.targetOfVar(resultVariable),
                    "gdcc_is_instance_of_typed_array_variant(" + addressOf(variantCode) + ", " +
                            expectedBuiltin + ", " + expectedClass + ")",
                    GdBoolType.BOOL
            );
            if (operand.tempVar() != null) {
                bodyBuilder.destroyTempVar(operand.tempVar());
            }
            return;
        }
        bodyBuilder.assignExpr(bodyBuilder.targetOfVar(resultVariable), "false", GdBoolType.BOOL);
    }

    private void emitTypedDictionaryPath(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull LirVariable resultVariable,
            @NotNull LirVariable valueVariable,
            @NotNull GdDictionaryType targetDictionary
    ) {
        var helper = bodyBuilder.helper();
        var keyBuiltin = helper.renderTypedDictionaryGuardBuiltinTypeLiteral(targetDictionary, "key");
        var keyClass = helper.renderTypedDictionaryGuardClassNameExpr(targetDictionary, "key");
        var valueBuiltin = helper.renderTypedDictionaryGuardBuiltinTypeLiteral(targetDictionary, "value");
        var valueClass = helper.renderTypedDictionaryGuardClassNameExpr(targetDictionary, "value");
        var valueType = valueVariable.type();

        if (valueType instanceof GdDictionaryType) {
            var dictCode = bodyBuilder.valueOfVar(valueVariable).generateCode();
            bodyBuilder.assignExpr(
                    bodyBuilder.targetOfVar(resultVariable),
                    "gdcc_is_instance_of_typed_dictionary(" + addressOf(dictCode) + ", " + keyBuiltin +
                            ", " + keyClass + ", " + valueBuiltin + ", " + valueClass + ")",
                    GdBoolType.BOOL
            );
            return;
        }
        if (valueType instanceof GdVariantType) {
            var operand = InsnGenSupport.materializeVariantOperand(bodyBuilder, valueVariable, "is_tdict");
            var variantCode = InsnGenSupport.renderArgumentCode(
                    bodyBuilder,
                    operand.variantValue(),
                    "is_instance_of typed dictionary"
            );
            bodyBuilder.assignExpr(
                    bodyBuilder.targetOfVar(resultVariable),
                    "gdcc_is_instance_of_typed_dictionary_variant(" + addressOf(variantCode) + ", " +
                            keyBuiltin + ", " + keyClass + ", " + valueBuiltin + ", " + valueClass + ")",
                    GdBoolType.BOOL
            );
            if (operand.tempVar() != null) {
                bodyBuilder.destroyTempVar(operand.tempVar());
            }
            return;
        }
        bodyBuilder.assignExpr(bodyBuilder.targetOfVar(resultVariable), "false", GdBoolType.BOOL);
    }

    private static @NotNull LirVariable requireVariable(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull String variableId,
            @NotNull String role
    ) {
        var variable = bodyBuilder.func().getVariableById(variableId);
        if (variable == null) {
            throw bodyBuilder.invalidInsn("is_instance_of " + role + " variable not found: " + variableId);
        }
        return variable;
    }

    /// LIR locals render as `$id`; take address for pointer APIs.
    private static @NotNull String addressOf(@NotNull String expression) {
        if (expression.startsWith("&")) {
            return expression;
        }
        return "&" + expression;
    }
}
