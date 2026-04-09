package dev.superice.gdcc.backend.c.gen.insn;

import dev.superice.gdcc.backend.c.gen.CBodyBuilder;
import dev.superice.gdcc.backend.c.gen.CInsnGen;
import dev.superice.gdcc.enums.GdInstruction;
import dev.superice.gdcc.gdextension.ExtensionGdClass;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.ConstructArrayInsn;
import dev.superice.gdcc.lir.insn.ConstructBuiltinInsn;
import dev.superice.gdcc.lir.insn.ConstructDictionaryInsn;
import dev.superice.gdcc.lir.insn.ConstructObjectInsn;
import dev.superice.gdcc.lir.insn.ConstructionInstruction;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.RefCountedStatus;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdObjectType;
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
/// - construct_object
public final class ConstructInsnGen implements CInsnGen<ConstructionInstruction> {
    private record ObjectConstructTarget(
            @NotNull GdObjectType constructedType,
            @NotNull ClassDef classDef,
            boolean needsExternalRefCountedInit
    ) {
    }

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(
                GdInstruction.CONSTRUCT_BUILTIN,
                GdInstruction.CONSTRUCT_ARRAY,
                GdInstruction.CONSTRUCT_DICTIONARY,
                GdInstruction.CONSTRUCT_OBJECT
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
                case ConstructObjectInsn(_, var className) -> {
                    var objectTarget = validateConstructObjectTarget(bodyBuilder, resultVar, className);
                    var constructCall = renderObjectConstructCall(objectTarget);
                    bodyBuilder.assignVar(
                            target,
                            // `construct_object` materializes a fresh object. Mark it as OWNED here so
                            // the destination slot consumes the constructor result instead of retaining it again.
                            bodyBuilder.valueOfOwnedExpr(
                                    constructCall,
                                    objectTarget.constructedType(),
                                    CBodyBuilder.PtrKind.GODOT_PTR
                            )
                    );
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

    private @NotNull ObjectConstructTarget validateConstructObjectTarget(@NotNull CBodyBuilder bodyBuilder,
                                                                         @NotNull LirVariable resultVar,
                                                                         @NotNull String className) {
        if (className.isBlank()) {
            throw bodyBuilder.invalidInsn("construct_object class_name must not be blank");
        }
        if (!(resultVar.type() instanceof GdObjectType resultType)) {
            throw bodyBuilder.invalidInsn(
                    "Result variable ID '" + resultVar.id() + "' must be Object type for construct_object"
            );
        }

        var constructedType = new GdObjectType(className);
        var classDef = bodyBuilder.classRegistry().getClassDef(constructedType);
        if (classDef == null) {
            throw bodyBuilder.invalidInsn("construct_object class '" + className + "' is not registered");
        }
        validateConstructibleClass(bodyBuilder, classDef, className);
        if (!bodyBuilder.classRegistry().checkAssignable(constructedType, resultType)) {
            throw bodyBuilder.invalidInsn(
                    "construct_object class '" + className + "' is not assignable to result variable type '" +
                            resultType.getTypeName() + "'"
            );
        }
        return new ObjectConstructTarget(
                constructedType,
                classDef,
                // `*_class_create_instance(...)` stays a raw shared create/bind helper. When generated C
                // explicitly constructs a GDCC RefCounted object, the caller must delay postinitialize
                // until after `gdcc_ref_counted_init_raw(...)` has established the initial reference count.
                !(classDef instanceof ExtensionGdClass)
                        && bodyBuilder.classRegistry().getRefCountedStatus(constructedType) == RefCountedStatus.YES
        );
    }

    /// Render the direct constructor expression for `construct_object`.
    /// Engine classes use gdextension-lite `godot_new_XXX()`, while GDCC classes reuse generated
    /// `*_class_create_instance(...)`. Explicit GDCC `RefCounted` construction suppresses the shared
    /// postinitialize notification first, then replays it from `gdcc_ref_counted_init_raw(..., true)`
    /// after the raw reference count has been initialized.
    private @NotNull String renderObjectConstructCall(@NotNull ObjectConstructTarget target) {
        return switch (target.classDef()) {
            case ExtensionGdClass _ -> "godot_new_" + target.constructedType().getTypeName() + "()";
            default -> {
                var createCall = target.constructedType().getTypeName()
                        + "_class_create_instance(NULL, " + (!target.needsExternalRefCountedInit()) + ")";
                yield target.needsExternalRefCountedInit()
                        ? "gdcc_ref_counted_init_raw(" + createCall + ", true)"
                        : createCall;
            }
        };
    }

    private void validateConstructibleClass(@NotNull CBodyBuilder bodyBuilder,
                                            @NotNull ClassDef classDef,
                                            @NotNull String className) {
        if (classDef.isAbstract()) {
            throw bodyBuilder.invalidInsn("construct_object class '" + className + "' is abstract");
        }
        if (classDef instanceof ExtensionGdClass engineClass && !engineClass.isInstantiable()) {
            throw bodyBuilder.invalidInsn("construct_object class '" + className + "' is not instantiable");
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
        // Low IR container hints still accept registry compatibility parsing so external/manual IR can keep
        // expressing forward object names such as `Array[FutureItem]` while reusing the shared strict core
        // that now lives behind `ClassRegistry.findType(...)`.
        var parsedType = bodyBuilder.classRegistry().findType(textType);
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
