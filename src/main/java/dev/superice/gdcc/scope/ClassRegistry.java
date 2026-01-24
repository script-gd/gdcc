package dev.superice.gdcc.scope;

import dev.superice.gdcc.exception.TypeParsingException;
import dev.superice.gdcc.gdextension.*;
import dev.superice.gdcc.type.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ClassRegistry {
    private final Map<String, ExtensionBuiltinClass> builtinByName = new HashMap<>();
    private final Map<String, ExtensionGdClass> gdClassByName = new HashMap<>();
    private final Map<String, ExtensionUtilityFunction> utilityByName = new HashMap<>();
    private final Map<String, ExtensionGlobalEnum> globalEnumByName = new HashMap<>();
    private final Map<String, ExtensionSingleton> singletonByName = new HashMap<>();

    public ClassRegistry(@NotNull ExtensionAPI api) {
        for (var bc : api.builtinClasses()) {
            if (bc != null && bc.name() != null) builtinByName.put(bc.name(), bc);
        }
        for (var gc : api.classes()) {
            if (gc != null && gc.name() != null) gdClassByName.put(gc.name(), gc);
        }
        for (var uf : api.utilityFunctions()) {
            if (uf != null && uf.name() != null) utilityByName.put(uf.name(), uf);
        }
        for (var ge : api.globalEnums()) {
            if (ge != null && ge.name() != null) globalEnumByName.put(ge.name(), ge);
        }
        for (var s : api.singletons()) {
            if (s != null && s.name() != null) singletonByName.put(s.name(), s);
        }
    }

    /// Check whether a name refers to a builtin class from API.
    public boolean isBuiltinClass(@NotNull String name) { return builtinByName.containsKey(name); }

    /// Check whether a name refers to a GdClass (script-exposed Godot class from API).
    public boolean isGdClass(@NotNull String name) { return gdClassByName.containsKey(name); }

    /// Check whether a name refers to a global utility function.
    public boolean isUtilityFunction(@NotNull String name) { return utilityByName.containsKey(name); }

    /// Check whether a name refers to a global enum.
    public boolean isGlobalEnum(@NotNull String name) { return globalEnumByName.containsKey(name); }

    /// Check whether a name refers to a singleton.
    public boolean isSingleton(@NotNull String name) { return singletonByName.containsKey(name); }

    /// Return a GdType instance for the given type name if known.
    /// Rules:
    /// - Prefer textual parsing (copied from TypeParser) which maps builtin and container types to concrete GdType instances.
    /// - If textual parsing yields a concrete non-GdObjectType -> return it (these are builtins, primitives, containers).
    /// - If the name refers to a gd class (API `classes`) -> return engine GdObjectType(name, true).
    /// - If the name refers to builtin class from API (API.builtin_classes) -> treat as builtin type (non-engine);
    ///   we prefer TypeParser result for precise mapping; if TypeParser didn't recognize it, return a plain non-engine GdObjectType.
    /// - Do NOT return a type for global enums/utility functions in this method (return null instead).
    /// - If none of the above matched, return a plain non-engine GdObjectType(name) (represents a user type reference).
    public @Nullable GdType findType(@NotNull String name) {
        // textual parsing first
        var parsed = tryParseTextType(name);
        if (parsed != null && !(parsed instanceof GdObjectType)) return parsed;

        // If name corresponds to a gd class (engine type)
        if (isGdClass(name)) return new GdObjectType(name, true);

        // If name is a global enum or utility function or singleton, we should not return a type here
        if (isGlobalEnum(name) || isUtilityFunction(name)) return null;

        // If name is a builtin class defined in API but TypeParser couldn't map, then it is an error
        if (isBuiltinClass(name)) {
            throw new TypeParsingException("Builtin class type '" + name + "' could not be resolved by parser");
        }

        // Unknown: return plain non-engine object type
        return new GdObjectType(name);
    }

    private @Nullable GdType resolveTypeName(@Nullable String typeName) {
        if (typeName == null) return null;
        return findType(typeName);
    }

    /// Copy of TypeParser.parse textual mapping (exact, case-sensitive). Returns a concrete GdType or GdObjectType as fallback.
    private @Nullable GdType tryParseTextType(@NotNull String typeName) {
        var t = typeName.trim();
        if (t.isEmpty()) return null;
        switch (t) {
            case "AABB": return GdAABBType.AABB;
            case "Array": return new GdArrayType(GdVariantType.VARIANT);
            case "Basis": return GdBasisType.BASIS;
            case "bool": return GdBoolType.BOOL;
            case "Callable": return new GdCallableType();
            case "Color": return GdColorType.COLOR;
            case "Dictionary": return new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);
            case "float": return GdFloatType.FLOAT;
            case "Vector2": return GdFloatVectorType.VECTOR2;
            case "Vector2i": return GdIntVectorType.VECTOR2I;
            case "Vector3": return GdFloatVectorType.VECTOR3;
            case "Vector3i": return GdIntVectorType.VECTOR3I;
            case "Vector4": return GdFloatVectorType.VECTOR4;
            case "Vector4i": return GdIntVectorType.VECTOR4I;
            case "int": return GdIntType.INT;
            case "Nil": case "null": return GdNilType.NIL;
            case "NodePath": return GdNodePathType.NODE_PATH;
            case "Object": return GdObjectType.OBJECT;
            case "PackedByteArray": return GdPackedNumericArrayType.PACKED_BYTE_ARRAY;
            case "PackedInt32Array": return GdPackedNumericArrayType.PACKED_INT32_ARRAY;
            case "PackedInt64Array": return GdPackedNumericArrayType.PACKED_INT64_ARRAY;
            case "PackedFloat32Array": return GdPackedNumericArrayType.PACKED_FLOAT32_ARRAY;
            case "PackedFloat64Array": return GdPackedNumericArrayType.PACKED_FLOAT64_ARRAY;
            case "PackedStringArray": return GdPackedStringArrayType.PACKED_STRING_ARRAY;
            case "PackedVector2Array": return GdPackedVectorArrayType.PACKED_VECTOR2_ARRAY;
            case "PackedVector3Array": return GdPackedVectorArrayType.PACKED_VECTOR3_ARRAY;
            case "PackedVector4Array": return GdPackedVectorArrayType.PACKED_VECTOR4_ARRAY;
            case "PackedColorArray": return GdPackedVectorArrayType.PACKED_COLOR_ARRAY;
            case "Plane": return GdPlaneType.PLANE;
            case "Projection": return GdProjectionType.PROJECTION;
            case "Quaternion": return GdQuaternionType.QUATERNION;
            case "Rect2": return GdRect2Type.RECT2;
            case "Rect2i": return GdRect2iType.RECT2I;
            case "RID": return GdRidType.RID;
            case "Signal": return new GdSignalType();
            case "String": return GdStringType.STRING;
            case "StringName": return GdStringNameType.STRING_NAME;
            case "Transform2D": return GdTransform2DType.TRANSFORM2D;
            case "Transform3D": return GdTransform3DType.TRANSFORM3D;
            case "void": case "Void": return GdVoidType.VOID;
            case "Variant": return GdVariantType.VARIANT;
        }

        // Generic forms: Array[T], Dictionary[K, V]
        if (t.startsWith("Array[") && t.endsWith("]")) {
            var inner = t.substring(6, t.length() - 1).trim();
            if (inner.isEmpty()) return null;
            var innerType = tryParseTextType(inner);
            if (innerType == null) innerType = new GdObjectType(inner);
            return new GdArrayType(innerType);
        }
        if (t.startsWith("Dictionary[") && t.endsWith("]")) {
            var inner = t.substring(11, t.length() - 1);
            var parts = inner.split(",");
            if (parts.length == 2) {
                var kt = tryParseTextType(parts[0].trim());
                var vt = tryParseTextType(parts[1].trim());
                if (kt == null) kt = GdVariantType.VARIANT;
                if (vt == null) vt = GdVariantType.VARIANT;
                return new GdDictionaryType(kt, vt);
            } else {
                return null;
            }
        }

        // fallback: treat as object/class name
        return new GdObjectType(t);
    }

    /// Return the function signature for a global utility function by name.
    public @Nullable FunctionSignature findUtilityFunctionSignature(@NotNull String name) {
        var uf = utilityByName.get(name);
        if (uf == null) return null;
        var params = new ArrayList<FunctionSignature.Parameter>();
        if (uf.arguments() != null) {
            for (var a : uf.arguments()) {
                var ptype = resolveTypeName(a.type());
                params.add(new FunctionSignature.Parameter(a.name(), ptype, a.defaultValue()));
            }
        }
        var rtype = resolveTypeName(uf.returnType());
        return new FunctionSignature(uf.name(), params, uf.isVararg(), rtype);
    }

    /// Return the ExtensionGlobalEnum object for a global enum name.
    public @Nullable ExtensionGlobalEnum findGlobalEnum(@NotNull String name) {
        return globalEnumByName.get(name);
    }

    /// Return the singleton's object type for a singleton name.
    public @Nullable GdObjectType findSingletonType(@NotNull String name) {
        var s = singletonByName.get(name);
        if (s == null) return null;
        var t = s.type();
        if (t == null) return null;
        return new GdObjectType(t, true);
    }

    /// Return the raw lists for inspection / tests.
    public @NotNull List<ExtensionBuiltinClass> builtinClasses() { return List.copyOf(builtinByName.values()); }

    public @NotNull List<ExtensionGdClass> gdClasses() { return List.copyOf(gdClassByName.values()); }

    public @NotNull List<ExtensionUtilityFunction> utilityFunctions() { return List.copyOf(utilityByName.values()); }

    public @NotNull List<ExtensionGlobalEnum> globalEnums() { return List.copyOf(globalEnumByName.values()); }

    public @NotNull List<ExtensionSingleton> singletons() { return List.copyOf(singletonByName.values()); }
}
