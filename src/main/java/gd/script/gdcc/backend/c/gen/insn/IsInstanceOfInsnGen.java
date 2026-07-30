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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Objects;

/// Backend codegen for the unified `is_instance_of` LIR surface (GDScript `is` / `is not`).
///
/// Contract (plan §3.3 / §3.4 / §3.5):
/// - single LIR opcode; all path choice lives here (no frontend multi-instruction recipes)
/// - fold only when value static type + resolved target decide the outcome
/// - unresolved object type names always take the runtime ClassDB path and never fold
/// - null / freed objects are false (never reuse `gdcc_check_variant_type_object` unpack null→true)
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
        var valueVariable = requireVariable(bodyBuilder, insn.valueId(), "value");
        var resultVariable = requireVariable(
                bodyBuilder,
                Objects.requireNonNull(insn.resultId(), "is_instance_of resultId"),
                "result"
        );
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, valueVariable, "is_instance_of value");
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, resultVariable, "is_instance_of result");
        if (!(resultVariable.type() instanceof GdBoolType)) {
            throw bodyBuilder.invalidInsn(
                    "is_instance_of result '" + resultVariable.id() + "' must be bool, got '" +
                            resultVariable.type().getTypeName() + "'"
            );
        }

        var typeName = insn.typeName().trim();
        if (typeName.isEmpty()) {
            throw bodyBuilder.invalidInsn("is_instance_of type_name must not be empty");
        }

        var registry = bodyBuilder.classRegistry();
        var resolvedTarget = registry.tryResolveDeclaredType(typeName);
        // Identifier that survives strict resolution failure is an unresolved object target:
        // force runtime inheritance checks and forbid constant folding
        var unresolvedObjectTarget = resolvedTarget == null
                && !ScopeTypeTextSupport.looksStructuredTypeText(typeName)
                && ClassRegistry.tryParseStrictTextType(typeName, registry) == null;

        if (resolvedTarget == null && !unresolvedObjectTarget) {
            throw bodyBuilder.invalidInsn(
                    "is_instance_of type_name '" + typeName + "' cannot be resolved for codegen"
            );
        }

        if (!unresolvedObjectTarget) {
            var folded = tryFold(registry, valueVariable.type(), Objects.requireNonNull(resolvedTarget));
            if (folded != null) {
                bodyBuilder.assignExpr(
                        bodyBuilder.targetOfVar(resultVariable),
                        folded.toString(),
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
                emitObjectRuntimePath(bodyBuilder, resultVariable, valueVariable, objectTarget.getTypeName());
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

    /// Mirrors frontend `tryFoldKnownTypeTest` as a second insurance layer for still-emitted LIR.
    private static @Nullable Boolean tryFold(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType valueType,
            @NotNull GdType targetType
    ) {
        if (valueType instanceof GdVariantType) {
            return null;
        }
        if (valueType instanceof GdNilType) {
            return false;
        }
        if (sameStaticType(valueType, targetType)) {
            return true;
        }
        if (valueType instanceof GdObjectType
                && targetType instanceof GdObjectType
                && classRegistry.checkAssignable(valueType, targetType)) {
            return true;
        }
        if (valueType instanceof GdObjectType != targetType instanceof GdObjectType) {
            return false;
        }
        // Parent→child object stays open; never fold false without a proven disjoint hierarchy.
        if (valueType instanceof GdObjectType) {
            return null;
        }
        if (targetType instanceof GdArrayType targetArray
                && targetArray.isGenericArray()
                && valueType instanceof GdArrayType) {
            return true;
        }
        // Exact non-object mismatch (including bare→typed container).
        return targetType instanceof GdDictionaryType targetDictionary
                && targetDictionary.isGenericDictionary()
                && valueType instanceof GdDictionaryType;
    }

    private static boolean sameStaticType(@NotNull GdType first, @NotNull GdType second) {
        return first == second
                || (first.getClass() == second.getClass()
                && first.getTypeName().equals(second.getTypeName()));
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
        // (including UNRESOLVED_OBJECT targets: plan forces no true-fold, not a useless runtime call).
        bodyBuilder.assignExpr(bodyBuilder.targetOfVar(resultVariable), "false", GdBoolType.BOOL);
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
