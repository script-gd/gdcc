package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.lir.*;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static dev.superice.gdcc.util.StringUtil.escapeStringLiteral;

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
        if ("__prepare__".equals(blockId)) {
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
                                            @NotNull GdType type) {
        return new TempVar(newTempName(prefix), type, null, resolvePtrKind(type), false);
    }

    public @NotNull TempVar newTempVariable(@NotNull String prefix,
                                            @NotNull GdType type,
                                            @NotNull String initCode) {
        return new TempVar(newTempName(prefix), type, initCode, resolvePtrKind(type), true);
    }

    public @NotNull CBodyBuilder declareTempVar(@NotNull TempVar temp) {
        out.append(helper.renderGdTypeInC(temp.type())).append(" ").append(temp.name());
        if (temp.hasInitializer()) {
            out.append(" = ").append(Objects.requireNonNull(temp.initCode()));
            temp.setInitialized(true);
        } else {
            temp.setInitialized(false);
        }
        out.append(";\n");
        return this;
    }

    public @NotNull CBodyBuilder destroyTempVar(@NotNull TempVar temp) {
        if (!temp.initialized()) {
            return this;
        }
        emitDestroy(temp.name(), temp.type());
        temp.setInitialized(false);
        return this;
    }

    /// Initializes an uninitialized temp variable without old-value destroy/own-release semantics.
    /// This is used for first-write initialization where assign/callAssign lifecycle hooks are not desired.
    public @NotNull CBodyBuilder initTempVar(@NotNull TempVar temp, @NotNull ValueRef value) {
        checkAssignable(value.type(), temp.type());
        var initCode = value.generateCode();
        if (temp.type() instanceof GdObjectType targetObjType) {
            initCode = convertPtrIfNeeded(initCode, value.ptrKind(), targetObjType);
        }
        out.append(temp.name()).append(" = ").append(initCode).append(";\n");
        temp.setInitialized(true);
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

    /// Resolves the PtrKind for a given GdType based on the class registry.
    private @NotNull PtrKind resolvePtrKind(@NotNull GdType type) {
        if (type instanceof GdObjectType objType) {
            if (objType.checkGdccType(classRegistry())) {
                return PtrKind.GDCC_PTR;
            }
            return PtrKind.GODOT_PTR;
        }
        return PtrKind.NON_OBJECT;
    }

    /// Creates a value reference from a variable.
    public @NotNull ValueRef valueOfVar(@NotNull LirVariable variable) {
        return new VarValue(variable, resolvePtrKind(variable.type()));
    }

    public @NotNull ValueRef valueOfVar(@NotNull String variableName) {
        var variable = func.getVariableById(variableName);
        if (variable == null) {
            throw new InvalidInsnException(func.getName(), currentBlock() != null ? currentBlock().id() : "unknown",
                    currentInsnIndex(), currentInsn != null ? currentInsn.toString() : "unknown",
                    "Variable '" + variableName + "' not found in function");
        }
        return valueOfVar(variable);
    }

    /// Creates a value reference from a raw C expression and type.
    /// PtrKind is auto-resolved from the type.
    public @NotNull ValueRef valueOfExpr(@NotNull String code, @NotNull GdType type) {
        return new ExprValue(code, type, resolvePtrKind(type));
    }

    /// Creates a value reference from a raw C expression, type, and explicit pointer kind.
    public @NotNull ValueRef valueOfExpr(@NotNull String code, @NotNull GdType type, @NotNull PtrKind ptrKind) {
        return new ExprValue(code, type, ptrKind);
    }

    /// Creates a value reference for a static StringName pointer literal.
    public @NotNull ValueRef valueOfStringNamePtrLiteral(@NotNull String value) {
        return new StringNamePtrLiteralValue(value);
    }

    /// Creates a value reference for a static String pointer literal.
    public @NotNull ValueRef valueOfStringPtrLiteral(@NotNull String value) {
        return new StringPtrLiteralValue(value);
    }

    /// Creates a target reference from a variable.
    ///
    /// Throws InvalidInsnException if the variable is a reference variable (ref=true).
    public @NotNull TargetRef targetOfVar(@NotNull LirVariable variable) {
        if (variable.ref()) {
            throw invalidInsn("Cannot assign to reference variable '" + variable.id() + "'");
        }
        return new VarTargetRef(variable);
    }

    public @NotNull CBodyBuilder assignVar(@NotNull TargetRef target, @NotNull ValueRef value) {
        checkTargetAssignable(target);
        checkAssignable(value.type(), target.type());

        var targetCode = target.generateCode();
        var targetType = target.type();
        var canDestroyOldValue = canDestroyOldValue(target);

        // Prepare RHS first to avoid destroying sources for self/alias assignments.
        var rhsResult = prepareRhsValue(value, targetType);
        emitTempDecls(rhsResult.temps());

        // Phase C: Full assignment semantics
        // 1. Destroy old value if needed (non-object destroyable types)
        // 2. Release old object ownership if object type
        if (canDestroyOldValue && !checkInPrepareBlock() && targetType.isDestroyable()) {
            if (targetType instanceof GdObjectType objType) {
                // Object type: release old ownership
                emitReleaseObject(targetCode, objType);
            } else {
                // Non-object destroyable: call destroy
                emitDestroy(targetCode, targetType);
            }
        }

        // 4. Write new value, converting pointer representation if needed
        var assignCode = rhsResult.code();
        if (targetType instanceof GdObjectType targetObjType) {
            assignCode = convertPtrIfNeeded(assignCode, value.ptrKind(), targetObjType);
        }
        out.append(targetCode).append(" = ").append(assignCode).append(";\n");

        // 5. Own new object if object type
        if (targetType instanceof GdObjectType objType) {
            emitOwnObject(targetCode, objType);
        }

        markTargetInitialized(target);
        emitTempDestroys(rhsResult.temps());
        return this;
    }

    /// Assigns a raw expression into a target variable.
    public @NotNull CBodyBuilder assignExpr(@NotNull TargetRef target, @NotNull String expr, @NotNull GdType type) {
        return assignVar(target, valueOfExpr(expr, type));
    }

    /// Assigns a raw expression into a target variable with an explicit pointer kind.
    public @NotNull CBodyBuilder assignExpr(@NotNull TargetRef target, @NotNull String expr, @NotNull GdType type, @NotNull PtrKind ptrKind) {
        return assignVar(target, valueOfExpr(expr, type, ptrKind));
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
        validateCallArgs(funcName, args);
        var argsResult = renderArgs(funcName, args);
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
        var canDestroyOldValue = canDestroyOldValue(target);

        var argsResult = renderArgs(funcName, args);
        emitTempDecls(argsResult.temps());

        // Phase C: Handle old value destruction before assignment
        if (canDestroyOldValue && !checkInPrepareBlock() && targetType.isDestroyable()) {
            if (targetType instanceof GdObjectType objType) {
                emitReleaseObject(targetCode, objType);
            } else {
                emitDestroy(targetCode, targetType);
            }
        }

        // Generate call expression
        var callExpr = funcName + "(" + argsResult.code() + ")";

        // Convert Godot raw ptr return to GDCC ptr if target is a GDCC object
        if (checkGlobalFuncReturnGodotRawPtr(funcName)
                && targetType instanceof GdObjectType objType
                && objType.checkGdccType(classRegistry())) {
            callExpr = fromGodotObjectPtr(callExpr, objType);
        }

        out.append(targetCode).append(" = ").append(callExpr).append(";\n");

        // Own new object if returned type is object
        if (targetType instanceof GdObjectType objType) {
            emitOwnObject(targetCode, objType);
        }

        markTargetInitialized(target);
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
        var returnType = func.getReturnType();
        if (!checkInFinallyBlock()) {
            if (!(returnType instanceof GdVoidType)) {
                throw invalidInsn("Cannot return void from non-void function");
            }
            out.append("goto __finally__;\n");
            return this;
        }
        out.append("return;\n");
        return this;
    }

    public @NotNull CBodyBuilder returnTerminal() {
        var returnType = func.getReturnType();
        if (!checkInFinallyBlock()) {
            throw invalidInsn("Cannot return _return_val from non finally block");
        }
        if (returnType instanceof GdVoidType) {
            throw invalidInsn("Cannot return _return_val from void function");
        }
        out.append("return _return_val;\n");
        return this;
    }

    public @NotNull CBodyBuilder returnValue(@NotNull ValueRef value) {
        var returnType = func.getReturnType();
        if (returnType instanceof GdVoidType) {
            throw invalidInsn("Cannot return a value from void function");
        }
        checkAssignable(value.type(), returnType);

        var returnResult = prepareReturnValue(value);
        emitTempDecls(returnResult.temps());
        var returnCode = returnResult.code();
        if (returnType instanceof GdObjectType objType) {
            returnCode = convertPtrIfNeeded(returnCode, value.ptrKind(), objType);
        }

        if (!checkInFinallyBlock()) {
            out.append("_return_val = ").append(returnCode).append(";\n");
            emitTempDestroys(returnResult.temps());
            out.append("goto __finally__;\n");
            return this;
        }

        if (returnResult.temps().isEmpty()) {
            out.append("return ").append(returnCode).append(";\n");
            return this;
        }

        var retTemp = newTempVariable("ret", returnType);
        declareTempVar(retTemp);
        initTempVar(retTemp, valueOfExpr(returnCode, returnType, resolvePtrKind(returnType)));
        emitTempDestroys(returnResult.temps());
        out.append("return ").append(retTemp.name()).append(";\n");
        return this;
    }

    private @NotNull RenderResult renderArgs(@NotNull String funcName, @NotNull List<ValueRef> args) {
        var requireGodotRawPtr = checkGlobalFuncRequireGodotRawPtr(funcName);
        var rendered = new StringBuilder();
        var temps = new ArrayList<TempVar>();
        for (var i = 0; i < args.size(); i++) {
            if (i > 0) {
                rendered.append(", ");
            }
            var argResult = renderArgument(args.get(i), requireGodotRawPtr);
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
        if (target.isRef()) {
            throw invalidInsn("Cannot assign to reference variable");
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
            funcName = "godot_" + funcName;
            signature = classRegistry().findUtilityFunctionSignature(funcName);
        }
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
    /// When requireGodotRawPtr is true, GDCC object pointers are auto-converted to Godot
    /// object pointers via `->_object`.
    private @NotNull RenderResult renderArgument(@NotNull ValueRef value, boolean requireGodotRawPtr) {
        var type = value.type();

        // Convert GDCC object ptr to Godot raw ptr when calling GDExtension functions
        if (requireGodotRawPtr && value.ptrKind() == PtrKind.GDCC_PTR && type instanceof GdObjectType objType) {
            return new RenderResult(toGodotObjectPtr(value.generateCode(), objType), List.of());
        }

        // Special handling for variable references that are already refs
        if (value instanceof VarValue varValue && varValue.variable().ref()) {
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

    /// Determines if we can skip old value destruction for a target based on whether it's initialized.
    /// Mainly used to optimize first-write initialization of temp variables where there is no old value to destroy.
    private boolean canDestroyOldValue(@NotNull TargetRef target) {
        if (target instanceof TempVar tempVar) {
            return tempVar.initialized();
        }
        return true;
    }

    private void markTargetInitialized(@NotNull TargetRef target) {
        if (target instanceof TempVar tempVar) {
            tempVar.setInitialized(true);
        }
    }

    /// Renders a pointer to a value, materializing expressions when needed.
    /// - ref variables already point to the value
    /// - non-ref variables use &var
    /// - expressions are materialized to a temp, then &temp
    /// - string literals use GD_STATIC_S or GD_STATIC_SN macros, which are already pointers
    private @NotNull RenderResult renderValueAddress(@NotNull ValueRef value) {
        if (value instanceof StringNamePtrLiteralValue || value instanceof StringPtrLiteralValue) {
            return new RenderResult(value.generateCode(), List.of());
        }
        if (value instanceof VarValue varValue) {
            var code = value.generateCode();
            if (varValue.variable().ref()) {
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

    /// Converts an object pointer expression between GDCC and Godot representations if needed.
    ///
    /// - GODOT_PTR value → GDCC_PTR target: wraps with `fromGodotObjectPtr`
    /// - GDCC_PTR value → GODOT_PTR target: appends `->_object`
    /// - Same kind or NON_OBJECT: no conversion
    private @NotNull String convertPtrIfNeeded(@NotNull String code,
                                               @NotNull PtrKind valuePtrKind,
                                               @NotNull GdObjectType targetObjType) {
        var targetPtrKind = resolvePtrKind(targetObjType);
        if (valuePtrKind == PtrKind.GODOT_PTR && targetPtrKind == PtrKind.GDCC_PTR) {
            return fromGodotObjectPtr(code, targetObjType);
        }
        if (valuePtrKind == PtrKind.GDCC_PTR && targetPtrKind == PtrKind.GODOT_PTR) {
            // GDCC object pointers have _object field containing the Godot object pointer
            return code + "->_object";
        }
        return code;
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
            case NO -> {
            }
            case UNKNOWN -> out.append("try_own_object(").append(godotPtrCode).append(");\n");
        }
    }

    /// Emits release_object or try_release_object based on RefCounted status.
    private void releaseOrTryRelease(@NotNull String godotPtrCode, @NotNull GdObjectType objType) {
        var status = classRegistry().getRefCountedStatus(objType);
        switch (status) {
            case YES -> out.append("release_object(").append(godotPtrCode).append(");\n");
            case NO -> {
            }
            case UNKNOWN -> out.append("try_release_object(").append(godotPtrCode).append(");\n");
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean checkInPrepareBlock() {
        return currentBlock != null && "__prepare__".equals(currentBlock.id());
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean checkInFinallyBlock() {
        return currentBlock != null && "__finally__".equals(currentBlock.id());
    }

    private boolean checkGlobalFuncRequireGodotRawPtr(@NotNull String funcName) {
        if (funcName.endsWith("_with_gdcc_Object")) {
            return false;
        }
        if (funcName.startsWith("godot_")) {
            return true;
        }
        return funcName.endsWith("own_object") || funcName.endsWith("release_object") ||
                funcName.equals("try_destroy_object") || funcName.equals("gdcc_object_from_godot_object_ptr");
    }

    private boolean checkGlobalFuncReturnGodotRawPtr(@NotNull String funcName) {
        return funcName.startsWith("godot_");
    }

    /// Indicates the pointer kind of an object value reference.
    /// Used to determine whether conversion is needed when passing to/from GDExtension APIs.
    public enum PtrKind {
        /// GDCC object pointer (holds wrapper with `_object` field, e.g. `MyClass*`)
        GDCC_PTR,
        /// Godot/engine raw object pointer (e.g. `godot_Node*`, `GDExtensionObjectPtr`)
        GODOT_PTR,
        /// Not an object pointer (primitives, value-semantic types, etc.)
        NON_OBJECT
    }

    public sealed interface ValueRef permits VarValue, ExprValue, StringNamePtrLiteralValue, StringPtrLiteralValue, TempVar {
        @NotNull GdType type();

        @NotNull String generateCode();

        @NotNull PtrKind ptrKind();
    }

    public record VarValue(@NotNull LirVariable variable, @NotNull PtrKind ptrKind) implements ValueRef {
        public VarValue {
            Objects.requireNonNull(variable);
            Objects.requireNonNull(ptrKind);
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

    public record ExprValue(@NotNull String code, @NotNull GdType type, @NotNull PtrKind ptrKind) implements ValueRef {
        public ExprValue {
            Objects.requireNonNull(code);
            Objects.requireNonNull(type);
            Objects.requireNonNull(ptrKind);
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

    public record StringNamePtrLiteralValue(@NotNull String value) implements ValueRef {
        public StringNamePtrLiteralValue {
            Objects.requireNonNull(value);
        }

        @Override
        public @NotNull GdType type() {
            return GdStringNameType.STRING_NAME;
        }

        @Override
        public @NotNull String generateCode() {
            return "GD_STATIC_SN(u8\"" + escapeStringLiteral(value) + "\")";
        }

        @Override
        public @NotNull PtrKind ptrKind() {
            return PtrKind.NON_OBJECT;
        }
    }

    public record StringPtrLiteralValue(@NotNull String value) implements ValueRef {
        public StringPtrLiteralValue {
            Objects.requireNonNull(value);
        }

        @Override
        public @NotNull GdType type() {
            return GdStringType.STRING;
        }

        @Override
        public @NotNull String generateCode() {
            return "GD_STATIC_S(u8\"" + escapeStringLiteral(value) + "\")";
        }

        @Override
        public @NotNull PtrKind ptrKind() {
            return PtrKind.NON_OBJECT;
        }
    }

    public sealed interface TargetRef permits VarTargetRef, TempVar {
        @NotNull GdType type();

        @NotNull String generateCode();

        boolean isRef();
    }

    public record VarTargetRef(@NotNull LirVariable variable) implements TargetRef {
        public VarTargetRef {
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

        @Override
        public boolean isRef() {
            return variable.ref();
        }
    }

    public static final class TempVar implements ValueRef, TargetRef {
        private final @NotNull String name;
        private final @NotNull GdType type;
        private final @Nullable String initCode;
        private final @NotNull PtrKind ptrKind;
        private final boolean initializedAtDeclaration;
        private boolean initialized;

        public TempVar(@NotNull String name,
                       @NotNull GdType type,
                       @Nullable String initCode,
                       @NotNull PtrKind ptrKind,
                       boolean initializedAtDeclaration) {
            this.name = Objects.requireNonNull(name);
            this.type = Objects.requireNonNull(type);
            this.initCode = initCode;
            this.ptrKind = Objects.requireNonNull(ptrKind);
            this.initializedAtDeclaration = initializedAtDeclaration;
            this.initialized = false;
        }

        public @NotNull String name() {
            return name;
        }

        @Override
        public @NotNull GdType type() {
            return type;
        }

        public @Nullable String initCode() {
            return initCode;
        }

        @Override
        public @NotNull PtrKind ptrKind() {
            return ptrKind;
        }

        public boolean hasInitializer() {
            return initializedAtDeclaration && initCode != null;
        }

        public boolean initialized() {
            return initialized;
        }

        public void setInitialized(boolean initialized) {
            this.initialized = initialized;
        }

        @Override
        public @NotNull String generateCode() {
            return name;
        }

        @Override
        public boolean isRef() {
            return false;
        }
    }

    private record RenderResult(@NotNull String code, @NotNull List<TempVar> temps) {
        private RenderResult {
            Objects.requireNonNull(code);
            Objects.requireNonNull(temps);
        }
    }
}
