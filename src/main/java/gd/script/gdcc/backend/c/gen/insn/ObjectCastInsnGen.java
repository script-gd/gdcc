package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.ObjectCastInsn;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.util.type.ExplicitCastDecision;
import gd.script.gdcc.util.type.ExplicitCastSupport;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

/// Backend codegen for LIR `object_cast` (GDScript `as` to an Object target).
///
/// Contract (see `frontend_cast_expression_implementation.md` §5.2 / §5.3):
/// - class name is the canonical / Godot-facing runtime name; unresolved names fail closed
/// - `resultId == null` is a validated no-op after checks (no runtime cast)
/// - success keeps the validated live raw + source instance_id via `_from_raw`; failure is
///   canonical null `{NULL, 0}`
/// - ownership-neutral helper; result slot write reuses unified object assignment
/// - must not use `godot_object_cast_to`, `gdcc_check_variant_type_object`, or plain
///   `_fat_ptr_from_variant` as the class check
public final class ObjectCastInsnGen implements CInsnGen<ObjectCastInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.OBJECT_CAST);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var insn = bodyBuilder.getCurrentInsn(this);
        var className = insn.className().trim();
        if (className.isEmpty()) {
            throw bodyBuilder.invalidInsn("object_cast class_name must not be empty");
        }

        var valueVariable = requireVariable(bodyBuilder, insn.valueId(), "value");
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, valueVariable, "object_cast value");

        var registry = bodyBuilder.classRegistry();
        var resolvedTarget = registry.tryResolveDeclaredType(className);
        if (!(resolvedTarget instanceof GdObjectType objectTarget)) {
            // First wave: only registry-proven object classes (engine or registered GDCC).
            // Script-instance-only / unknown bare identifiers fail closed here.
            throw bodyBuilder.invalidInsn(
                    "object_cast class_name '" + className +
                            "' is not a registry-proven object class for codegen"
            );
        }

        if (insn.resultId() == null) {
            // Optional result: validate target/source only; no runtime cast.
            requireSupportedSource(bodyBuilder, valueVariable.type());
            validateDecision(bodyBuilder, valueVariable.type(), objectTarget);
            return;
        }

        var resultVariable = requireVariable(bodyBuilder, insn.resultId(), "result");
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, resultVariable, "object_cast result");
        if (!(resultVariable.type() instanceof GdObjectType resultObjectType)) {
            throw bodyBuilder.invalidInsn(
                    "object_cast result '" + resultVariable.id() + "' must be an object type, got '" +
                            resultVariable.type().getTypeName() + "'"
            );
        }
        if (!resultObjectType.getTypeName().equals(objectTarget.getTypeName())) {
            throw bodyBuilder.invalidInsn(
                    "object_cast result type '" + resultObjectType.getTypeName() +
                            "' does not match class_name '" + objectTarget.getTypeName() + "'"
            );
        }

        requireSupportedSource(bodyBuilder, valueVariable.type());
        validateDecision(bodyBuilder, valueVariable.type(), objectTarget);
        emitRuntimeCast(bodyBuilder, resultVariable, valueVariable, objectTarget);
    }

    private static void emitRuntimeCast(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull LirVariable resultVariable,
            @NotNull LirVariable valueVariable,
            @NotNull GdObjectType targetType
    ) {
        var expectedClassLiteral = CBodyBuilder.renderStaticStringNameLiteral(targetType.getTypeName());
        // Unique C name for the validated raw pointer (not a GdType; raw declaration only).
        var castRaw = bodyBuilder.newTempVariable("ocast_raw", GdBoolType.BOOL);
        var valueType = valueVariable.type();

        if (valueType instanceof GdObjectType) {
            var objectCode = bodyBuilder.valueOfVar(valueVariable).generateCode();
            var rawOperand = bodyBuilder.renderNullQueryRawOperand(objectCode);
            bodyBuilder.appendLine(
                    "GDExtensionObjectPtr " + castRaw.name() + " = gdcc_object_cast_raw_and_id(" +
                            rawOperand + ", " + objectCode + ".instance_id, " + expectedClassLiteral + ");"
            );
        } else if (valueType instanceof GdVariantType || valueType instanceof GdNilType) {
            var operand = InsnGenSupport.materializeVariantOperand(bodyBuilder, valueVariable, "ocast_src");
            var variantCode = InsnGenSupport.renderArgumentCode(
                    bodyBuilder,
                    operand.variantValue(),
                    "object_cast source"
            );
            // renderArgumentCode already yields const Variant* for locals and ref params.
            bodyBuilder.appendLine(
                    "GDExtensionObjectPtr " + castRaw.name() + " = gdcc_object_cast_variant(" +
                            variantCode + ", " + expectedClassLiteral + ");"
            );
            // Source Variant temp is only needed for the cast query; destroy after the call.
            if (operand.tempVar() != null) {
                bodyBuilder.destroyTempVar(operand.tempVar());
            }
        } else {
            throw bodyBuilder.invalidInsn(
                    "object_cast source type '" + valueType.getTypeName() +
                            "' is not Object/Variant/Nil"
            );
        }

        // Success: capture validated raw into target fat pointer (preserves instance_id).
        // Failure: canonical null `{NULL, 0}` — never recover ID from an unvalidated raw.
        var fromRaw = bodyBuilder.renderFatPtrFromRaw(castRaw.name(), targetType);
        var nullExpr = bodyBuilder.helper().renderDefaultValueExprInC(targetType);
        bodyBuilder.assignExpr(
                bodyBuilder.targetOfVar(resultVariable),
                "(" + castRaw.name() + " != NULL ? " + fromRaw + " : " + nullExpr + ")",
                targetType
        );
    }

    private static void requireSupportedSource(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull GdType sourceType
    ) {
        if (sourceType instanceof GdObjectType
                || sourceType instanceof GdVariantType
                || sourceType instanceof GdNilType) {
            return;
        }
        throw bodyBuilder.invalidInsn(
                "object_cast source type '" + sourceType.getTypeName() +
                        "' is not Object/Variant/Nil"
        );
    }

    private static void validateDecision(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull GdType sourceType,
            @NotNull GdObjectType targetType
    ) {
        ExplicitCastDecision decision;
        try {
            decision = ExplicitCastSupport.classify(bodyBuilder.classRegistry(), sourceType, targetType);
        } catch (IllegalArgumentException ex) {
            throw bodyBuilder.invalidInsn("object_cast classifier rejected operand: " + ex.getMessage());
        }
        // IDENTITY / OBJECT_UPCAST / OBJECT_RUNTIME_CAST may reach hand-written object_cast;
        // only INVALID must fail closed.
        if (decision == ExplicitCastDecision.INVALID) {
            throw bodyBuilder.invalidInsn(
                    "object_cast is statically invalid for source '" + sourceType.getTypeName() +
                            "' -> '" + targetType.getTypeName() + "'"
            );
        }
        if (decision != ExplicitCastDecision.IDENTITY
                && decision != ExplicitCastDecision.OBJECT_UPCAST
                && decision != ExplicitCastDecision.OBJECT_RUNTIME_CAST) {
            throw bodyBuilder.invalidInsn(
                    "object_cast does not accept decision " + decision + " for source '" +
                            sourceType.getTypeName() + "' -> '" + targetType.getTypeName() + "'"
            );
        }
    }

    private static @NotNull LirVariable requireVariable(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull String variableId,
            @NotNull String role
    ) {
        var variable = bodyBuilder.func().getVariableById(variableId);
        if (variable == null) {
            throw bodyBuilder.invalidInsn("object_cast " + role + " variable not found: " + variableId);
        }
        return variable;
    }

}
