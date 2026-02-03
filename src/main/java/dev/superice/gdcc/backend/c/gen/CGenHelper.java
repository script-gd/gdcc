package dev.superice.gdcc.backend.c.gen;

import dev.superice.gdcc.backend.CodegenContext;
import dev.superice.gdcc.exception.NotImplementedException;
import dev.superice.gdcc.lir.LirFunctionDef;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ParameterDef;
import dev.superice.gdcc.scope.PropertyDef;
import dev.superice.gdcc.type.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class CGenHelper {
    private final @NotNull CodegenContext context;
    private final @NotNull Set<BindingData> bindingDataSet = new HashSet<>();

    public CGenHelper(@NotNull CodegenContext context, @NotNull List<? extends ClassDef> classDefs) {
        this.context = context;
        this.collectBindingData(classDefs);
    }

    public record BindingData(
            @NotNull List<GdType> paramTypes,
            @NotNull GdType returnType,
            @NotNull List<GdType> defaultVariables,
            boolean staticMethod
    ) {
    }

    public @NotNull List<BindingData> getBindingDataList() {
        return List.copyOf(bindingDataSet);
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
                if (!functionDef.isStatic()) {
                    paramTypes.add(new GdObjectType(classDef.getName()));
                }
                for (var parameterDef : functionDef.getParameters()) {
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
                if (!functionDef.isStatic()) {
                    throw new IllegalStateException("Virtual methods must be instance methods");
                }
                for (var parameterDef : functionDef.getParameters()) {
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
        return renderFuncBindName(bindingData.returnType, bindingData.paramTypes, bindingData.defaultVariables, bindingData.staticMethod);
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
                return "(godot_" + objectType.getTypeName() +"*)godot_new_Object_with_Variant";
            }
        } else {
            return "godot_new_" + type.getTypeName() + "_with_Variant";
        }
    }

    public @NotNull String renderPackFunctionName(@NotNull GdType type) {
        if (type instanceof GdObjectType objectType) {
            if (objectType.checkGdccType(context.classRegistry())) {
                return "godot_new_Variant_with_gdcc_Object";
            } else {
                return "godot_new_Variant_with_Object";
            }
        } else {
            return "godot_new_Variant_with_" + type.getTypeName();
        }
    }

    public @NotNull String renderCopyAssignFunctionName(@NotNull GdType type) {
        return switch (type) {
            case GdObjectType _, GdPrimitiveType _ -> "";
            case GdVoidType _, GdNilType _ -> throw new IllegalArgumentException("Type " + type.getTypeName() + " does not support copy assignment");
            default -> "godot_new_" + type.getTypeName() + "_with_" + type.getTypeName();
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

    public @NotNull String renderPropertyUsageEnum(@NotNull PropertyDef propertyDef) {
        boolean export = false;
        for (var entry : propertyDef.getAnnotations().entrySet()) {
            if (entry.getKey().equals("export")) {
                export = true;
                break;
            }
        }
        if (export) {
            return "godot_PROPERTY_USAGE_DEFAULT";
        }
        return "godot_PROPERTY_USAGE_NO_EDITOR";
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

    public @NotNull CodegenContext context() {
        return context;
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
