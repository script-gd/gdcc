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

    /// Creates a special target reference that discards call return values.
    public @NotNull DiscardRef discardRef() {
        return DiscardRef.INSTANCE;
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
        rejectVarargUtilityViaNonUtilityPath(funcName);
        validateCallArgs(funcName, args);
        var argsResult = renderArgs(funcName, args);
        emitTempDecls(argsResult.temps());
        out.append(funcName).append("(").append(argsResult.code()).append(");\n");
        emitTempDestroys(argsResult.temps());
        return this;
    }

    /// Calls a utility function and always renders the canonical C symbol name (`godot_<name>`).
    /// Supports utility vararg ABI by appending argv/argc automatically.
    public @NotNull CBodyBuilder callUtilityVoid(@NotNull String funcName, @NotNull List<ValueRef> args) {
        var utility = requireUtilityCall(funcName);
        return callUtilityVoid(utility, args);
    }

    /// Calls a resolved utility function and emits a statement call.
    public @NotNull CBodyBuilder callUtilityVoid(@NotNull CGenHelper.UtilityCallResolution utility,
                                                 @NotNull List<ValueRef> args) {
        validateCallArgs(utility, args, true);
        var argsResult = renderUtilityArgs(utility, args);
        emitTempDecls(argsResult.temps());
        emitRawLines(argsResult.preCallLines());
        out.append(utility.cFunctionName()).append("(").append(argsResult.code()).append(");\n");
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
        var discardResult = target instanceof DiscardRef;
        if (!discardResult) {
            checkTargetAssignable(target);
        }
        rejectVarargUtilityViaNonUtilityPath(funcName);

        var resolvedReturnType = resolveReturnType(funcName, returnType);
        validateCallArgs(funcName, args);
        if (discardResult) {
            validateDiscardableReturnType(funcName, resolvedReturnType);
        } else {
            validateReturnType(funcName, resolvedReturnType, target.type());
        }

        var argsResult = renderArgs(funcName, args);
        emitTempDecls(argsResult.temps());

        var callExpr = funcName + "(" + argsResult.code() + ")";
        if (discardResult) {
            out.append(callExpr).append(";\n");
        } else {
            emitCallResultAssignment(target, funcName, callExpr);
        }

        emitTempDestroys(argsResult.temps());
        return this;
    }

    /// Calls a utility function and assigns its non-void return value to target.
    /// Supports utility vararg ABI by appending argv/argc automatically.
    public @NotNull CBodyBuilder callUtilityAssign(@NotNull TargetRef target,
                                                   @NotNull String funcName,
                                                   @NotNull List<ValueRef> args) {
        var utility = requireUtilityCall(funcName);
        return callUtilityAssign(target, utility, args);
    }

    /// Calls a resolved utility function and either assigns or discards its non-void return value.
    public @NotNull CBodyBuilder callUtilityAssign(@NotNull TargetRef target,
                                                   @NotNull CGenHelper.UtilityCallResolution utility,
                                                   @NotNull List<ValueRef> args) {
        var discardResult = target instanceof DiscardRef;
        if (!discardResult) {
            checkTargetAssignable(target);
        }
        validateCallArgs(utility, args, true);

        var returnType = utility.signature().returnType();
        if (returnType == null || returnType instanceof GdVoidType) {
            throw invalidInsn("Utility function '" + utility.lookupName() + "' has no return value");
        }
        if (!discardResult) {
            checkAssignable(returnType, target.type());
        }

        var argsResult = renderUtilityArgs(utility, args);
        emitTempDecls(argsResult.temps());
        emitRawLines(argsResult.preCallLines());

        var callExpr = utility.cFunctionName() + "(" + argsResult.code() + ")";
        if (discardResult) {
            out.append(callExpr).append(";\n");
        } else {
            emitCallResultAssignment(target, utility.cFunctionName(), callExpr);
        }

        emitTempDestroys(argsResult.temps());
        return this;
    }

    /// Common logic for writing a call expression result into a target variable.
    /// Handles: old-value destroy/release → ptr conversion → assignment → own new object → mark initialized.
    private void emitCallResultAssignment(@NotNull TargetRef target,
                                          @NotNull String cFuncName,
                                          @NotNull String callExpr) {
        var targetCode = target.generateCode();
        var targetType = target.type();
        var canDestroyOldValue = canDestroyOldValue(target);

        if (canDestroyOldValue && !checkInPrepareBlock() && targetType.isDestroyable()) {
            if (targetType instanceof GdObjectType objType) {
                emitReleaseObject(targetCode, objType);
            } else {
                emitDestroy(targetCode, targetType);
            }
        }

        var finalExpr = callExpr;
        if (checkGlobalFuncReturnGodotRawPtr(cFuncName)
                && targetType instanceof GdObjectType objType
                && objType.checkGdccType(classRegistry())) {
            finalExpr = fromGodotObjectPtr(finalExpr, objType);
        }

        out.append(targetCode).append(" = ").append(finalExpr).append(";\n");

        if (targetType instanceof GdObjectType objType) {
            emitOwnObject(targetCode, objType);
        }

        markTargetInitialized(target);
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
        var callArgs = new ArrayList<CallArg>(args.size());
        for (var arg : args) {
            callArgs.add(new ValueCallArg(arg));
        }
        return renderCallArgs(funcName, callArgs);
    }

    private @NotNull RenderResult renderCallArgs(@NotNull String funcName, @NotNull List<CallArg> args) {
        var requireGodotRawPtr = checkGlobalFuncRequireGodotRawPtr(funcName);
        var rendered = new StringBuilder();
        var temps = new ArrayList<TempVar>();
        for (var i = 0; i < args.size(); i++) {
            if (i > 0) {
                rendered.append(", ");
            }
            switch (args.get(i)) {
                case ValueCallArg(var value) -> {
                    var argResult = renderArgument(value, requireGodotRawPtr);
                    rendered.append(argResult.code());
                    temps.addAll(argResult.temps());
                }
                case RawCallArg(var code) -> rendered.append(code);
            }
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
        var utility = resolveUtilityCall(funcName);
        if (utility == null) {
            return null;
        }
        return utility.signature().returnType();
    }

    private void validateCallArgs(@NotNull String funcName, @NotNull List<ValueRef> args) {
        var utility = resolveUtilityCall(funcName);
        if (utility == null) {
            return;
        }
        validateCallArgs(utility, args, false, false);
    }

    private void validateCallArgs(@NotNull CGenHelper.UtilityCallResolution utility,
                                  @NotNull List<ValueRef> args,
                                  boolean strictVarargVariant) {
        validateCallArgs(utility, args, strictVarargVariant, true);
    }

    private void validateCallArgs(@NotNull CGenHelper.UtilityCallResolution utility,
                                  @NotNull List<ValueRef> args,
                                  boolean strictVarargVariant,
                                  boolean allowDefaultValueOmission) {
        var signature = utility.signature();
        var paramCount = signature.parameterCount();
        var isVararg = signature.isVararg();
        if (!isVararg && args.size() > paramCount) {
            throw invalidInsn("Too many arguments for utility function '" + utility.lookupName() + "': expected " +
                    paramCount + ", got " + args.size());
        }
        if (args.size() < paramCount) {
            if (!allowDefaultValueOmission) {
                throw invalidInsn("Too few arguments for utility function '" + utility.lookupName() + "': expected " +
                        (isVararg ? "at least " : "") + paramCount + ", got " + args.size());
            }
            for (var i = args.size(); i < paramCount; i++) {
                var param = signature.parameters().get(i);
                if (param.defaultValue() == null) {
                    throw invalidInsn("Too few arguments for utility function '" + utility.lookupName() + "': expected " +
                            (isVararg ? "at least " : "") + paramCount + ", got " + args.size());
                }
                if (param.type() == null) {
                    throw invalidInsn("Utility function '" + utility.lookupName() + "' parameter #" + (i + 1) +
                            " has default value but no type information");
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
        if (!isVararg || !strictVarargVariant) {
            return;
        }
        for (var i = paramCount; i < args.size(); i++) {
            var arg = args.get(i);
            if (!classRegistry().checkAssignable(arg.type(), GdVariantType.VARIANT)) {
                throw invalidInsn("Vararg argument #" + (i - paramCount + 1) + " of utility '" +
                        utility.lookupName() + "' must be Variant, got '" + arg.type().getTypeName() + "'");
            }
            if (!(arg instanceof VarValue)) {
                throw invalidInsn("Vararg argument #" + (i - paramCount + 1) + " of utility '" +
                        utility.lookupName() + "' must be a variable");
            }
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

    private void validateDiscardableReturnType(@NotNull String funcName, @Nullable GdType resolvedReturnType) {
        if (resolvedReturnType == null) {
            throw invalidInsn("Return type is required for non-utility function: " + funcName);
        }
        if (resolvedReturnType instanceof GdVoidType) {
            throw invalidInsn("CallAssign discard expects a non-void function: " + funcName);
        }
    }

    /// Renders a ValueRef for a conditional expression without emitting code.
    private @NotNull RenderResult renderCondition(@NotNull ValueRef condition) {
        if (!(condition.type() instanceof GdBoolType)) {
            throw invalidInsn("jumpIf condition must be bool, got '" + condition.type().getTypeName() + "'");
        }
        return new RenderResult(condition.generateCode(), List.of());
    }

    private @NotNull CGenHelper.UtilityCallResolution requireUtilityCall(@NotNull String funcName) {
        var utility = resolveUtilityCall(funcName);
        if (utility == null) {
            throw invalidInsn("Global utility function '" + funcName + "' not found in registry");
        }
        return utility;
    }

    /// Guards against accidentally calling a vararg utility via `callVoid`/`callAssign`
    /// instead of the dedicated `callUtilityVoid`/`callUtilityAssign` APIs.
    /// Non-vararg utilities are allowed through the generic path since they don't need argv/argc handling.
    private void rejectVarargUtilityViaNonUtilityPath(@NotNull String funcName) {
        var utility = resolveUtilityCall(funcName);
        if (utility != null && utility.signature().isVararg()) {
            throw invalidInsn("Vararg utility function '" + utility.lookupName() +
                    "' must be called via callUtilityVoid/callUtilityAssign, not callVoid/callAssign");
        }
    }

    private @Nullable CGenHelper.UtilityCallResolution resolveUtilityCall(@NotNull String funcName) {
        return helper.resolveUtilityCall(funcName);
    }

    /// Renders arguments for a utility function call, including vararg argv/argc handling.
    ///
    /// For vararg utilities, extra arguments beyond fixed parameters are collected into a
    /// temporary `const godot_Variant*[]` array. The pointers in this array point directly
    /// at the IR variables (`&$varId`), which is safe because `validateCallArgs` guarantees
    /// that all vararg extra arguments are `VarValue` references with stable addresses.
    private @NotNull UtilityArgsRenderResult renderUtilityArgs(@NotNull CGenHelper.UtilityCallResolution utility,
                                                               @NotNull List<ValueRef> args) {
        var fixedCount = utility.signature().parameterCount();
        var callArgs = new ArrayList<CallArg>();
        var fixedLimit = Math.min(fixedCount, args.size());
        for (var i = 0; i < fixedLimit; i++) {
            callArgs.add(new ValueCallArg(args.get(i)));
        }
        var defaultArgs = appendMissingUtilityDefaultArgs(utility, args, callArgs);

        var preCallLines = new ArrayList<>(defaultArgs.preCallLines());
        if (utility.signature().isVararg()) {
            var extraCount = Math.max(0, args.size() - fixedCount);
            if (extraCount == 0) {
                callArgs.add(new RawCallArg("NULL"));
                callArgs.add(new RawCallArg("(godot_int)0"));
            } else {
                var argvName = newTempName("argv");
                var pointers = new ArrayList<String>(extraCount);
                for (var i = fixedCount; i < args.size(); i++) {
                    pointers.add(renderVarargVariantPointer(utility, args.get(i), i - fixedCount));
                }
                preCallLines.add("const godot_Variant* " + argvName + "[] = { " + String.join(", ", pointers) + " };");
                callArgs.add(new RawCallArg(argvName));
                callArgs.add(new RawCallArg("(godot_int)" + extraCount));
            }
        }
        var rendered = renderCallArgs(utility.cFunctionName(), callArgs);
        var allTemps = new ArrayList<TempVar>(defaultArgs.temps().size() + rendered.temps().size());
        allTemps.addAll(defaultArgs.temps());
        allTemps.addAll(rendered.temps());
        return new UtilityArgsRenderResult(rendered.code(), allTemps, preCallLines);
    }

    private @NotNull UtilityDefaultArgsResult appendMissingUtilityDefaultArgs(@NotNull CGenHelper.UtilityCallResolution utility,
                                                                               @NotNull List<ValueRef> args,
                                                                               @NotNull List<CallArg> callArgs) {
        var signature = utility.signature();
        var paramCount = signature.parameterCount();
        if (args.size() >= paramCount) {
            return new UtilityDefaultArgsResult(List.of(), List.of());
        }
        var defaultTemps = new ArrayList<TempVar>(paramCount - args.size());
        var preCallLines = new ArrayList<String>(paramCount - args.size());
        for (var i = args.size(); i < paramCount; i++) {
            var param = signature.parameters().get(i);
            var paramType = param.type();
            var defaultValue = param.defaultValue();
            if (paramType == null || defaultValue == null) {
                throw invalidInsn("Utility function '" + utility.lookupName() + "' parameter #" + (i + 1) +
                        " is missing type/default metadata required for omitted argument");
            }
            var defaultExpr = renderUtilityDefaultValueExpr(utility, i, paramType, defaultValue);
            var typeName = helper.renderGdTypeName(paramType).toLowerCase();
            var temp = newTempVariable("default_" + typeName, paramType, defaultExpr.expression());
            defaultTemps.add(temp);
            if (defaultExpr.arrayElementTypeForSetTyped() != null) {
                preCallLines.add(renderTypedArraySetTypedLine(temp, defaultExpr.arrayElementTypeForSetTyped()));
            }
            callArgs.add(new ValueCallArg(temp));
        }
        return new UtilityDefaultArgsResult(defaultTemps, preCallLines);
    }

    private @NotNull DefaultValueExprResult renderUtilityDefaultValueExpr(@NotNull CGenHelper.UtilityCallResolution utility,
                                                                          int paramIndex,
                                                                          @NotNull GdType type,
                                                                          @NotNull String defaultValue) {
        var literal = defaultValue.trim();
        if (literal.equals("null")) {
            return new DefaultValueExprResult(renderNullDefaultValueExpr(utility, paramIndex, type), null);
        }
        switch (type) {
            case GdStringType _ when isQuotedStringLiteral(literal) -> {
                var content = literal.substring(1, literal.length() - 1);
                return new DefaultValueExprResult(
                        "godot_new_String_with_String(" + renderStaticStringLiteral(content) + ")",
                        null
                );
            }
            case GdStringNameType _ when (isQuotedStringNameLiteral(literal) || isQuotedStringLiteral(literal)) -> {
                var offset = literal.startsWith("&\"") ? 2 : 1;
                var content = literal.substring(offset, literal.length() - 1);
                return new DefaultValueExprResult(
                        "godot_new_StringName_with_StringName(" + renderStaticStringNameLiteral(content) + ")",
                        null
                );
            }
            case GdPrimitiveType _ -> {
                return new DefaultValueExprResult(literal, null);
            }
            default -> {}
        }
        var leftParen = literal.indexOf('(');
        if (leftParen > 0 && literal.endsWith(")")) {
            var ctorTypeLiteral = literal.substring(0, leftParen).trim();
            var ctorArgs = literal.substring(leftParen + 1, literal.length() - 1).trim();
            return renderBuiltinDefaultConstructorExpr(type, ctorTypeLiteral, ctorArgs);
        }
        throw invalidInsn("Unsupported default value literal '" + defaultValue + "' for utility function '" +
                utility.lookupName() + "' parameter #" + (paramIndex + 1) + " of type '" + type.getTypeName() + "'");
    }

    private boolean isQuotedStringLiteral(@NotNull String literal) {
        return literal.length() >= 2 && literal.startsWith("\"") && literal.endsWith("\"");
    }

    private boolean isQuotedStringNameLiteral(@NotNull String literal) {
        return literal.length() >= 3 && literal.startsWith("&\"") && literal.endsWith("\"");
    }

    private @NotNull String renderNullDefaultValueExpr(@NotNull CGenHelper.UtilityCallResolution utility,
                                                       int paramIndex,
                                                       @NotNull GdType type) {
        if (type instanceof GdObjectType) {
            return "NULL";
        }
        if (type instanceof GdVariantType) {
            return "godot_new_Variant_nil()";
        }
        throw invalidInsn("Unsupported null default value for utility function '" + utility.lookupName() +
                "' parameter #" + (paramIndex + 1) + " of type '" + type.getTypeName() + "'");
    }

    private @NotNull String renderStaticStringLiteral(@NotNull String value) {
        return "GD_STATIC_S(u8\"" + escapeStringLiteral(value) + "\")";
    }

    private @NotNull String renderStaticStringNameLiteral(@NotNull String value) {
        return "GD_STATIC_SN(u8\"" + escapeStringLiteral(value) + "\")";
    }

    private @NotNull DefaultValueExprResult renderBuiltinDefaultConstructorExpr(@NotNull GdType type,
                                                                                 @NotNull String ctorTypeLiteral,
                                                                                 @NotNull String ctorArgsLiteral) {
        var ctorName = helper.renderBuiltinConstructorBaseName(type);
        var ctorArgs = splitCtorArguments(ctorArgsLiteral);
        if (ctorArgs.isEmpty()) {
            validateBuiltinConstructor(type, List.of(), false);
            return new DefaultValueExprResult(ctorName + "()", null);
        }
        var arrayElementType = resolveTypedArrayElementTypeForSetTyped(type, ctorTypeLiteral, ctorArgs);
        if (arrayElementType != null) {
            validateBuiltinConstructor(type, List.of(), false);
            return new DefaultValueExprResult(ctorName + "()", arrayElementType);
        }
        if (ctorArgs.size() == 1 && ctorArgs.getFirst().equals("{}")) {
            validateBuiltinConstructor(type, List.of(), false);
            return new DefaultValueExprResult(ctorName + "()", null);
        }

        var helperCtorArgTypes = resolveHelperTransformCtorArgTypes(type, ctorArgs.size());
        if (helperCtorArgTypes != null) {
            var ctorFunc = helper.renderBuiltinConstructorFunctionNameByTypes(type, helperCtorArgTypes);
            validateBuiltinConstructor(type, helperCtorArgTypes, true);
            return new DefaultValueExprResult(ctorFunc + "(" + String.join(", ", ctorArgs) + ")", null);
        }

        if (type instanceof GdNodePathType
                && ctorArgs.size() == 1
                && isQuotedStringLiteral(ctorArgs.getFirst())) {
            var content = ctorArgs.getFirst().substring(1, ctorArgs.getFirst().length() - 1);
            var ctorArgTypes = List.<GdType>of(GdStringType.STRING);
            validateBuiltinConstructor(type, ctorArgTypes, false);
            var ctorFunc = helper.renderBuiltinConstructorFunctionName(type, List.of("utf8_chars"));
            return new DefaultValueExprResult(ctorFunc + "(u8\"" + escapeStringLiteral(content) + "\")", null);
        }

        var numericCtorArgTypes = resolveBuiltinNumericCtorArgTypes(type, ctorArgs.size());
        if (numericCtorArgTypes != null) {
            validateBuiltinConstructor(type, numericCtorArgTypes, false);
            var ctorFunc = helper.renderBuiltinConstructorFunctionNameByTypes(type, numericCtorArgTypes);
            return new DefaultValueExprResult(ctorFunc + "(" + String.join(", ", ctorArgs) + ")", null);
        }
        throw invalidInsn("Unsupported constructor literal arguments for builtin type '" + type.getTypeName() +
                "': " + String.join(", ", ctorArgs));
    }

    private @Nullable List<GdType> resolveBuiltinNumericCtorArgTypes(@NotNull GdType type, int argCount) {
        return switch (type) {
            case GdFloatVectorType vectorType when argCount == vectorType.getDimension() ->
                    repeatedCtorArgTypes(GdFloatType.FLOAT, argCount);
            case GdIntVectorType vectorType when argCount == vectorType.getDimension() ->
                    repeatedCtorArgTypes(GdIntType.INT, argCount);
            case GdColorType _ when argCount == 3 || argCount == 4 ->
                    repeatedCtorArgTypes(GdFloatType.FLOAT, argCount);
            case GdRect2Type _ when argCount == 4 ->
                    repeatedCtorArgTypes(GdFloatType.FLOAT, argCount);
            case GdRect2iType _ when argCount == 4 ->
                    repeatedCtorArgTypes(GdIntType.INT, argCount);
            default -> null;
        };
    }

    private @Nullable List<GdType> resolveHelperTransformCtorArgTypes(@NotNull GdType type, int argCount) {
        return switch (type) {
            case GdTransform2DType _ when argCount == 6 -> repeatedCtorArgTypes(GdFloatType.FLOAT, 6);
            case GdTransform3DType _ when argCount == 12 -> repeatedCtorArgTypes(GdFloatType.FLOAT, 12);
            default -> null;
        };
    }

    private @NotNull List<GdType> repeatedCtorArgTypes(@NotNull GdType argType, int count) {
        var suffixes = new ArrayList<GdType>(count);
        for (var i = 0; i < count; i++) {
            suffixes.add(argType);
        }
        return suffixes;
    }

    private @Nullable GdType resolveTypedArrayElementTypeForSetTyped(@NotNull GdType type,
                                                                      @NotNull String ctorTypeLiteral,
                                                                      @NotNull List<String> ctorArgs) {
        if (!(type instanceof GdArrayType arrayType)) {
            return null;
        }
        if (ctorArgs.size() == 1 && "[]".equals(ctorArgs.getFirst())) {
            var parsedCtorType = ClassRegistry.tryParseTextType(ctorTypeLiteral);
            if (parsedCtorType instanceof GdArrayType literalArrayType) {
                return literalArrayType.getValueType();
            }
            return arrayType.getValueType();
        }
        return null;
    }

    private void validateBuiltinConstructor(@NotNull GdType type,
                                            @NotNull List<GdType> argTypes,
                                            boolean skipApiValidation) {
        if (skipApiValidation) {
            return;
        }
        if (!helper.hasBuiltinConstructor(type, argTypes)) {
            var argTypeNames = new ArrayList<String>(argTypes.size());
            for (var argType : argTypes) {
                argTypeNames.add(helper.renderGdTypeName(argType));
            }
            throw invalidInsn("Builtin constructor validation failed: '" + helper.renderGdTypeName(type) +
                    "' with args [" + String.join(", ", argTypeNames) + "] is not defined in ExtensionBuiltinClass");
        }
    }

    private @NotNull String renderTypedArraySetTypedLine(@NotNull TempVar temp, @NotNull GdType elementType) {
        var gdType = elementType.getGdExtensionType();
        if (gdType == null) {
            throw invalidInsn("Typed array element type '" + elementType.getTypeName() + "' has no GDExtension variant type");
        }
        var classNamePtr = "GD_STATIC_SN(u8\"\")";
        if (elementType instanceof GdObjectType objectType) {
            classNamePtr = renderStaticStringNameLiteral(objectType.getTypeName());
        }
        return "godot_array_set_typed(&" + temp.name() + ", GDEXTENSION_VARIANT_TYPE_" + gdType.name() +
                ", " + classNamePtr + ", NULL);";
    }

    private @NotNull List<String> splitCtorArguments(@NotNull String argsLiteral) {
        var trimmed = argsLiteral.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        var args = new ArrayList<String>();
        var token = new StringBuilder();
        var parenDepth = 0;
        var bracketDepth = 0;
        var braceDepth = 0;
        var inString = false;
        var escaped = false;
        for (var i = 0; i < trimmed.length(); i++) {
            var ch = trimmed.charAt(i);
            if (inString) {
                token.append(ch);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            switch (ch) {
                case '"' -> {
                    inString = true;
                    token.append(ch);
                }
                case '(' -> {
                    parenDepth++;
                    token.append(ch);
                }
                case ')' -> {
                    parenDepth--;
                    token.append(ch);
                }
                case '[' -> {
                    bracketDepth++;
                    token.append(ch);
                }
                case ']' -> {
                    bracketDepth--;
                    token.append(ch);
                }
                case '{' -> {
                    braceDepth++;
                    token.append(ch);
                }
                case '}' -> {
                    braceDepth--;
                    token.append(ch);
                }
                case ',' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        args.add(token.toString().trim());
                        token.setLength(0);
                    } else {
                        token.append(ch);
                    }
                }
                default -> token.append(ch);
            }
        }
        if (!token.isEmpty()) {
            args.add(token.toString().trim());
        }
        return args;
    }

    /// Renders the C pointer expression for a vararg extra argument.
    /// Pre-condition: validateCallArgs has already verified that value is a VarValue of Variant type.
    private @NotNull String renderVarargVariantPointer(@NotNull CGenHelper.UtilityCallResolution utility,
                                                       @NotNull ValueRef value,
                                                       int varargIndex) {
        if (!(value instanceof VarValue varValue)) {
            throw invalidInsn("Vararg argument #" + (varargIndex + 1) + " of utility '" +
                    utility.lookupName() + "' must be a variable");
        }
        if (!classRegistry().checkAssignable(varValue.type(), GdVariantType.VARIANT)) {
            throw invalidInsn("Vararg argument #" + (varargIndex + 1) + " of utility '" +
                    utility.lookupName() + "' must be Variant, got '" + varValue.type().getTypeName() + "'");
        }
        var code = varValue.generateCode();
        if (varValue.variable().ref()) {
            return code;
        }
        return "&" + code;
    }

    private void emitRawLines(@NotNull List<String> lines) {
        for (var line : lines) {
            out.append(line).append("\n");
        }
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
    public boolean checkInFinallyBlock() {
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

    public sealed interface TargetRef permits VarTargetRef, TempVar, DiscardRef {
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

    public static final class DiscardRef implements TargetRef {
        private static final DiscardRef INSTANCE = new DiscardRef();

        private DiscardRef() {
        }

        @Override
        public @NotNull GdType type() {
            throw new IllegalStateException("DiscardRef does not carry a target type");
        }

        @Override
        public @NotNull String generateCode() {
            throw new IllegalStateException("DiscardRef does not generate target code");
        }

        @Override
        public boolean isRef() {
            return false;
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

    private record UtilityArgsRenderResult(@NotNull String code,
                                           @NotNull List<TempVar> temps,
                                           @NotNull List<String> preCallLines) {
        private UtilityArgsRenderResult {
            Objects.requireNonNull(code);
            Objects.requireNonNull(temps);
            Objects.requireNonNull(preCallLines);
        }
    }

    private record UtilityDefaultArgsResult(@NotNull List<TempVar> temps,
                                            @NotNull List<String> preCallLines) {
        private UtilityDefaultArgsResult {
            Objects.requireNonNull(temps);
            Objects.requireNonNull(preCallLines);
        }
    }

    private record DefaultValueExprResult(@NotNull String expression,
                                          @Nullable GdType arrayElementTypeForSetTyped) {
        private DefaultValueExprResult {
            Objects.requireNonNull(expression);
        }
    }

    private sealed interface CallArg permits ValueCallArg, RawCallArg {
    }

    private record ValueCallArg(@NotNull ValueRef value) implements CallArg {
        private ValueCallArg {
            Objects.requireNonNull(value);
        }
    }

    private record RawCallArg(@NotNull String code) implements CallArg {
        private RawCallArg {
            Objects.requireNonNull(code);
        }
    }
}
