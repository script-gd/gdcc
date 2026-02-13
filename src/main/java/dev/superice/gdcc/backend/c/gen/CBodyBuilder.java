package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.lir.LirBasicBlock;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirInstruction;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdObjectType;
import dev.superice.gdcc.type.GdPrimitiveType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdVoidType;
import dev.superice.gdcc.type.GdBoolType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.ArrayList;

/// Builder for generating C function body code.
///
/// This builder is created per function and used on a single thread.
/// It tracks current instruction position to provide precise codegen errors.
@SuppressWarnings("UnusedReturnValue")
public final class CBodyBuilder {
    private final @NotNull CGenHelper helper;
    private final @NotNull LirClassDef clazz;
    private final @NotNull LirFunctionDef func;
    private final @NotNull StringBuilder out = new StringBuilder();

    private @Nullable LirBasicBlock currentBlock;
    private int currentInsnIndex = -1;
    private @Nullable LirInstruction currentInsn;

    private int tempVarCounter = 0;

    public CBodyBuilder(@NotNull CGenHelper helper,
                        @NotNull LirClassDef clazz,
                        @NotNull LirFunctionDef func) {
        this.helper = Objects.requireNonNull(helper);
        this.clazz = Objects.requireNonNull(clazz);
        this.func = Objects.requireNonNull(func);
    }

    public @NotNull CBodyBuilder setCurrentPosition(@NotNull LirBasicBlock block,
                                                    int insnIndex,
                                                    @NotNull LirInstruction instruction) {
        this.currentBlock = Objects.requireNonNull(block);
        this.currentInsnIndex = insnIndex;
        this.currentInsn = Objects.requireNonNull(instruction);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <Insn extends LirInstruction> @NotNull Insn getCurrentInsn(@NotNull CInsnGen<Insn> gen) {
        Objects.requireNonNull(gen);
        var insn = currentInsn;
        if (insn == null || currentBlock == null) {
            throw new IllegalStateException("Current instruction position is not set");
        }
        if (!gen.getInsnOpcodes().contains(insn.opcode())) {
            throw new InvalidInsnException(
                    func.getName(),
                    currentBlock.id(),
                    currentInsnIndex,
                    insn.opcode().opcode(),
                    "Current instruction opcode '" + insn.opcode().opcode() +
                            "' is not handled by generator '" + gen.getClass().getSimpleName() + "'"
            );
        }
        return (Insn) insn;
    }

    public @NotNull CBodyBuilder beginBasicBlock(@NotNull String blockId) {
        out.append(blockId).append(": // ").append(blockId).append("\n");
        if ("_prepare".equals(blockId)) {
            var returnType = func.getReturnType();
            if (!(returnType instanceof GdVoidType)) {
                out.append(helper.renderGdTypeInC(returnType)).append(" _return_val;\n");
            }
        }
        return this;
    }

    public @NotNull CBodyBuilder appendLine(@NotNull String line) {
        out.append(line).append("\n");
        return this;
    }

    public @NotNull CBodyBuilder appendRaw(@NotNull String code) {
        out.append(code);
        return this;
    }

    private @NotNull String newTempName(@NotNull String prefix) {
        return "__gdcc_tmp_" + prefix + "_" + tempVarCounter++;
    }

    public @NotNull TempVar newTempVariable(@NotNull String prefix,
                                            @NotNull GdType type,
                                            @NotNull String initCode) {
        return new TempVar(newTempName(prefix), type, initCode);
    }

    public @NotNull CBodyBuilder declareTempVar(@NotNull TempVar temp) {
        out.append(helper.renderGdTypeInC(temp.type())).append(" ").append(temp.name()).append(" = ")
           .append(temp.initCode()).append(";\n");
        return this;
    }

    public @NotNull CBodyBuilder destroyTempVar(@NotNull TempVar temp) {
        emitDestroy(temp.name(), temp.type());
        return this;
    }

    public @NotNull InvalidInsnException invalidInsn(@NotNull String reason) {
        var insn = currentInsn;
        var block = currentBlock;
        if (insn == null || block == null) {
            return new InvalidInsnException("Invalid instruction in function '" + func.getName() + "': " + reason);
        }
        return new InvalidInsnException(func.getName(), block.id(), currentInsnIndex, insn.opcode().opcode(), reason);
    }

    public @NotNull CGenHelper helper() {
        return helper;
    }

    public @NotNull ClassRegistry classRegistry() {
        return helper.context().classRegistry();
    }

    public @NotNull LirClassDef clazz() {
        return clazz;
    }

    public @NotNull LirFunctionDef func() {
        return func;
    }

    public @Nullable LirBasicBlock currentBlock() {
        return currentBlock;
    }

    public int currentInsnIndex() {
        return currentInsnIndex;
    }

    public @Nullable LirInstruction currentInsn() {
        return currentInsn;
    }

    public @NotNull String build() {
        return out.toString();
    }

    /// Creates a value reference from a variable.
    public @NotNull ValueRef valueOfVar(@NotNull LirVariable variable) {
        return new VarValue(variable);
    }

    /// Creates a value reference from a raw C expression and type.
    public @NotNull ValueRef valueOfExpr(@NotNull String code, @NotNull GdType type) {
        return new ExprValue(code, type);
    }

    /// Creates a target reference from a variable.
    ///
    /// Throws InvalidInsnException if the variable is a reference variable (ref=true).
    public @NotNull TargetRef targetOfVar(@NotNull LirVariable variable) {
        if (variable.ref()) {
            throw invalidInsn("Cannot assign to reference variable '" + variable.id() + "'");
        }
        return new TargetRef(variable);
    }

    public @NotNull CBodyBuilder assignVar(@NotNull TargetRef target, @NotNull ValueRef value) {
        checkTargetAssignable(target);
        checkAssignable(value.type(), target.type());

        var targetCode = target.generateCode();
        var targetType = target.type();

        // Prepare RHS first to avoid destroying sources for self/alias assignments.
        var rhsResult = prepareRhsValue(value, targetType);
        emitTempDecls(rhsResult.temps());

        // Phase C: Full assignment semantics
        // 1. Destroy old value if needed (non-object destroyable types)
        // 2. Release old object ownership if object type
        if (!checkInPrepareBlock() && targetType.isDestroyable()) {
            if (targetType instanceof GdObjectType objType) {
                // Object type: release old ownership
                emitReleaseObject(targetCode, objType);
            } else {
                // Non-object destroyable: call destroy
                emitDestroy(targetCode, targetType);
            }
        }

        // 4. Write new value
        out.append(targetCode).append(" = ").append(rhsResult.code()).append(";\n");

        // 5. Own new object if object type
        if (targetType instanceof GdObjectType objType) {
            emitOwnObject(targetCode, objType);
        }

        emitTempDestroys(rhsResult.temps());
        return this;
    }

    /// Assigns a raw expression into a target variable.
    public @NotNull CBodyBuilder assignExpr(@NotNull TargetRef target, @NotNull String expr, @NotNull GdType type) {
        return assignVar(target, valueOfExpr(expr, type));
    }

    /// Assigns a global enum constant to a target variable.
    public @NotNull CBodyBuilder assignGlobalConst(@NotNull TargetRef target,
                                                   @NotNull String enumName,
                                                   @NotNull String valueName) {
        var globalEnum = classRegistry().findGlobalEnum(enumName);
        if (globalEnum == null) {
            throw invalidInsn("Global enum '" + enumName + "' not found");
        }
        var matchedValue = globalEnum.values().stream()
                .filter(value -> value.name().equals(valueName))
                .findFirst()
                .orElse(null);
        if (matchedValue == null) {
            throw invalidInsn("Global enum value '" + valueName + "' not found in enum '" + enumName + "'");
        }
        return assignVar(target, valueOfExpr(Integer.toString(matchedValue.value()), GdIntType.INT));
    }

    public @NotNull CBodyBuilder callVoid(@NotNull String funcName, @NotNull List<ValueRef> args) {
        var argsResult = renderArgs(args);
        emitTempDecls(argsResult.temps());
        out.append(funcName).append("(").append(argsResult.code()).append(");\n");
        emitTempDestroys(argsResult.temps());
        return this;
    }

    public @NotNull CBodyBuilder callAssign(@NotNull TargetRef target, @NotNull String funcName, @NotNull List<ValueRef> args) {
        return callAssign(target, funcName, null, args);
    }

    public @NotNull CBodyBuilder callAssign(@NotNull TargetRef target,
                                            @NotNull String funcName,
                                            @Nullable GdType returnType,
                                            @NotNull List<ValueRef> args) {
        checkTargetAssignable(target);

        var resolvedReturnType = resolveReturnType(funcName, returnType);
        validateCallArgs(funcName, args);
        validateReturnType(funcName, resolvedReturnType, target.type());

        var targetCode = target.generateCode();
        var targetType = target.type();

        var argsResult = renderArgs(args);
        emitTempDecls(argsResult.temps());

        // Phase C: Handle old value destruction before assignment
        if (!checkInPrepareBlock() && targetType.isDestroyable()) {
            if (targetType instanceof GdObjectType objType) {
                emitReleaseObject(targetCode, objType);
            } else {
                emitDestroy(targetCode, targetType);
            }
        }

        // Generate call and assignment
        out.append(targetCode).append(" = ").append(funcName).append("(").append(argsResult.code()).append(");\n");

        // Own new object if returned type is object
        if (targetType instanceof GdObjectType objType) {
            emitOwnObject(targetCode, objType);
        }

        emitTempDestroys(argsResult.temps());
        return this;
    }

    public @NotNull CBodyBuilder jump(@NotNull String blockId) {
        out.append("goto ").append(blockId).append(";\n");
        return this;
    }

    public @NotNull CBodyBuilder jumpIf(@NotNull ValueRef condition, @NotNull String trueBlockId, @NotNull String falseBlockId) {
        var conditionResult = renderCondition(condition);
        emitTempDecls(conditionResult.temps());
        out.append("if (").append(conditionResult.code()).append(") goto ").append(trueBlockId).append(";\n");
        out.append("else goto ").append(falseBlockId).append(";\n");
        emitTempDestroys(conditionResult.temps());
        return this;
    }

    public @NotNull CBodyBuilder returnVoid() {
        if (!checkInFinallyBlock()) {
            out.append("goto _finally;\n");
            return this;
        }
        out.append("return;\n");
        return this;
    }

    public @NotNull CBodyBuilder returnValue(@NotNull ValueRef value) {
        var returnType = func.getReturnType();
        if (!checkInFinallyBlock()) {
            if (returnType instanceof GdVoidType) {
                var returnResult = prepareReturnValue(value);
                if (!returnResult.temps.isEmpty()) {
                    throw new IllegalStateException("Cannot return a value with prepareReturnValue");
                }
                out.append("_return_val = ").append(returnResult.code()).append(";\n");
            }
            out.append("goto _finally;\n");
            return this;
        }

        // Phase C: Copy semantics for return
        var returnResult = prepareReturnValue(value);
        emitTempDecls(returnResult.temps());

        if (returnResult.temps().isEmpty()) {
            out.append("return ").append(returnResult.code()).append(";\n");
            return this;
        }

        var retTemp = newTempVariable("ret", value.type(), returnResult.code());
        out.append(helper.renderGdTypeInC(retTemp.type())).append(" ").append(retTemp.name()).append(" = ")
           .append(retTemp.initCode()).append(";\n");
        emitTempDestroys(returnResult.temps());
        out.append("return ").append(retTemp.name()).append(";\n");
        return this;
    }

    private @NotNull RenderResult renderArgs(@NotNull List<ValueRef> args) {
        var rendered = new StringBuilder();
        var temps = new ArrayList<TempVar>();
        for (var i = 0; i < args.size(); i++) {
            if (i > 0) {
                rendered.append(", ");
            }
            var argResult = renderArgument(args.get(i));
            rendered.append(argResult.code());
            temps.addAll(argResult.temps());
        }
        return new RenderResult(rendered.toString(), temps);
    }

    /// Checks if a value of sourceType can be assigned to a variable of targetType.
    /// Throws InvalidInsnException if not assignable.
    private void checkAssignable(@NotNull GdType sourceType, @NotNull GdType targetType) {
        if (!classRegistry().checkAssignable(sourceType, targetType)) {
            throw invalidInsn("Cannot assign value of type '" + sourceType.getTypeName() +
                    "' to variable of type '" + targetType.getTypeName() + "'");
        }
    }

    private void checkTargetAssignable(@NotNull TargetRef target) {
        if (target.variable().ref()) {
            throw invalidInsn("Cannot assign to reference variable '" + target.variable().id() + "'");
        }
    }

    private @Nullable GdType resolveReturnType(@NotNull String funcName, @Nullable GdType explicitType) {
        if (explicitType != null) {
            return explicitType;
        }
        var signature = classRegistry().findUtilityFunctionSignature(funcName);
        if (signature == null) {
            return null;
        }
        return signature.returnType();
    }

    private void validateCallArgs(@NotNull String funcName, @NotNull List<ValueRef> args) {
        var signature = classRegistry().findUtilityFunctionSignature(funcName);
        if (signature == null) {
            return;
        }
        var paramCount = signature.parameterCount();
        var isVararg = signature.isVararg();
        if (!isVararg && args.size() > paramCount) {
            throw invalidInsn("Too many arguments for function '" + funcName + "': expected " + paramCount + ", got " + args.size());
        }
        if (args.size() < paramCount) {
            for (var i = args.size(); i < paramCount; i++) {
                var param = signature.parameters().get(i);
                if (param.defaultValue() == null) {
                    throw invalidInsn("Too few arguments for function '" + funcName + "': expected " + paramCount + ", got " + args.size());
                }
            }
        }
        var checkCount = Math.min(args.size(), paramCount);
        for (var i = 0; i < checkCount; i++) {
            var param = signature.parameters().get(i);
            var paramType = param.type();
            if (paramType == null) {
                continue;
            }
            checkAssignable(args.get(i).type(), paramType);
        }
    }

    private void validateReturnType(@NotNull String funcName, @Nullable GdType resolvedReturnType, @NotNull GdType targetType) {
        if (resolvedReturnType == null) {
            throw invalidInsn("Return type is required for non-utility function: " + funcName);
        }
        if (resolvedReturnType instanceof GdVoidType) {
            throw invalidInsn("CallAssign expects a non-void function: " + funcName);
        }
        checkAssignable(resolvedReturnType, targetType);
    }

    /// Renders a ValueRef for a conditional expression without emitting code.
    private @NotNull RenderResult renderCondition(@NotNull ValueRef condition) {
        if (!(condition.type() instanceof GdBoolType)) {
            throw invalidInsn("jumpIf condition must be bool, got '" + condition.type().getTypeName() + "'");
        }
        return new RenderResult(condition.generateCode(), List.of());
    }

    /// Renders a ValueRef as a C argument, adding '&' if needed for pass-by-reference types.
    /// - Primitive types and object pointers: pass by value (no &)
    /// - Value-semantic types (String, StringName, Variant, etc.): pass by pointer (&)
    private @NotNull RenderResult renderArgument(@NotNull ValueRef value) {
        var type = value.type();

        // Special handling for variable references that are already refs
        if (value instanceof VarValue(var variable) && variable.ref()) {
            // ref variables are already pointers, use as-is
            return new RenderResult(value.generateCode(), List.of());
        }

        // Determine if we need to add &
        if (needsAddressOf(type)) {
            return renderValueAddress(value);
        }
        return new RenderResult(value.generateCode(), List.of());
    }

    /// Determines if a type needs '&' when passed as argument.
    /// - Primitives (bool, int, float): NO
    /// - Object pointers: NO
    /// - Value-semantic types (String, Variant, Array, etc.): YES
    private boolean needsAddressOf(@NotNull GdType type) {
        // Primitives are passed by value
        // Object pointers are already pointers
        // All other types (String, StringName, Variant, Array, Dictionary, etc.)
        // are value-semantic structs that need to be passed by pointer
        return !(type instanceof GdPrimitiveType) && !(type instanceof GdObjectType);
    }

    /// Prepares the RHS value for assignment, copying if needed for value-semantic types.
    /// Returns the C code expression for the prepared value.
    @SuppressWarnings("unused") // targetType reserved for future type conversion logic
    private @NotNull RenderResult prepareRhsValue(@NotNull ValueRef value, @NotNull GdType targetType) {
        var type = value.type();
        var code = value.generateCode();

        // Primitives and object pointers: direct assignment
        if (type instanceof GdPrimitiveType || type instanceof GdObjectType) {
            return new RenderResult(code, List.of());
        }

        // Value-semantic types: need to copy
        // For String, StringName, Variant, Array, Dictionary, etc.
        var copyFunc = helper.renderCopyAssignFunctionName(type);
        if (!copyFunc.isEmpty()) {
            // Need to copy: godot_new_<Type>_with_<Type>(source_ptr)
            var sourcePtr = renderValueAddress(value);
            var temp = newTempVariable(type.getTypeName().toLowerCase(), type, copyFunc + "(" + sourcePtr.code() + ")");
            var temps = new ArrayList<>(sourcePtr.temps());
            temps.add(temp);
            return new RenderResult(temp.name(), temps);
        }

        return new RenderResult(code, List.of());
    }

    /// Prepares a value for return, copying if needed.
    private @NotNull RenderResult prepareReturnValue(@NotNull ValueRef value) {
        var type = value.type();
        var code = value.generateCode();

        // Primitives and object pointers: direct return
        if (type instanceof GdPrimitiveType || type instanceof GdObjectType) {
            return new RenderResult(code, List.of());
        }

        // Value-semantic types: need to copy for return
        var copyFunc = helper.renderCopyAssignFunctionName(type);
        if (!copyFunc.isEmpty()) {
            // Generate copy: godot_new_<Type>_with_<Type>(source_ptr)
            var sourcePtr = renderValueAddress(value);
            return new RenderResult(copyFunc + "(" + sourcePtr.code() + ")", sourcePtr.temps());
        }

        return new RenderResult(code, List.of());
    }

    private void emitTempDecls(@NotNull List<TempVar> temps) {
        for (var temp : temps) {
            declareTempVar(temp);
        }
    }

    private void emitTempDestroys(@NotNull List<TempVar> temps) {
        for (var i = temps.size() - 1; i >= 0; i--) {
            var temp = temps.get(i);
            destroyTempVar(temp);
        }
    }

    /// Renders a pointer to a value, materializing expressions when needed.
    /// - ref variables already point to the value
    /// - non-ref variables use &var
    /// - expressions are materialized to a temp, then &temp
    private @NotNull RenderResult renderValueAddress(@NotNull ValueRef value) {
        if (value instanceof VarValue(var variable)) {
            var code = value.generateCode();
            if (variable.ref()) {
                return new RenderResult(code, List.of());
            }
            return new RenderResult("&" + code, List.of());
        }
        if (value instanceof ExprValue exprValue) {
            var temp = newTempVariable(exprValue.type().getTypeName().toLowerCase(), exprValue.type(), exprValue.generateCode());
            return new RenderResult("&" + temp.name(), List.of(temp));
        }
        return new RenderResult("&" + value.generateCode(), List.of());
    }

    private void emitDestroy(@NotNull String varCode, @NotNull GdType type) {
        if (!type.isDestroyable() || type instanceof GdObjectType) {
            return;
        }
        var destroyFunc = helper.renderDestroyFunctionName(type);
        out.append(destroyFunc).append("(&").append(varCode).append(");\n");
    }

    /// Emits code to release ownership of an object.
    /// Uses try_release_object for unknown RefCounted status, release_object for definite RefCounted.
    private void emitReleaseObject(@NotNull String varCode, @NotNull GdObjectType objType) {
        var godotPtrCode = toGodotObjectPtr(varCode, objType);
        releaseOrTryRelease(godotPtrCode, objType);
    }

    /// Emits code to own an object.
    /// Uses try_own_object for unknown RefCounted status, own_object for definite RefCounted.
    private void emitOwnObject(@NotNull String varCode, @NotNull GdObjectType objType) {
        var godotPtrCode = toGodotObjectPtr(varCode, objType);
        ownOrTryOwn(godotPtrCode, objType);
    }

    /// Converts a GDCC object pointer to Godot object pointer.
    /// For GDCC types: use ->_object
    /// For engine types: use as-is
    private @NotNull String toGodotObjectPtr(@NotNull String varCode, @NotNull GdObjectType objType) {
        if (objType.checkGdccType(classRegistry())) {
            return varCode + "->_object";
        }
        return varCode;
    }

    /// Converts a Godot object pointer to a GDCC object pointer when needed.
    /// For GDCC types: wraps with gdcc_object_from_godot_object_ptr
    /// For engine types: use as-is
    private @NotNull String fromGodotObjectPtr(@NotNull String godotPtrCode, @NotNull GdObjectType objType) {
        if (objType.checkGdccType(classRegistry())) {
            var castType = helper.renderGdTypeInC(objType);
            return "(" + castType + ")gdcc_object_from_godot_object_ptr(" + godotPtrCode + ")";
        }
        return godotPtrCode;
    }

    /// Emits own_object or try_own_object based on RefCounted status.
    private void ownOrTryOwn(@NotNull String godotPtrCode, @NotNull GdObjectType objType) {
        var status = classRegistry().getRefCountedStatus(objType);
        switch (status) {
            case YES -> out.append("own_object(").append(godotPtrCode).append(");\n");
            case NO -> { }
            case UNKNOWN -> out.append("try_own_object(").append(godotPtrCode).append(");\n");
        }
    }

    /// Emits release_object or try_release_object based on RefCounted status.
    private void releaseOrTryRelease(@NotNull String godotPtrCode, @NotNull GdObjectType objType) {
        var status = classRegistry().getRefCountedStatus(objType);
        switch (status) {
            case YES -> out.append("release_object(").append(godotPtrCode).append(");\n");
            case NO -> { }
            case UNKNOWN -> out.append("try_release_object(").append(godotPtrCode).append(");\n");
        }
    }

    private boolean checkInPrepareBlock() {
        return currentBlock != null && "_prepare".equals(currentBlock.id());
    }

    private boolean checkInFinallyBlock() {
        return currentBlock != null && "_finally".equals(currentBlock.id());
    }

    public sealed interface ValueRef permits VarValue, ExprValue {
        @NotNull GdType type();

        @NotNull String generateCode();
    }

    public record VarValue(@NotNull LirVariable variable) implements ValueRef {
        public VarValue {
            Objects.requireNonNull(variable);
        }

        @Override
        public @NotNull GdType type() {
            return variable.type();
        }

        @Override
        public @NotNull String generateCode() {
            return "$" + variable.id();
        }
    }

    public record ExprValue(@NotNull String code, @NotNull GdType type) implements ValueRef {
        public ExprValue {
            Objects.requireNonNull(code);
            Objects.requireNonNull(type);
        }

        @Override
        public @NotNull GdType type() {
            return type;
        }

        @Override
        public @NotNull String generateCode() {
            return code;
        }
    }

    public record TargetRef(@NotNull LirVariable variable) {
        public TargetRef {
            Objects.requireNonNull(variable);
        }

        public @NotNull GdType type() {
            return variable.type();
        }

        public @NotNull String generateCode() {
            return "$" + variable.id();
        }
    }

    public record TempVar(@NotNull String name, @NotNull GdType type, @NotNull String initCode) {
        public TempVar {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
            Objects.requireNonNull(initCode);
        }
    }

    private record RenderResult(@NotNull String code, @NotNull List<TempVar> temps) {
        private RenderResult {
            Objects.requireNonNull(code);
            Objects.requireNonNull(temps);
        }
    }
}
