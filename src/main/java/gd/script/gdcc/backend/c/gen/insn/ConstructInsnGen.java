package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.ConstructArrayInsn;
import gd.script.gdcc.lir.insn.ConstructBuiltinInsn;
import gd.script.gdcc.lir.insn.ConstructDictionaryInsn;
import gd.script.gdcc.lir.insn.ConstructObjectInsn;
import gd.script.gdcc.lir.insn.ConstructCallableInsn;
import gd.script.gdcc.lir.insn.ConstructSignalInsn;
import gd.script.gdcc.lir.insn.ConstructStandaloneCallableInsn;
import gd.script.gdcc.lir.insn.ConstructionInstruction;
import gd.script.gdcc.lir.insn.StandaloneCallableKind;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.RefCountedStatus;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedArrayType;
import gd.script.gdcc.type.GdCallableType;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/// C code generator for construct instructions:
/// - construct_builtin
/// - construct_array
/// - construct_dictionary
/// - construct_object
/// - construct_signal
/// - construct_callable
/// - construct_standalone_callable
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
                GdInstruction.CONSTRUCT_OBJECT,
                GdInstruction.CONSTRUCT_SIGNAL,
                GdInstruction.CONSTRUCT_CALLABLE,
                GdInstruction.CONSTRUCT_STANDALONE_CALLABLE
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
                    if (objectTarget.classDef() instanceof ExtensionGdClass) {
                        bodyBuilder.recordUsedEngineConstructor(objectTarget.constructedType());
                    }
                    var constructCall = renderObjectConstructCall(objectTarget);
                    bodyBuilder.assignVar(
                            target,
                            // `construct_object` materializes a fresh object. Mark it as OWNED here so
                            // the destination slot consumes the constructor result instead of retaining it again.
                            bodyBuilder.valueOfOwnedExpr(
                                    constructCall,
                                    objectTarget.constructedType(),
                                    CBodyBuilder.PtrKind.RAW_PRODUCER
                            )
                    );
                }
                case ConstructSignalInsn(_, var receiverVarId, var signalName) -> {
                    if (!(resultVar.type() instanceof GdSignalType)) {
                        throw bodyBuilder.invalidInsn(
                                "Result variable ID '" + resultVar.id() + "' must be Signal type for construct_signal"
                        );
                    }
                    var receiverVar = resolveObjectReceiverVariable(
                            bodyBuilder,
                            receiverVarId,
                            "construct_signal"
                    );
                    var livePtr = bodyBuilder.renderLiveGodotObjectPtr(
                            bodyBuilder.valueOfVar(receiverVar).generateCode(),
                            (GdObjectType) receiverVar.type()
                    );
                    bodyBuilder.assignVar(
                            target,
                            // Signal is a destroyable builtin value and only stores a non-owning ObjectID.
                            bodyBuilder.valueOfExpr(
                                    "godot_new_Signal_with_Object_StringName("
                                            + livePtr
                                            + ", "
                                            + CBodyBuilder.renderStaticStringNameLiteral(signalName)
                                            + ")",
                                    resultVar.type()
                            )
                    );
                }
                case ConstructCallableInsn(_, var receiverVarId, var methodName) -> {
                    if (!(resultVar.type() instanceof GdCallableType)) {
                        throw bodyBuilder.invalidInsn(
                                "Result variable ID '" + resultVar.id() + "' must be Callable type for construct_callable"
                        );
                    }
                    emitConstructCallable(bodyBuilder, target, resultVar, receiverVarId, methodName);
                }
                case ConstructStandaloneCallableInsn(_, var kind, var ownerName, var callableName) -> {
                    if (!(resultVar.type() instanceof GdCallableType)) {
                        throw bodyBuilder.invalidInsn(
                                "Result variable ID '" + resultVar.id()
                                        + "' must be Callable type for construct_standalone_callable"
                        );
                    }
                    emitConstructStandaloneCallable(bodyBuilder, target, resultVar, kind, ownerName, callableName);
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

    private @NotNull LirVariable resolveObjectReceiverVariable(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull String receiverVarId,
            @NotNull String opcodeName
    ) {
        var actualReceiverId = StringUtil.requireTrimmedNonBlank(receiverVarId, opcodeName + " receiver");
        var receiverVar = bodyBuilder.func().getVariableById(actualReceiverId);
        if (receiverVar == null) {
            throw bodyBuilder.invalidInsn(opcodeName + " receiver variable ID '" + actualReceiverId + "' not found");
        }
        if (!(receiverVar.type() instanceof GdObjectType)) {
            throw bodyBuilder.invalidInsn(
                    opcodeName + " receiver variable ID '" + actualReceiverId + "' must be Object type"
            );
        }
        return receiverVar;
    }

    /// Object receivers use the ObjectID constructor. Non-Object builtins pack a
    /// temporary Variant and use `godot_Callable_create`. Variant receivers stay illegal.
    private void emitConstructCallable(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull CBodyBuilder.TargetRef target,
            @NotNull LirVariable resultVar,
            @NotNull String receiverVarId,
            @NotNull String methodName
    ) {
        var actualReceiverId = StringUtil.requireTrimmedNonBlank(receiverVarId, "construct_callable receiver");
        var receiverVar = bodyBuilder.func().getVariableById(actualReceiverId);
        if (receiverVar == null) {
            throw bodyBuilder.invalidInsn("construct_callable receiver variable ID '" + actualReceiverId + "' not found");
        }
        switch (receiverVar.type()) {
            case GdObjectType objectType -> {
                var livePtr = bodyBuilder.renderLiveGodotObjectPtr(
                        bodyBuilder.valueOfVar(receiverVar).generateCode(),
                        objectType
                );
                bodyBuilder.assignVar(
                        target,
                        bodyBuilder.valueOfExpr(
                                "godot_new_Callable_with_Object_StringName("
                                        + livePtr
                                        + ", "
                                        + CBodyBuilder.renderStaticStringNameLiteral(methodName)
                                        + ")",
                                resultVar.type()
                        )
                );
            }
            case GdVariantType _ -> throw bodyBuilder.invalidInsn(
                    "construct_callable receiver variable ID '" + actualReceiverId
                            + "' must not be Variant"
            );
            default -> {
                if (!isPackableBuiltinReceiver(receiverVar.type())) {
                    throw bodyBuilder.invalidInsn(
                            "construct_callable receiver variable ID '" + actualReceiverId
                                    + "' must be Object or non-Object builtin type"
                    );
                }
                var packedReceiver = InsnGenSupport.materializeVariantOperand(
                        bodyBuilder,
                        receiverVar,
                        "callable_receiver"
                );
                var receiverArg = InsnGenSupport.renderArgumentCode(
                        bodyBuilder,
                        packedReceiver.variantValue(),
                        "construct_callable"
                );
                bodyBuilder.assignVar(
                        target,
                        bodyBuilder.valueOfExpr(
                                "godot_Callable_create(NULL, "
                                        + receiverArg
                                        + ", "
                                        + CBodyBuilder.renderStaticStringNameLiteral(methodName)
                                        + ")",
                                resultVar.type()
                        )
                );
                InsnGenSupport.destroyInitializedTemps(bodyBuilder, packedReceiver.tempVar());
            }
        }
    }

    private boolean isPackableBuiltinReceiver(@NotNull GdType type) {
        return !(type instanceof GdObjectType)
                && !(type instanceof GdVariantType)
                && !(type instanceof GdVoidType)
                && !(type instanceof GdNilType)
                && !(type instanceof GdCompilerType)
                && type.getGdExtensionType() != null;
    }

    private void emitConstructStandaloneCallable(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull CBodyBuilder.TargetRef target,
            @NotNull LirVariable resultVar,
            @NotNull StandaloneCallableKind kind,
            @NotNull String ownerName,
            @NotNull String callableName
    ) {
        var spec = switch (kind) {
            case UTILITY -> resolveUtilityStandaloneSpec(bodyBuilder, callableName);
            case STATIC_GDCC -> resolveGdccStaticStandaloneSpec(bodyBuilder, ownerName, callableName);
            case STATIC_ENGINE -> resolveEngineStaticStandaloneSpec(bodyBuilder, ownerName, callableName);
        };
        bodyBuilder.assignVar(
                target,
                bodyBuilder.valueOfExpr(
                        "gdcc_new_standalone_callable("
                                + "u8\"" + escapeCString(kind.token()) + "\", "
                                + "u8\"" + escapeCString(spec.ownerName()) + "\", "
                                + "u8\"" + escapeCString(spec.callableName()) + "\", "
                                + spec.utilityHash() + "LL, "
                                + spec.argumentCount() + ", "
                                + spec.vararg() + ", "
                                + spec.returnsValue()
                                + ")",
                        resultVar.type()
                )
        );
    }

    private @NotNull StandaloneCallableSpec resolveUtilityStandaloneSpec(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull String callableName
    ) {
        var utility = bodyBuilder.classRegistry().findUtilityFunction(callableName);
        if (utility == null) {
            throw bodyBuilder.invalidInsn(
                    "construct_standalone_callable utility '" + callableName + "' is not registered"
            );
        }
        return new StandaloneCallableSpec(
                "",
                utility.name(),
                Integer.toUnsignedLong(utility.hash()),
                utility.getParameterCount(),
                utility.isVararg(),
                !(utility.getReturnType() instanceof GdVoidType)
        );
    }

    private @NotNull StandaloneCallableSpec resolveGdccStaticStandaloneSpec(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull String ownerName,
            @NotNull String callableName
    ) {
        var startClass = bodyBuilder.classRegistry().resolveClassDefByName(ownerName);
        if (startClass == null || !startClass.isGdccClass()) {
            throw bodyBuilder.invalidInsn(
                    "construct_standalone_callable static_gdcc owner '" + ownerName + "' is not a GDCC class"
            );
        }
        var lookup = requireStaticFunctionInHierarchy(bodyBuilder, ownerName, callableName, "static_gdcc");
        if (!lookup.ownerClass().isGdccClass()) {
            throw bodyBuilder.invalidInsn(
                    "construct_standalone_callable static_gdcc '" + ownerName
                            + "." + callableName + "' is not a generated static function"
            );
        }
        var function = requireStaticFunction(bodyBuilder, lookup.ownerClass(), callableName, "static_gdcc");
        return new StandaloneCallableSpec(
                lookup.ownerClass().getName(),
                function.getName(),
                0L,
                function.getParameterCount(),
                function.isVararg(),
                !(function.getReturnType() instanceof GdVoidType)
        );
    }

    private @NotNull StandaloneCallableSpec resolveEngineStaticStandaloneSpec(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull String ownerName,
            @NotNull String callableName
    ) {
        var lookup = requireStaticFunctionInHierarchy(bodyBuilder, ownerName, callableName, "static_engine");
        if (!(lookup.ownerClass() instanceof ExtensionGdClass engineClass)) {
            throw bodyBuilder.invalidInsn(
                    "construct_standalone_callable static_engine owner '" + ownerName + "' is not an engine class"
            );
        }
        var function = requireStaticFunction(bodyBuilder, engineClass, callableName, "static_engine");
        return new StandaloneCallableSpec(
                engineClass.getName(),
                function.getName(),
                0L,
                function.getParameterCount(),
                function.isVararg(),
                !(function.getReturnType() instanceof GdVoidType)
        );
    }

    private @NotNull ClassRegistry.ClassStaticFunctionLookup requireStaticFunctionInHierarchy(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull String ownerName,
            @NotNull String callableName,
            @NotNull String kindToken
    ) {
        var lookup = bodyBuilder.classRegistry().findStaticFunctionInHierarchy(ownerName, callableName);
        if (lookup == null) {
            throw bodyBuilder.invalidInsn(
                    "construct_standalone_callable " + kindToken + " '" + ownerName
                            + "." + callableName + "' is not a generated static function"
            );
        }
        return lookup;
    }

    private @NotNull FunctionDef requireStaticFunction(
            @NotNull CBodyBuilder bodyBuilder,
            @NotNull ClassDef classDef,
            @NotNull String callableName,
            @NotNull String kindToken
    ) {
        FunctionDef found = null;
        for (var function : classDef.getFunctions()) {
            if (!function.getName().equals(callableName) || !function.isStatic()) {
                continue;
            }
            if (found != null) {
                throw bodyBuilder.invalidInsn(
                        "construct_standalone_callable " + kindToken + " '" + classDef.getName()
                                + "." + callableName + "' is overloaded"
                );
            }
            found = function;
        }
        if (found == null) {
            throw bodyBuilder.invalidInsn(
                    "construct_standalone_callable " + kindToken + " '" + classDef.getName()
                            + "." + callableName + "' is not a generated static function"
            );
        }
        return found;
    }

    private @NotNull String escapeCString(@NotNull String value) {
        return StringUtil.escapeStringLiteral(value);
    }

    private record StandaloneCallableSpec(
            @NotNull String ownerName,
            @NotNull String callableName,
            long utilityHash,
            int argumentCount,
            boolean vararg,
            boolean returnsValue
    ) {
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
        if (hasDifferentRenderedTypeName(bodyBuilder, expectedElementType, actualElementType)) {
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
        if (hasDifferentRenderedTypeName(bodyBuilder, expectedKeyType, resultType.getKeyType())) {
            throw bodyBuilder.invalidInsn(
                    "construct_dictionary key type mismatch: operand key type '" +
                            renderTypeName(bodyBuilder, expectedKeyType) +
                            "' does not match result variable key type '" +
                            renderTypeName(bodyBuilder, resultType.getKeyType()) + "'"
            );
        }
        if (hasDifferentRenderedTypeName(bodyBuilder, expectedValueType, resultType.getValueType())) {
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
        var actualClassName = StringUtil.requireTrimmedNonBlank(className, "construct_object class_name");
        if (!(resultVar.type() instanceof GdObjectType resultType)) {
            throw bodyBuilder.invalidInsn(
                    "Result variable ID '" + resultVar.id() + "' must be Object type for construct_object"
            );
        }

        var constructedType = new GdObjectType(actualClassName);
        var classDef = bodyBuilder.classRegistry().getClassDef(constructedType);
        if (classDef == null) {
            throw bodyBuilder.invalidInsn("construct_object class '" + actualClassName + "' is not registered");
        }
        validateConstructibleClass(bodyBuilder, classDef, actualClassName);
        if (!bodyBuilder.classRegistry().checkAssignable(constructedType, resultType)) {
            throw bodyBuilder.invalidInsn(
                    "construct_object class '" + actualClassName + "' is not assignable to result variable type '" +
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
    /// Engine classes use GDCC's runtime `godot_new_<EngineClass>()` wrappers, while GDCC classes reuse generated
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

    private boolean hasDifferentRenderedTypeName(@NotNull CBodyBuilder bodyBuilder,
                                                 @NotNull GdType expectedType,
                                                 @NotNull GdType actualType) {
        return !renderTypeName(bodyBuilder, expectedType).equals(renderTypeName(bodyBuilder, actualType));
    }

    private @NotNull String renderTypeName(@NotNull CBodyBuilder bodyBuilder, @NotNull GdType type) {
        return bodyBuilder.helper().renderGdTypeName(type);
    }
}
