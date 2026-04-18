package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.backend.c.gen.binding.BindingData;
import dev.superice.gdcc.backend.c.gen.binding.BoundMetadata;
import dev.superice.gdcc.backend.c.gen.binding.EngineMethodHelperParam;
import dev.superice.gdcc.backend.c.gen.insn.OperatorResolver;
import dev.superice.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import dev.superice.gdcc.exception.InvalidInsnException;
import dev.superice.gdcc.exception.NotImplementedException;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.lir.LirModule;
import dev.superice.gdcc.lir.LirPropertyDef;
import dev.superice.gdcc.lir.LirVariable;
import dev.superice.gdcc.lir.insn.BinaryOpInsn;
import dev.superice.gdcc.lir.insn.UnaryOpInsn;
import dev.superice.gdcc.scope.*;
import dev.superice.gdcc.scope.resolver.ScopeTypeParsers;
import dev.superice.gdcc.type.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Pattern;

import static dev.superice.gdcc.util.StringUtil.escapeStringLiteral;

public final class CGenHelper {
    private static final String GODOT_UTILITY_PREFIX = "godot_";
    private static final String VARIANT_WRITEBACK_HELPER_NAME = "gdcc_variant_requires_writeback";
    private static final Pattern NON_C_IDENTIFIER_PATTERN = Pattern.compile("[^a-z0-9_]");
    private static final FunctionSignature VARIANT_WRITEBACK_HELPER_SIGNATURE = new FunctionSignature(
            VARIANT_WRITEBACK_HELPER_NAME,
            List.of(new FunctionSignature.Parameter("value", GdVariantType.VARIANT, null)),
            false,
            GdBoolType.BOOL
    );

    private final @NotNull CodegenContext context;
    private final @NotNull CBuiltinBuilder builtinBuilder;
    private final @NotNull OperatorResolver operatorResolver = new OperatorResolver();
    private final @NotNull Set<BindingData> bindingDataSet = new HashSet<>();

    public CGenHelper(@NotNull CodegenContext context, @NotNull List<? extends ClassDef> classDefs) {
        this.context = context;
        this.builtinBuilder = new CBuiltinBuilder(this);
        this.collectBindingData(classDefs);
    }

    public record OperatorEvaluatorHelperSpec(
            @NotNull String functionName,
            boolean unary,
            @NotNull String operatorEnumLiteral,
            @NotNull GdType leftType,
            @Nullable GdType rightType,
            @NotNull GdType returnType,
            @NotNull String leftVariantTypeEnumLiteral,
            @Nullable String rightVariantTypeEnumLiteral
    ) {
        public OperatorEvaluatorHelperSpec {
            Objects.requireNonNull(functionName);
            Objects.requireNonNull(operatorEnumLiteral);
            Objects.requireNonNull(leftType);
            Objects.requireNonNull(returnType);
            Objects.requireNonNull(leftVariantTypeEnumLiteral);
            if (unary && rightType != null) {
                throw new IllegalArgumentException("Unary evaluator helper must not have rightType");
            }
            if (unary && rightVariantTypeEnumLiteral != null) {
                throw new IllegalArgumentException("Unary evaluator helper must not have rightVariantTypeEnumLiteral");
            }
            if (!unary && rightType == null) {
                throw new IllegalArgumentException("Binary evaluator helper must have rightType");
            }
            if (!unary && rightVariantTypeEnumLiteral == null) {
                throw new IllegalArgumentException("Binary evaluator helper must have rightVariantTypeEnumLiteral");
            }
        }
    }

    private record TypedContainerRuntimeLeaf(
            @NotNull String typeIntLiteral,
            @NotNull String classNameExpr,
            boolean objectLeaf
    ) {
    }

    public @NotNull List<BindingData> getBindingDataList() {
        return List.copyOf(bindingDataSet);
    }

    public @NotNull List<OperatorEvaluatorHelperSpec> collectOperatorEvaluatorHelperSpecs(@NotNull LirModule module) {
        var specsByName = new LinkedHashMap<String, OperatorEvaluatorHelperSpec>();
        for (var classDef : module.getClassDefs()) {
            collectClassOperatorEvaluatorHelperSpecs(specsByName, classDef);
        }
        return List.copyOf(specsByName.values());
    }

    public @NotNull String renderOperatorEvaluatorHelperTypeInC(@NotNull GdType type) {
        if (type instanceof GdObjectType) {
            return "GDExtensionObjectPtr";
        }
        return renderGdTypeRefInC(type);
    }

    public @NotNull String renderOperatorEvaluatorHelperReturnTypeInC(@NotNull GdType type) {
        if (type instanceof GdObjectType) {
            return "GDExtensionObjectPtr";
        }
        return renderGdTypeInC(type);
    }

    public @NotNull String renderOperatorEvaluatorArgExpr(@NotNull GdType type, @NotNull String argName) {
        if (type instanceof GdPrimitiveType || type instanceof GdObjectType) {
            return "&" + argName;
        }
        return argName;
    }

    public @NotNull String renderDefaultValueExprInC(@NotNull GdType type) {
        return CBodyBuilder.renderDefaultValueExpr(type);
    }

    private void collectClassOperatorEvaluatorHelperSpecs(@NotNull Map<String, OperatorEvaluatorHelperSpec> specsByName,
                                                          @NotNull LirClassDef classDef) {
        for (var func : classDef.getFunctions()) {
            var bodyBuilder = new CBodyBuilder(this, classDef, func);
            for (var block : func) {
                var instructions = block.getInstructions();
                for (var i = 0; i < instructions.size(); i++) {
                    var instruction = instructions.get(i);
                    bodyBuilder.setCurrentPosition(block, i, instruction);
                    switch (instruction) {
                        case UnaryOpInsn unaryOpInsn -> collectUnaryEvaluatorHelperSpec(
                                specsByName, bodyBuilder, func, unaryOpInsn
                        );
                        case BinaryOpInsn binaryOpInsn -> collectBinaryEvaluatorHelperSpec(
                                specsByName, bodyBuilder, func, binaryOpInsn
                        );
                        default -> {
                        }
                    }
                }
            }
        }
    }

    private void collectUnaryEvaluatorHelperSpec(@NotNull Map<String, OperatorEvaluatorHelperSpec> specsByName,
                                                 @NotNull CBodyBuilder bodyBuilder,
                                                 @NotNull LirFunctionDef func,
                                                 @NotNull UnaryOpInsn instruction) {
        var operandVar = resolveOperatorOperandVariable(func, instruction.operandId(), "operand");
        var decision = operatorResolver.resolveUnaryPath(bodyBuilder, instruction.op(), operandVar.type());
        if (decision.path() != OperatorResolver.OperatorPath.BUILTIN_EVALUATOR ||
                decision.semanticResultType() == null) {
            return;
        }
        var semanticReturnType = decision.semanticResultType();
        var functionName = operatorResolver.renderUnaryEvaluatorHelperName(
                instruction.op(), operandVar.type(), semanticReturnType
        );
        specsByName.putIfAbsent(functionName, new OperatorEvaluatorHelperSpec(
                functionName,
                true,
                operatorResolver.resolveVariantOperatorEnumLiteral(instruction.op()),
                operandVar.type(),
                null,
                semanticReturnType,
                operatorResolver.resolveVariantTypeEnumLiteral(bodyBuilder, operandVar.type()),
                null
        ));
    }

    private void collectBinaryEvaluatorHelperSpec(@NotNull Map<String, OperatorEvaluatorHelperSpec> specsByName,
                                                  @NotNull CBodyBuilder bodyBuilder,
                                                  @NotNull LirFunctionDef func,
                                                  @NotNull BinaryOpInsn instruction) {
        var leftVar = resolveOperatorOperandVariable(func, instruction.leftId(), "left");
        var rightVar = resolveOperatorOperandVariable(func, instruction.rightId(), "right");
        var decision = operatorResolver.resolveBinaryPath(bodyBuilder, instruction.op(), leftVar.type(), rightVar.type());
        if (decision.path() != OperatorResolver.OperatorPath.BUILTIN_EVALUATOR ||
                decision.semanticResultType() == null) {
            return;
        }
        var functionName = operatorResolver.renderBinaryEvaluatorHelperName(
                instruction.op(),
                leftVar.type(),
                rightVar.type(),
                decision.semanticResultType()
        );
        specsByName.putIfAbsent(functionName, new OperatorEvaluatorHelperSpec(
                functionName,
                false,
                operatorResolver.resolveVariantOperatorEnumLiteral(instruction.op()),
                leftVar.type(),
                rightVar.type(),
                decision.semanticResultType(),
                operatorResolver.resolveVariantTypeEnumLiteral(bodyBuilder, leftVar.type()),
                operatorResolver.resolveVariantTypeEnumLiteral(bodyBuilder, rightVar.type())
        ));
    }

    private @NotNull LirVariable resolveOperatorOperandVariable(@NotNull LirFunctionDef func,
                                                                @NotNull String varId,
                                                                @NotNull String role) {
        var variable = func.getVariableById(varId);
        if (variable == null) {
            throw new IllegalStateException(
                    "Operator " + role + " operand variable '" + varId + "' not found in function '" + func.getName() + "'"
            );
        }
        return variable;
    }

    private void collectBindingData(@NotNull List<? extends ClassDef> classDefs) {
        bindingDataSet.clear();
        for (var classDef : classDefs) {
            // Properties getter and setters binding data
            for (var propertyDef : classDef.getProperties()) {
                bindingDataSet.add(new BindingData(
                        List.of(),
                        propertyDef.getType(),
                        List.of(),
                        false
                ));
                bindingDataSet.add(new BindingData(
                        List.of(propertyDef.getType()),
                        GdVoidType.VOID,
                        List.of(),
                        false
                ));
            }
            // Functions binding data
            for (var functionDef : classDef.getFunctions()) {
                if (functionDef.isHidden() || functionDef.isLambda()) {
                    continue;
                }
                var paramTypes = new ArrayList<GdType>();
                var defaultVariables = new ArrayList<GdType>();
                for (var parameterDef : functionDef.getParameters()) {
                    if (parameterDef.getName().equals("self")) {
                        continue;
                    }
                    paramTypes.add(parameterDef.getType());
                    if (parameterDef.getDefaultValueFunc() != null) {
                        defaultVariables.add(parameterDef.getType());
                    }
                }
                bindingDataSet.add(new BindingData(
                        paramTypes,
                        functionDef.getReturnType(),
                        defaultVariables,
                        functionDef.isStatic()
                ));
            }
            // Virtual methods binding data
            var virtualFunctions = context.classRegistry().getVirtualMethods(classDef.getName());
            for (var functionDef : classDef.getFunctions()) {
                if (!virtualFunctions.containsKey(functionDef.getName())) {
                    continue;
                }
                var paramTypes = new ArrayList<GdType>();
                var defaultVariables = new ArrayList<GdType>();
                if (functionDef.isStatic()) {
                    throw new IllegalStateException("Virtual methods must be instance methods");
                }
                for (var parameterDef : functionDef.getParameters()) {
                    if (parameterDef.getDefaultValueFunc() != null) {
                        defaultVariables.add(parameterDef.getType());
                    }
                }
                bindingDataSet.add(new BindingData(
                        paramTypes,
                        functionDef.getReturnType(),
                        defaultVariables,
                        functionDef.isStatic()
                ));
            }
        }
    }

    public @NotNull String renderGdTypeInC(@NotNull GdType gdType) {
        return switch (gdType) {
            case GdContainerType gdContainerType -> switch (gdContainerType) {
                case GdArrayType gdArrayType -> {
                    if (gdArrayType.getValueType() instanceof GdVariantType) {
                        yield "godot_Array";
                    } else {
                        yield "godot_TypedArray(" + renderGdTypeInC(gdArrayType.getValueType()) + ")";
                    }
                }
                case GdDictionaryType gdDictionaryType -> {
                    if (gdContainerType.getKeyType() instanceof GdVariantType && gdContainerType.getValueType() instanceof GdVariantType) {
                        yield "godot_Dictionary";
                    } else {
                        yield "godot_TypedDictionary(" + renderGdTypeInC(gdDictionaryType.getKeyType()) + ", " + renderGdTypeInC(gdDictionaryType.getValueType()) + ")";
                    }
                }
                case GdPackedArrayType gdPackedArrayType -> "godot_" + gdPackedArrayType.getTypeName();
            };
            case GdObjectType gdObjectType -> {
                if (gdObjectType.checkEngineType(context.classRegistry())) {
                    yield "godot_" + gdObjectType.getTypeName() + "*";
                } else if (gdObjectType.checkGdccType(context.classRegistry())) {
                    yield gdObjectType.getTypeName() + "*";
                } else {
                    yield "GDExtensionObjectPtr";
                }
            }
            case GdVoidType _ -> "void";
            default -> "godot_" + gdType.getTypeName();
        };
    }

    public @NotNull String renderGdTypeRefInC(@NotNull GdType gdType) {
        return switch (gdType) {
            case GdContainerType gdContainerType -> switch (gdContainerType) {
                case GdArrayType gdArrayType -> {
                    if (gdArrayType.getValueType() instanceof GdVariantType) {
                        yield "godot_Array*";
                    } else {
                        yield "godot_TypedArray(" + renderGdTypeInC(gdArrayType.getValueType()) + ")*";
                    }
                }
                case GdDictionaryType gdDictionaryType -> {
                    if (gdContainerType.getKeyType() instanceof GdVariantType && gdContainerType.getValueType() instanceof GdVariantType) {
                        yield "godot_Dictionary*";
                    } else {
                        yield "godot_TypedDictionary(" + renderGdTypeInC(gdDictionaryType.getKeyType()) + ", " + renderGdTypeInC(gdDictionaryType.getValueType()) + ")*";
                    }
                }
                case GdPackedArrayType gdPackedArrayType -> "godot_" + gdPackedArrayType.getTypeName() + "*";
            };
            case GdObjectType gdObjectType -> {
                if (gdObjectType.checkEngineType(context.classRegistry())) {
                    yield "godot_" + gdObjectType.getTypeName() + "*";
                } else if (gdObjectType.checkGdccType(context.classRegistry())) {
                    yield gdObjectType.getTypeName() + "*";
                } else {
                    yield "GDExtensionObjectPtr";
                }
            }
            case GdVoidType _ -> "void*";
            case GdPrimitiveType _ -> "godot_" + gdType.getTypeName();
            default -> "godot_" + gdType.getTypeName() + "*";
        };
    }

    public @NotNull String renderValueRef(@NotNull GdType gdType, @NotNull String v) {
        return switch (gdType) {
            case GdObjectType _, GdPrimitiveType _ -> v;
            default -> "&" + v;
        };
    }

    /// Engine bind accessor symbols must stay backend-owned and collision-free relative to gdextension-lite.
    /// Static and vararg markers remain explicit because later helper surfaces diverge even when bind lookup
    /// still uses the same owner/method/hash triple.
    public @NotNull String renderEngineMethodBindAccessorName(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        var bindSpec = Objects.requireNonNull(
                resolved.engineMethodBindSpec(),
                "Exact engine method bind metadata is required to render accessor names"
        );
        var name = new StringBuilder("gdcc_engine_method_bind_");
        if (resolved.isStatic()) {
            name.append("static_");
        }
        if (resolved.isVararg()) {
            name.append("vararg_");
        }
        name.append(sanitizeCIdentifierFragment(resolved.ownerClassName()))
                .append("_")
                .append(sanitizeCIdentifierFragment(resolved.methodName()))
                .append("_")
                .append(bindSpec.hash());
        return name.toString();
    }

    /// The accessor tries the primary hash first, then compatibility hashes in declared order.
    /// Duplicate or zero hashes are skipped so the generated skeleton stays stable and reviewable.
    public @NotNull List<Long> collectEngineMethodBindLookupHashes(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        var bindSpec = Objects.requireNonNull(
                resolved.engineMethodBindSpec(),
                "Exact engine method bind metadata is required to render lookup hashes"
        );
        var hashes = new LinkedHashSet<Long>();
        hashes.add(bindSpec.hash());
        for (var hashCompatibility : bindSpec.hashCompatibility()) {
            if (hashCompatibility != 0L) {
                hashes.add(hashCompatibility);
            }
        }
        return List.copyOf(hashes);
    }

    /// Direct exact-engine helpers must stay in a backend-owned namespace so later route switches
    /// never collide with gdextension-lite public wrappers.
    public @NotNull String renderEngineMethodCallHelperName(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        var bindSpec = Objects.requireNonNull(
                resolved.engineMethodBindSpec(),
                "Exact engine method bind metadata is required to render helper names"
        );
        var name = new StringBuilder(resolved.isVararg() ? "gdcc_engine_callv_" : "gdcc_engine_call_");
        if (resolved.isStatic()) {
            name.append("static_");
        }
        name.append(sanitizeCIdentifierFragment(resolved.ownerClassName()))
                .append("_")
                .append(sanitizeCIdentifierFragment(resolved.methodName()))
                .append("_")
                .append(bindSpec.hash());
        return name.toString();
    }

    /// Helper parameters intentionally mirror the current callable surface:
    /// - primitive/object slots stay value-shaped
    /// - value-semantic wrappers stay storage-pointer shaped
    /// - phase-5 bitfield compatibility keeps the temporary pointer surface explicit
    public @NotNull List<EngineMethodHelperParam> collectEngineMethodHelperParameters(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        var params = new ArrayList<EngineMethodHelperParam>(resolved.parameters().size());
        for (var i = 0; i < resolved.parameters().size(); i++) {
            var parameter = resolved.parameters().get(i);
            var bitfieldPassByRef = parameter.requiresBitfieldPassByRef();
            var cType = bitfieldPassByRef
                    ? "const " + requireBitfieldPassByRefCType(parameter) + " *"
                    : renderGdTypeRefInC(parameter.type());
            var pointerSurface = bitfieldPassByRef
                    || (!(parameter.type() instanceof GdPrimitiveType) && !(parameter.type() instanceof GdObjectType));
            params.add(new EngineMethodHelperParam(
                    "arg" + i,
                    parameter.type(),
                    cType,
                    pointerSurface,
                    bitfieldPassByRef
            ));
        }
        return List.copyOf(params);
    }

    /// Ptrcall consumes addresses of argument storage slots.
    /// Value-shaped params therefore contribute `&arg`, while wrapper/compat pointer surfaces can
    /// pass the helper parameter expression directly.
    public @NotNull String renderEngineMethodPtrcallSlotExpr(@NotNull EngineMethodHelperParam param) {
        return param.pointerSurface() ? param.name() : "&" + param.name();
    }

    /// Helper-local pack sites need the typed value, not always the raw parameter token.
    /// Wrapper pointer surfaces already match pack helpers, while phase-5 bitfield compatibility
    /// still needs one dereference to get back to the normalized int value.
    public @NotNull String renderEngineMethodHelperValueExpr(@NotNull EngineMethodHelperParam param) {
        return param.bitfieldPassByRef() ? "*" + param.name() : param.name();
    }

    public @NotNull String renderEngineMethodBindLookupErrorDescription(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        return "engine method bind lookup failed: " +
                escapeStringLiteral(resolved.ownerClassName() + "." + resolved.methodName());
    }

    public @NotNull String renderEngineMethodCallErrorDescription(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        return "engine method call failed: " +
                escapeStringLiteral(resolved.ownerClassName() + "." + resolved.methodName());
    }

    public @NotNull String renderVarRef(@NotNull LirFunctionDef func, @NotNull String varName) {
        var varDef = func.getVariableById(varName);
        if (varDef == null) {
            throw new IllegalArgumentException("Variable " + varName + " not found in function " + func.getName());
        }
        if (varDef.ref()) {
            return "$" + varName;
        } else {
            return renderValueRef(varDef.type(), "$" + varName);
        }
    }

    public @NotNull String renderFuncBindName(@NotNull BindingData bindingData) {
        return renderFuncBindName(
                bindingData.returnType(),
                bindingData.paramTypes(),
                bindingData.defaultVariables(),
                bindingData.staticMethod()
        );
    }

    private @NotNull String requireBitfieldPassByRefCType(
            @NotNull BackendMethodCallResolver.MethodParamSpec parameter
    ) {
        var extraData = parameter.bitfieldPassByRefExtraParamSpecData();
        if (extraData == null) {
            throw new IllegalArgumentException("Missing bitfield ABI metadata for parameter '" + parameter.name() + "'");
        }
        return extraData.cType();
    }

    private @NotNull String sanitizeCIdentifierFragment(@NotNull String raw) {
        var normalized = raw.toLowerCase(Locale.ROOT);
        var sanitized = NON_C_IDENTIFIER_PATTERN.matcher(normalized).replaceAll("_");
        sanitized = sanitized.replaceAll("_+", "_");
        if (sanitized.startsWith("_")) {
            sanitized = sanitized.substring(1);
        }
        if (sanitized.endsWith("_")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        if (sanitized.isBlank()) {
            throw new IllegalArgumentException("Identifier fragment becomes empty after sanitization: " + raw);
        }
        return sanitized;
    }

    public @NotNull String renderFuncBindName(@NotNull FunctionDef functionDef) {
        var paramTypes = new ArrayList<GdType>();
        var defaultVarTypes = new ArrayList<GdType>();
        for (var parameterDef : functionDef.getParameters()) {
            if (parameterDef.getName().equals("self")) {
                continue;
            }
            paramTypes.add(parameterDef.getType());
            if (parameterDef.getDefaultValueFunc() != null) {
                defaultVarTypes.add(parameterDef.getType());
            }
        }
        return renderFuncBindName(functionDef.getReturnType(), paramTypes, defaultVarTypes, functionDef.isStatic());
    }

    public @NotNull String renderGetterBindName(@NotNull PropertyDef propertyDef) {
        return renderFuncBindName(propertyDef.getType(), List.of(), List.of(), false);
    }

    public @NotNull String renderSetterBindName(@NotNull PropertyDef propertyDef) {
        return renderFuncBindName(GdVoidType.VOID, List.of(propertyDef.getType()), List.of(), false);
    }

    public @NotNull String renderGdTypeName(@NotNull GdType gdType) {
        return switch (gdType) {
            case GdContainerType gdContainerType -> switch (gdContainerType) {
                case GdArrayType _ -> "Array";
                case GdDictionaryType _ -> "Dictionary";
                case GdPackedArrayType gdPackedArrayType -> gdPackedArrayType.getTypeName();
            };
            case GdVoidType _ -> "void";
            default -> gdType.getTypeName();
        };
    }

    /// Thin wrapper around the shared scope-layer parser for extension metadata normalization.
    ///
    /// The shared parser now understands exported families such as `typeddictionary::K;V`, but the
    /// backend still owns typed-dictionary outward ABI concerns like hint emission and runtime guards.
    public @NotNull GdType parseExtensionType(@Nullable String rawTypeName,
                                              @NotNull String typeUseSite) {
        return ScopeTypeParsers.parseExtensionTypeMetadata(rawTypeName, typeUseSite, context.classRegistry());
    }

    public @NotNull String renderFuncBindName(@Nullable GdType returnType,
                                              @NotNull List<GdType> paramTypes,
                                              @NotNull List<GdType> defaultVarTypes,
                                              boolean staticFunction) {
        var sb = new StringBuilder("_");
        sb.append(paramTypes.size()).append("_arg_");
        for (var paramType : paramTypes) {
            sb.append(renderGdTypeName(paramType)).append("_");
        }
        if (returnType != null && !(returnType instanceof GdVoidType)) {
            sb.append("ret_").append(renderGdTypeName(returnType));
        } else {
            sb.append("no_ret");
        }
        if (!defaultVarTypes.isEmpty()) {
            sb.append("_").append(defaultVarTypes.size()).append("_default_");
            for (var defType : defaultVarTypes) {
                sb.append(renderGdTypeName(defType)).append("_");
            }
            if (sb.lastIndexOf("_") == sb.length() - 1) {
                sb.deleteCharAt(sb.length() - 1);
            }
        }
        if (staticFunction) {
            sb.append("_static");
        }
        return sb.toString();
    }

    public @NotNull String renderUnpackFunctionName(@NotNull GdType type) {
        if (type instanceof GdObjectType objectType) {
            if (objectType.checkGdccType(context.classRegistry())) {
                return "(" + objectType.getTypeName() + "*)godot_new_gdcc_Object_with_Variant";
            } else {
                return "(godot_" + objectType.getTypeName() + "*)godot_new_Object_with_Variant";
            }
        } else {
            return "godot_new_" + renderGdTypeName(type) + "_with_Variant";
        }
    }

    public @NotNull String renderPackFunctionName(@NotNull GdType type) {
        if (type instanceof GdObjectType objectType) {
            if (objectType.checkGdccType(context.classRegistry())) {
                return "gdcc_new_Variant_with_gdcc_Object";
            }
            return "godot_new_Variant_with_Object";
        } else {
            return "godot_new_Variant_with_" + renderGdTypeName(type);
        }
    }

    public @NotNull String renderCopyAssignFunctionName(@NotNull GdType type) {
        return switch (type) {
            case GdObjectType _, GdPrimitiveType _ -> "";
            case GdVoidType _, GdNilType _ ->
                    throw new IllegalArgumentException("Type " + type.getTypeName() + " does not support copy assignment");
            default -> {
                var symbolTypeName = renderGdTypeName(type);
                yield "godot_new_" + symbolTypeName + "_with_" + symbolTypeName;
            }
        };
    }

    public @NotNull String renderDefaultValueFunctionName(@NotNull ParameterDef def) {
        if (def.getDefaultValueFunc() == null) {
            throw new IllegalArgumentException("ParameterDef does not have a default value function");
        }
        if (def.getDefaultValueFunc().startsWith("(")) {
            throw new NotImplementedException("ParameterDef default value function for GdExtension literal is not supported");
        }
        return def.getDefaultValueFunc();
    }

    public @NotNull String renderDestroyFunctionName(@NotNull GdType type) {
        if (!type.isDestroyable()) {
            throw new IllegalArgumentException("Type " + type.getTypeName() + " is not destroyable");
        }
        if (type instanceof GdObjectType) {
            return "godot_object_destroy";
        } else {
            return "godot_" + renderGdTypeName(type) + "_destroy";
        }
    }

    /// Render wrapper-local cleanup for generated `call_func` glue code.
    ///
    /// This is intentionally narrower than ordinary backend destruct semantics:
    /// - only destroyable non-object wrappers materialize an addressable local slot that the wrapper must destroy
    /// - object locals stay as plain pointers here, so they must not be blanket destroy/release'd at wrapper exit
    public @NotNull String renderCallWrapperDestroyStmt(@NotNull GdType type, @NotNull String varName) {
        if (type instanceof GdObjectType || !type.isDestroyable()) {
            return "";
        }
        return renderDestroyFunctionName(type) + "(&" + varName + ");";
    }

    /// Typed Dictionary wrapper preflight only applies to non-generic `Dictionary[K, V]` slots.
    public boolean needsTypedDictionaryCallGuard(@NotNull GdType type) {
        return type instanceof GdDictionaryType dictionaryType && !dictionaryType.isGenericDictionary();
    }

    /// Typed Array wrapper preflight only applies to non-generic `Array[T]` slots.
    public boolean needsTypedArrayCallGuard(@NotNull GdType type) {
        return type instanceof GdArrayType arrayType && !arrayType.isGenericArray();
    }

    /// Render the expected builtin type literal for one typed-array guard element leaf.
    public @NotNull String renderTypedArrayGuardBuiltinTypeLiteral(@NotNull GdType type) {
        return renderTypedArrayRuntimeLeaf(resolveTypedArrayGuardLeaf(type), "element leaf")
                .typeIntLiteral();
    }

    /// Object leaves need extra class/script metadata comparison in the wrapper guard.
    public boolean isTypedArrayGuardObjectLeaf(@NotNull GdType type) {
        return renderTypedArrayRuntimeLeaf(resolveTypedArrayGuardLeaf(type), "element leaf")
                .objectLeaf();
    }

    /// Render the expected class-name literal for one typed-array object leaf.
    public @NotNull String renderTypedArrayGuardClassNameExpr(@NotNull GdType type) {
        return renderTypedArrayRuntimeLeaf(resolveTypedArrayGuardLeaf(type), "element leaf")
                .classNameExpr();
    }

    /// Render the expected builtin type literal for one typed-dictionary guard side.
    public @NotNull String renderTypedDictionaryGuardBuiltinTypeLiteral(@NotNull GdType type,
                                                                        @NotNull String sideName) {
        return renderTypedDictionaryRuntimeLeaf(resolveTypedDictionaryGuardLeaf(type, sideName), sideName + " leaf")
                .typeIntLiteral();
    }

    /// Object leaves need extra class/script metadata comparison in the wrapper guard.
    public boolean isTypedDictionaryGuardObjectLeaf(@NotNull GdType type, @NotNull String sideName) {
        return renderTypedDictionaryRuntimeLeaf(resolveTypedDictionaryGuardLeaf(type, sideName), sideName + " leaf")
                .objectLeaf();
    }

    /// Render the expected class-name literal for one typed-dictionary object leaf.
    public @NotNull String renderTypedDictionaryGuardClassNameExpr(@NotNull GdType type,
                                                                   @NotNull String sideName) {
        return renderTypedDictionaryRuntimeLeaf(resolveTypedDictionaryGuardLeaf(type, sideName), sideName + " leaf")
                .classNameExpr();
    }

    /// Renders the outward-facing metadata literals for a bound slot.
    ///
    /// Current backend-owned outward ABI rules:
    /// - `Variant` still uses `NIL + PROPERTY_USAGE_NIL_IS_VARIANT`
    /// - typed `Array[T]` publishes `PROPERTY_HINT_ARRAY_TYPE` plus one leaf atom whenever `T != Variant`
    /// - typed `Dictionary[K, V]` publishes `PROPERTY_HINT_DICTIONARY_TYPE` plus a flat `key;value`
    ///   hint string whenever either side is stricter than `Variant`
    /// - `class_name` stays on the existing empty default here; typed dictionary leaf identity lives in
    ///   `hint_string`, not in the top-level property info class slot
    public @NotNull BoundMetadata renderBoundMetadata(@NotNull GdType type,
                                                      @NotNull String baseUsageExpr) {
        return renderBoundMetadata(type, baseUsageExpr, "bound slot");
    }

    public @NotNull BoundMetadata renderBoundMetadata(@NotNull GdType type,
                                                      @NotNull String baseUsageExpr,
                                                      @NotNull String useSite) {
        var extensionType = requireBoundMetadataType(type);
        var usageExpr = type instanceof GdVariantType
                ? baseUsageExpr + " | godot_PROPERTY_USAGE_NIL_IS_VARIANT"
                : baseUsageExpr;
        var hintEnumLiteral = "godot_PROPERTY_HINT_NONE";
        var hintStringExpr = "GD_STATIC_S(u8\"\")";
        if (type instanceof GdArrayType arrayType && !arrayType.isGenericArray()) {
            hintEnumLiteral = "godot_PROPERTY_HINT_ARRAY_TYPE";
            hintStringExpr = "GD_STATIC_S(u8\"" + escapeStringLiteral(renderTypedArrayHintString(arrayType, useSite)) + "\")";
        } else if (type instanceof GdDictionaryType dictionaryType && !dictionaryType.isGenericDictionary()) {
            hintEnumLiteral = "godot_PROPERTY_HINT_DICTIONARY_TYPE";
            hintStringExpr = "GD_STATIC_S(u8\"" + escapeStringLiteral(renderTypedDictionaryHintString(dictionaryType)) + "\")";
        }
        return new BoundMetadata(
                "GDEXTENSION_VARIANT_TYPE_" + extensionType.name(),
                hintEnumLiteral,
                hintStringExpr,
                "GD_STATIC_SN(u8\"\")",
                usageExpr
        );
    }

    /// Property registration keeps the current export/non-export base-usage split
    /// while reusing the same outward Variant encoding as method args/returns.
    public @NotNull BoundMetadata renderPropertyMetadata(@NotNull PropertyDef propertyDef) {
        return renderBoundMetadata(propertyDef.getType(), renderPropertyBaseUsageEnum(propertyDef), "property");
    }

    public @NotNull String renderPropertyUsageEnum(@NotNull PropertyDef propertyDef) {
        return renderPropertyMetadata(propertyDef).usageExpr();
    }

    private @NotNull String renderPropertyBaseUsageEnum(@NotNull PropertyDef propertyDef) {
        for (var entry : propertyDef.getAnnotations().entrySet()) {
            if (entry.getKey().equals("export")) {
                return "godot_PROPERTY_USAGE_DEFAULT";
            }
        }
        return "godot_PROPERTY_USAGE_NO_EDITOR";
    }

    private @NotNull GdExtensionTypeEnum requireBoundMetadataType(@NotNull GdType type) {
        var extensionType = type.getGdExtensionType();
        if (extensionType == null) {
            throw new IllegalArgumentException("Type " + type.getTypeName() + " does not have outward GDExtension metadata");
        }
        return extensionType;
    }

    /// Godot encodes typed array outward metadata as one leaf atom.
    /// Backend only sees object leaves that frontend/lowering has already resolved to stable engine/GDCC
    /// object identities. `script leaf` unsupported remains a documented ABI boundary rather than a helper-local
    /// revalidation branch here.
    private @NotNull String renderTypedArrayHintString(@NotNull GdArrayType type, @NotNull String useSite) {
        return renderContainerHintAtom(type.getValueType(), useSite, "typed-array", false);
    }

    /// Shared leaf renderer for typed-container outward hints.
    /// - typed array forbids `Variant` leaf because `Array[Variant]` must stay generic outwardly
    /// - typed dictionary still publishes `Variant` as a valid side atom
    /// - object leaves are emitted directly by name; backend assumes frontend/lowering already resolved them
    private @NotNull String renderContainerHintAtom(@NotNull GdType type,
                                                    @NotNull String useSite,
                                                    @NotNull String containerKind,
                                                    boolean allowVariantLeaf) {
        return switch (type) {
            case GdVariantType _ -> {
                if (allowVariantLeaf) {
                    yield type.getTypeName();
                }
                throw unsupportedOutwardHintLeaf(
                        containerKind,
                        type,
                        useSite,
                        "Variant element must stay generic Array outwardly"
                );
            }
            case GdPackedArrayType _ -> type.getTypeName();
            case GdArrayType arrayType -> {
                if (arrayType.isGenericArray()) {
                    yield "Array";
                }
                throw unsupportedOutwardHintLeaf(
                        containerKind,
                        type,
                        useSite,
                        "nested typed Array leaf is not supported"
                );
            }
            case GdDictionaryType dictionaryType -> {
                if (dictionaryType.isGenericDictionary()) {
                    yield "Dictionary";
                }
                throw unsupportedOutwardHintLeaf(
                        containerKind,
                        type,
                        useSite,
                        "nested typed Dictionary leaf is not supported"
                );
            }
            case GdObjectType _ -> type.getTypeName();
            default -> {
                if (type.getGdExtensionType() == null) {
                    throw unsupportedOutwardHintLeaf(
                            containerKind,
                            type,
                            useSite,
                            "missing outward GDExtension metadata"
                    );
                }
                yield type.getTypeName();
            }
        };
    }

    /// Godot encodes typed dictionary outward metadata as a flat `key;value` string.
    /// We only publish one atom per side here, so nested typed containers must fail fast until we have a
    /// real recursive outward grammar for them.
    private @NotNull String renderTypedDictionaryHintString(@NotNull GdDictionaryType type) {
        return renderContainerHintAtom(type.getKeyType(), "key leaf", "typed-dictionary", true) + ";" +
                renderContainerHintAtom(type.getValueType(), "value leaf", "typed-dictionary", true);
    }

    private @NotNull IllegalArgumentException unsupportedOutwardHintLeaf(@NotNull String containerKind,
                                                                         @NotNull GdType type,
                                                                         @NotNull String useSite,
                                                                         @NotNull String reason) {
        return new IllegalArgumentException(
                "Unsupported " + containerKind + " outward hint leaf '" + type.getTypeName() +
                        "' at " + useSite + ": " + reason
        );
    }

    private @NotNull TypedContainerRuntimeLeaf renderTypedArrayRuntimeLeaf(@NotNull GdType type,
                                                                           @NotNull String useSite) {
        return renderTypedContainerRuntimeLeaf(type, useSite, "typed-array", false);
    }

    private @NotNull TypedContainerRuntimeLeaf renderTypedDictionaryRuntimeLeaf(@NotNull GdType type,
                                                                                @NotNull String useSite) {
        return renderTypedContainerRuntimeLeaf(type, useSite, "typed-dictionary", true);
    }

    /// Typed array and typed dictionary share the same runtime leaf triple shape even though
    /// their outward hint grammars and template blocks stay intentionally separate.
    private @NotNull TypedContainerRuntimeLeaf renderTypedContainerRuntimeLeaf(@NotNull GdType type,
                                                                               @NotNull String useSite,
                                                                               @NotNull String containerKind,
                                                                               boolean allowVariantLeaf) {
        var typeEnum = requireTypedContainerRuntimeLeafType(type, useSite, containerKind, allowVariantLeaf);
        if (type instanceof GdObjectType objectType) {
            return new TypedContainerRuntimeLeaf(
                    "(godot_int)GDEXTENSION_VARIANT_TYPE_" + typeEnum.name(),
                    "GD_STATIC_SN(u8\"" + escapeStringLiteral(objectType.getTypeName()) + "\")",
                    true
            );
        }

        return new TypedContainerRuntimeLeaf(
                "(godot_int)GDEXTENSION_VARIANT_TYPE_" + typeEnum.name(),
                "GD_STATIC_SN(u8\"\")",
                false
        );
    }

    private @NotNull GdExtensionTypeEnum requireTypedContainerRuntimeLeafType(@NotNull GdType type,
                                                                              @NotNull String useSite,
                                                                              @NotNull String containerKind,
                                                                              boolean allowVariantLeaf) {
        return switch (type) {
            case GdVariantType _ -> {
                if (allowVariantLeaf) {
                    yield GdExtensionTypeEnum.NIL;
                }
                throw unsupportedTypedContainerRuntimeLeaf(
                        containerKind,
                        type,
                        useSite,
                        "Variant element must stay generic Array runtime guard"
                );
            }
            case GdArrayType arrayType -> {
                if (arrayType.isGenericArray()) {
                    yield GdExtensionTypeEnum.ARRAY;
                }
                throw unsupportedTypedContainerRuntimeLeaf(
                        containerKind,
                        type,
                        useSite,
                        "nested typed Array leaf is not supported"
                );
            }
            case GdDictionaryType dictionaryType -> {
                if (dictionaryType.isGenericDictionary()) {
                    yield GdExtensionTypeEnum.DICTIONARY;
                }
                throw unsupportedTypedContainerRuntimeLeaf(
                        containerKind,
                        type,
                        useSite,
                        "nested typed Dictionary leaf is not supported"
                );
            }
            default -> {
                var extensionType = type.getGdExtensionType();
                if (extensionType == null) {
                    throw unsupportedTypedContainerRuntimeLeaf(
                            containerKind,
                            type,
                            useSite,
                            "missing runtime GDExtension metadata"
                    );
                }
                yield extensionType;
            }
        };
    }

    private @NotNull IllegalArgumentException unsupportedTypedContainerRuntimeLeaf(@NotNull String containerKind,
                                                                                   @NotNull GdType type,
                                                                                   @NotNull String useSite,
                                                                                   @NotNull String reason) {
        return new IllegalArgumentException(
                "Unsupported " + containerKind + " runtime leaf '" + type.getTypeName() +
                        "' at " + useSite + ": " + reason
        );
    }

    private @NotNull GdType resolveTypedArrayGuardLeaf(@NotNull GdType type) {
        if (!(type instanceof GdArrayType arrayType) || arrayType.isGenericArray()) {
            throw new IllegalArgumentException(
                    "Typed-array guard metadata requested for non-typed Array slot '" + type.getTypeName() + "'"
            );
        }
        return arrayType.getValueType();
    }

    private @NotNull GdType resolveTypedDictionaryGuardLeaf(@NotNull GdType type, @NotNull String sideName) {
        if (!(type instanceof GdDictionaryType dictionaryType) || dictionaryType.isGenericDictionary()) {
            throw new IllegalArgumentException(
                    "Typed-dictionary guard metadata requested for non-typed Dictionary slot '" + type.getTypeName() + "'"
            );
        }
        return switch (sideName) {
            case "key" -> dictionaryType.getKeyType();
            case "value" -> dictionaryType.getValueType();
            default -> throw new IllegalArgumentException("Unknown typed-dictionary guard side: " + sideName);
        };
    }

    /// Renders a variable assignment statement in C, handling Godot object return types properly.
    /// Mainly used for preventing direct assignment of Godot object ptr to GDCC object ptr.
    ///
    /// @param sourceExpr This expr is in C which is a GDExtension function call. It never returns direct GDCC type ptr, but the underlying proxy Godot object ptr.
    public @NotNull String renderVarAssignWithGodotReturn(@NotNull LirFunctionDef func,
                                                          @NotNull String targetVarName,
                                                          @NotNull GdType sourceType,
                                                          @NotNull String sourceExpr) {
        var targetVar = func.getVariableById(targetVarName);
        if (targetVar == null) {
            throw new InvalidInsnException("Variable " + targetVarName + " not found in function " + func.getName());
        }
        if (targetVar.ref()) {
            throw new InvalidInsnException("Variable " + targetVarName + " is a readonly ref in function " + func.getName());
        }
        var registry = context.classRegistry();
        if (!registry.checkAssignable(sourceType, targetVar.type())) {
            throw new InvalidInsnException("Cannot assign value of type " + sourceType.getTypeName() + " to variable " +
                    targetVarName + " of type " + targetVar.type().getTypeName() + " in function " + func.getName());
        }
        if (targetVar.type() instanceof GdObjectType targetType && sourceType instanceof GdObjectType sourceObjectType) {
            var assignExpr = renderObjectAssignExprWithSafeConversion(sourceExpr, sourceObjectType, targetType);
            return "$" + targetVarName + " = " + assignExpr + ";";
        }
        return "$" + targetVarName + " = " + sourceExpr + ";";
    }

    private @NotNull String renderObjectAssignExprWithSafeConversion(@NotNull String sourceExpr,
                                                                     @NotNull GdObjectType sourceType,
                                                                     @NotNull GdObjectType targetType) {
        if (targetType.checkGdccType(context.classRegistry())) {
            var castType = renderGdTypeInC(targetType);
            return "(" + castType + ")gdcc_object_from_godot_object_ptr(" + sourceExpr + ")";
        }

        var convertedSourceExpr = sourceExpr;
        if (sourceType.checkGdccType(context.classRegistry())) {
            convertedSourceExpr = "gdcc_object_to_godot_object_ptr(" + sourceExpr + ", " +
                    renderGdccObjectPtrHelperName(sourceType) + ")";
        }
        if (!targetType.getTypeName().equals(sourceType.getTypeName())) {
            var castType = renderGdTypeInC(targetType);
            return "(" + castType + ")(" + convertedSourceExpr + ")";
        }
        return convertedSourceExpr;
    }

    public boolean checkVirtualMethod(@NotNull ClassDef classDef, @NotNull FunctionDef functionDef) {
        return context.classRegistry().getVirtualMethods(classDef.getName()).containsKey(functionDef.getName());
    }

    public boolean checkVirtualMethod(@NotNull String className, @NotNull String methodName) {
        return context.classRegistry().getVirtualMethods(className).containsKey(methodName);
    }

    public boolean checkGdccType(@NotNull GdType gdType) {
        if (gdType instanceof GdObjectType gdObjectType) {
            return gdObjectType.checkGdccType(context.classRegistry());
        }
        return false;
    }

    public boolean checkGdccClassByName(@NotNull String className) {
        return context.classRegistry().isGdccClass(className);
    }

    /// Renders generated per-class object pointer helper name for a GDCC object type.
    public @NotNull String renderGdccObjectPtrHelperName(@NotNull GdObjectType gdObjectType) {
        if (!gdObjectType.checkGdccType(context.classRegistry())) {
            throw new IllegalArgumentException("Type " + gdObjectType.getTypeName() + " is not a GDCC object type");
        }
        return gdObjectType.getTypeName() + "_object_ptr";
    }

    /// Render the dedicated constructor-time property-init apply helper name.
    /// This stays in `CGenHelper` because it is pure generated-symbol naming, not a codegen-phase
    /// control-flow concern.
    public @NotNull String renderPropertyInitApplyHelperName(
            @NotNull LirClassDef classDef,
            @NotNull LirPropertyDef propertyDef
    ) {
        return classDef.getName() + "_class_apply_property_init_" + propertyDef.getName();
    }

    /// Resolve the nearest constructible native ancestor for a GDCC class.
    /// This walks canonical GDCC superclass names until the first non-GDCC parent.
    public @NotNull String resolveNearestNativeAncestorName(@NotNull ClassDef classDef) {
        var registry = context.classRegistry();
        var ancestorCanonicalName = classDef.getSuperName();
        var visited = new HashSet<String>();
        while (registry.isGdccClass(ancestorCanonicalName)) {
            if (!visited.add(ancestorCanonicalName)) {
                throw new IllegalStateException("Detected GDCC inheritance cycle while resolving native ancestor for class " + classDef.getName());
            }
            var parentDef = registry.findGdccClass(ancestorCanonicalName);
            if (parentDef == null) {
                throw new IllegalStateException("Missing GDCC class definition for parent " + ancestorCanonicalName + " while resolving native ancestor for class " + classDef.getName());
            }
            ancestorCanonicalName = parentDef.getSuperName();
        }
        if (ancestorCanonicalName.isEmpty()) {
            throw new IllegalStateException("Class " + classDef.getName() + " does not have a native ancestor");
        }
        return ancestorCanonicalName;
    }

    public @NotNull CodegenContext context() {
        return context;
    }

    public @NotNull CBuiltinBuilder builtinBuilder() {
        return builtinBuilder;
    }

    /// Normalize a utility function name into the class-registry lookup key.
    /// The registry is keyed by the unprefixed utility name.
    public @NotNull String normalizeUtilityLookupName(@NotNull String functionName) {
        if (!functionName.startsWith(GODOT_UTILITY_PREFIX)) {
            return functionName;
        }
        if (functionName.length() == GODOT_UTILITY_PREFIX.length()) {
            return functionName;
        }
        return functionName.substring(GODOT_UTILITY_PREFIX.length());
    }

    /// Render the canonical C symbol name for a utility function.
    /// Accepts both prefixed and unprefixed inputs.
    public @NotNull String toUtilityCFunctionName(@NotNull String functionName) {
        if (functionName.startsWith(GODOT_UTILITY_PREFIX)) {
            return functionName;
        }
        return GODOT_UTILITY_PREFIX + functionName;
    }

    /// Resolve utility/helper call metadata from either `foo` or `godot_foo`.
    public @Nullable UtilityCallResolution resolveUtilityCall(@NotNull String functionName) {
        if (functionName.equals(VARIANT_WRITEBACK_HELPER_NAME)) {
            return new UtilityCallResolution(
                    VARIANT_WRITEBACK_HELPER_NAME,
                    VARIANT_WRITEBACK_HELPER_NAME,
                    VARIANT_WRITEBACK_HELPER_SIGNATURE
            );
        }
        var lookupName = normalizeUtilityLookupName(functionName);
        var signature = context.classRegistry().findUtilityFunctionSignature(lookupName);
        if (signature == null) {
            return null;
        }
        return new UtilityCallResolution(lookupName, toUtilityCFunctionName(lookupName), signature);
    }

    public record UtilityCallResolution(@NotNull String lookupName,
                                        @NotNull String cFunctionName,
                                        @NotNull FunctionSignature signature) {
        public UtilityCallResolution {
            Objects.requireNonNull(lookupName);
            Objects.requireNonNull(cFunctionName);
            Objects.requireNonNull(signature);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (CGenHelper) obj;
        return Objects.equals(this.context, that.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(context);
    }

    @Override
    public String toString() {
        return "CGenHelper[" +
                "context=" + context + ']';
    }

}
