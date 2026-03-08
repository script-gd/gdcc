package dev.superice.gdcc.scope;

import dev.superice.gdcc.exception.TypeParsingException;
import dev.superice.gdcc.gdextension.*;
import dev.superice.gdcc.type.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.*;

/// Global metadata registry shared by type parsing, scope lookup, and backend/frontend semantic helpers.
///
/// Today it still plays two roles at once:
/// - legacy loose lookup such as `findType(...)`, which may guess unknown object names
/// - new strict scope lookup such as restriction-aware `resolveValueHere(...)`,
///   `resolveFunctionsHere(...)`, and `resolveTypeMetaHere(...)`
///
/// As a `Scope`, `ClassRegistry` is always the global root:
/// - it never has a parent scope
/// - value/function/type namespaces remain independent
/// - restrictions do not currently filter global value/function bindings
/// - global type/meta lookup currently follows an explicit always-allowed policy and therefore never
///   returns `FOUND_BLOCKED`
/// - legacy `findXxx(...)` helpers stay available for compatibility callers
public final class ClassRegistry implements Scope {
    /// Built-in type are language built-in types, they are not engine defined types.
    private final Map<String, ExtensionBuiltinClass> builtinByName = new HashMap<>();
    /// Engine defined classes exposed to scripts via GDExtension API. Aka engine types.
    private final Map<String, ExtensionGdClass> gdClassByName = new HashMap<>();
    /// Global utility functions.
    private final Map<String, ExtensionUtilityFunction> utilityByName = new HashMap<>();
    private final Map<String, ExtensionGlobalEnum> globalEnumByName = new HashMap<>();
    private final Map<String, ExtensionSingleton> singletonByName = new HashMap<>();
    /// User defined classes in code that this compiler are compiling.
    private final Map<String, ClassDef> gdccClassByName = new HashMap<>();
    /// Virtual method for each class
    private final Map<String, Map<String, FunctionDef>> virtualMethodsByClassName = new HashMap<>();

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
    public boolean isBuiltinClass(@NotNull String name) {
        return builtinByName.containsKey(name);
    }

    /// Return a builtin class definition by name if present.
    public @Nullable ExtensionBuiltinClass findBuiltinClass(@NotNull String name) {
        return builtinByName.get(name);
    }

    /// Check whether a name refers to a GdClass (script-exposed Godot class from API).
    public boolean isGdClass(@NotNull String name) {
        return gdClassByName.containsKey(name);
    }

    /// Check whether a name refers to a global utility function.
    public boolean isUtilityFunction(@NotNull String name) {
        return utilityByName.containsKey(name);
    }

    /// Check whether a name refers to a global enum.
    public boolean isGlobalEnum(@NotNull String name) {
        return globalEnumByName.containsKey(name);
    }

    /// Check whether a name refers to a singleton.
    public boolean isSingleton(@NotNull String name) {
        return singletonByName.containsKey(name);
    }

    /// Check whether a name refers to a user-defined gdcc class.
    public boolean isGdccClass(@NotNull String name) {
        return gdccClassByName.containsKey(name);
    }

    public boolean isContainerClass(@NotNull String name) {
        return name.equals("Array") || name.equals("Dictionary") || name.startsWith("Array[") || name.startsWith("Dictionary[");
    }

    /// Add or replace a user-defined class.
    public void addGdccClass(@NotNull ClassDef classDef) {
        gdccClassByName.put(classDef.getName(), classDef);
    }

    /// Remove a user-defined class by name.
    public @Nullable ClassDef removeGdccClass(@NotNull String name) {
        return gdccClassByName.remove(name);
    }

    /// Get a user-defined class by name.
    public @Nullable ClassDef findGdccClass(@NotNull String name) {
        return gdccClassByName.get(name);
    }

    @Override
    public @Nullable Scope getParentScope() {
        return null;
    }

    @Override
    public void setParentScope(@Nullable Scope parentScope) {
        if (parentScope != null) {
            throw new IllegalArgumentException("ClassRegistry is the global root scope and cannot have a parent scope");
        }
    }

    @Override
    public @NotNull ScopeLookupResult<ScopeValue> resolveValueHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(restriction, "restriction");

        var singleton = singletonByName.get(name);
        if (singleton != null) {
            var singletonType = findSingletonType(name);
            if (singletonType != null) {
                return ScopeLookupResult.foundAllowed(
                        new ScopeValue(name, singletonType, ScopeValueKind.SINGLETON, singleton, true, false)
                );
            }
        }

        var globalEnum = globalEnumByName.get(name);
        if (globalEnum != null) {
            return ScopeLookupResult.foundAllowed(
                    new ScopeValue(name, GdIntType.INT, ScopeValueKind.GLOBAL_ENUM, globalEnum, true, false)
            );
        }

        return ScopeLookupResult.notFound();
    }

    @Override
    public @NotNull ScopeLookupResult<List<FunctionDef>> resolveFunctionsHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(restriction, "restriction");

        var utilityFunction = utilityByName.get(name);
        return utilityFunction != null
                ? ScopeLookupResult.foundAllowed(List.<FunctionDef>of(utilityFunction))
                : ScopeLookupResult.notFound();
    }

    /// Strict type-meta lookup for the global namespace.
    ///
    /// Resolution order is intentionally narrow and deterministic:
    /// 1. global enum names
    /// 2. GDCC user classes already registered in this compilation
    /// 3. engine classes exposed by the extension API
    /// 4. strict builtin and container text types such as `String`, `Array[int]`, or `Dictionary[String, Node]`
    ///
    /// Unlike `findType(...)`, this method never guesses that an unknown identifier is an object type.
    /// If the name is not already known to the registry and cannot be parsed as a strict builtin/container
    /// type expression, the method returns `ScopeLookupResult.notFound()`.
    ///
    /// The supplied `ResolveRestriction` is accepted only for protocol uniformity. Under the current
    /// Phase 4 contract, global type/meta bindings are always restriction-allowed, so this method may
    /// return only `FOUND_ALLOWED` or `NOT_FOUND`.
    @Override
    public @NotNull ScopeLookupResult<ScopeTypeMeta> resolveTypeMetaHere(
            @NotNull String name,
            @NotNull ResolveRestriction restriction
    ) {
        Objects.requireNonNull(restriction, "restriction");
        var trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return ScopeLookupResult.notFound();
        }

        if (isGlobalEnum(trimmed)) {
            return ScopeLookupResult.foundAllowed(new ScopeTypeMeta(
                    trimmed,
                    GdIntType.INT,
                    ScopeTypeMetaKind.GLOBAL_ENUM,
                    findGlobalEnum(trimmed),
                    true
            ));
        }
        if (isGdccClass(trimmed)) {
            return ScopeLookupResult.foundAllowed(new ScopeTypeMeta(
                    trimmed,
                    new GdObjectType(trimmed),
                    ScopeTypeMetaKind.GDCC_CLASS,
                    findGdccClass(trimmed),
                    false
            ));
        }
        if (isGdClass(trimmed)) {
            return ScopeLookupResult.foundAllowed(new ScopeTypeMeta(
                    trimmed,
                    new GdObjectType(trimmed),
                    ScopeTypeMetaKind.ENGINE_CLASS,
                    getClassDef(new GdObjectType(trimmed)),
                    false
            ));
        }

        var builtinType = tryParseStrictTextType(trimmed, this);
        if (builtinType != null) {
            return ScopeLookupResult.foundAllowed(new ScopeTypeMeta(
                    trimmed,
                    builtinType,
                    ScopeTypeMetaKind.BUILTIN,
                    findBuiltinClass(trimmed),
                    false
            ));
        }
        return ScopeLookupResult.notFound();
    }

    /// Recursive type-meta lookup entry for the current global scope root.
    ///
    /// `ClassRegistry` is not yet wired into the full `Scope` hierarchy, so the current implementation
    /// is equivalent to `resolveTypeMetaHere(...)`. The dedicated method still exists to freeze the
    /// future protocol shape and give callers a strict API that does not depend on `findType(...)`.
    @Override
    public @Nullable ScopeTypeMeta resolveTypeMeta(@NotNull String name) {
        return resolveTypeMetaHere(name);
    }

    /// Return a GdType instance for the given type name if known.
    /// Rules:
    /// - Prefer textual parsing which maps builtin and container types to concrete GdType instances.
    /// - If textual parsing yields a concrete non-GdObjectType -> return it (these are builtins, primitives, containers).
    /// - If the name refers to a gd class (API `classes`) -> return engine GdObjectType(name, true).
    /// - If the name refers to builtin class from API (API.builtin_classes) -> treat as builtin type (non-engine);
    /// - If the name is a container class -> treat as builtin container type and resolve its inner types accordingly.
    /// - If the name refers to a user defined class in `gdccClassByName` -> treat as user type (non-engine).
    ///   we prefer tryParseTextType result for precise mapping; if tryParseTextType didn't recognize it, return a plain non-engine GdObjectType.
    /// - Do NOT return a type for global enums/utility functions in this method (return null instead).
    /// - If none of the above matched, return a plain non-engine GdObjectType(name) (represents a user type reference).
    public @Nullable GdType findType(@NotNull String name) {
        // textual parsing first, including built-in type and container built-in type
        var parsed = tryParseStrictTextType(name, this);
        if (parsed != null && !(parsed instanceof GdObjectType)) {
            return parsed;
        }

        // If name corresponds to a gd class (engine type)
        if (isGdClass(name)) return new GdObjectType(name);

        // If name corresponds to a user-defined gdcc class, return a non-engine object type
        if (isGdccClass(name)) return new GdObjectType(name);

        // If name is a global enum or utility function or singleton, we should not return a type here
        if (isGlobalEnum(name) || isUtilityFunction(name)) return null;

        // If name is a builtin class defined in API but tryParseTextType couldn't map, then it is an error
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

    /// Compatibility-oriented textual type parsing.
    ///
    /// This is the older, looser parser used by existing code paths. It first attempts strict parsing,
    /// but if the name is still unknown it falls back to `new GdObjectType(name)`. New binder-style code
    /// should prefer `resolveTypeMeta(...)` or `tryParseStrictTextType(...)` when it needs a definitive answer.
    public static @Nullable GdType tryParseTextType(@NotNull String typeName) {
        var strict = tryParseStrictTextType(typeName, null);
        if (strict != null) {
            return strict;
        }
        var t = typeName.trim();
        if (t.isEmpty()) return null;
        return new GdObjectType(t);
    }

    /// Strict textual parsing used by type-meta resolution.
    ///
    /// This helper only accepts builtins and builtin-style container expressions that the compiler can
    /// interpret without guessing. If a `registry` is provided, nested container arguments may recurse into
    /// `resolveTypeMetaHere(...)` so constructs like `Array[MyScriptClass]` can be resolved strictly.
    ///
    /// Returns `null` for unknown identifiers instead of inventing `GdObjectType(name)`.
    public static @Nullable GdType tryParseStrictTextType(@NotNull String typeName,
                                                          @Nullable ClassRegistry registry) {
        var t = typeName.trim();
        if (t.isEmpty()) {
            return null;
        }
        return switch (t) {
            case "AABB" -> GdAABBType.AABB;
            case "Array" -> new GdArrayType(GdVariantType.VARIANT);
            case "Basis" -> GdBasisType.BASIS;
            case "bool" -> GdBoolType.BOOL;
            case "Callable" -> new GdCallableType();
            case "Color" -> GdColorType.COLOR;
            case "Dictionary" -> new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);
            case "float" -> GdFloatType.FLOAT;
            case "Vector2" -> GdFloatVectorType.VECTOR2;
            case "Vector2i" -> GdIntVectorType.VECTOR2I;
            case "Vector3" -> GdFloatVectorType.VECTOR3;
            case "Vector3i" -> GdIntVectorType.VECTOR3I;
            case "Vector4" -> GdFloatVectorType.VECTOR4;
            case "Vector4i" -> GdIntVectorType.VECTOR4I;
            case "int" -> GdIntType.INT;
            case "Nil", "null" -> GdNilType.NIL;
            case "NodePath" -> GdNodePathType.NODE_PATH;
            case "Object" -> GdObjectType.OBJECT;
            case "PackedByteArray" -> GdPackedNumericArrayType.PACKED_BYTE_ARRAY;
            case "PackedInt32Array" -> GdPackedNumericArrayType.PACKED_INT32_ARRAY;
            case "PackedInt64Array" -> GdPackedNumericArrayType.PACKED_INT64_ARRAY;
            case "PackedFloat32Array" -> GdPackedNumericArrayType.PACKED_FLOAT32_ARRAY;
            case "PackedFloat64Array" -> GdPackedNumericArrayType.PACKED_FLOAT64_ARRAY;
            case "PackedStringArray" -> GdPackedStringArrayType.PACKED_STRING_ARRAY;
            case "PackedVector2Array" -> GdPackedVectorArrayType.PACKED_VECTOR2_ARRAY;
            case "PackedVector3Array" -> GdPackedVectorArrayType.PACKED_VECTOR3_ARRAY;
            case "PackedVector4Array" -> GdPackedVectorArrayType.PACKED_VECTOR4_ARRAY;
            case "PackedColorArray" -> GdPackedVectorArrayType.PACKED_COLOR_ARRAY;
            case "Plane" -> GdPlaneType.PLANE;
            case "Projection" -> GdProjectionType.PROJECTION;
            case "Quaternion" -> GdQuaternionType.QUATERNION;
            case "Rect2" -> GdRect2Type.RECT2;
            case "Rect2i" -> GdRect2iType.RECT2I;
            case "RID" -> GdRidType.RID;
            case "Signal" -> new GdSignalType();
            case "String" -> GdStringType.STRING;
            case "StringName" -> GdStringNameType.STRING_NAME;
            case "Transform2D" -> GdTransform2DType.TRANSFORM2D;
            case "Transform3D" -> GdTransform3DType.TRANSFORM3D;
            case "void", "Void" -> GdVoidType.VOID;
            case "Variant" -> GdVariantType.VARIANT;
            default -> tryParseStrictContainerType(t, registry);
        };
    }

    /// Parses builtin container type expressions in strict mode.
    ///
    /// Supported forms are currently limited to:
    /// - `Array[T]`
    /// - `Dictionary[K, V]`
    ///
    /// Nested element/key/value types are resolved strictly as well, so any unknown inner type causes the
    /// whole container parse to fail with `null` instead of degrading into a guessed object type.
    private static @Nullable GdType tryParseStrictContainerType(@NotNull String textType,
                                                                @Nullable ClassRegistry registry) {
        if (textType.startsWith("Array[") && textType.endsWith("]")) {
            var inner = textType.substring(6, textType.length() - 1).trim();
            if (inner.isEmpty()) {
                return null;
            }
            var innerType = resolveStrictNestedType(inner, registry);
            return innerType != null ? new GdArrayType(innerType) : null;
        }
        if (textType.startsWith("Dictionary[") && textType.endsWith("]")) {
            var inner = textType.substring(11, textType.length() - 1).trim();
            var parts = splitDictionaryTypeArgs(inner);
            if (parts == null) {
                return null;
            }
            var keyType = resolveStrictNestedType(parts.getFirst(), registry);
            var valueType = resolveStrictNestedType(parts.getLast(), registry);
            if (keyType == null || valueType == null) {
                return null;
            }
            return new GdDictionaryType(keyType, valueType);
        }
        return null;
    }

    /// Resolves a nested type argument used inside strict container parsing.
    ///
    /// The helper first tries strict builtin parsing. If that fails and a registry is available, it then
    /// consults the strict type-meta namespace so user classes and global enums can participate inside
    /// container type expressions.
    private static @Nullable GdType resolveStrictNestedType(@NotNull String typeText,
                                                            @Nullable ClassRegistry registry) {
        var strictType = tryParseStrictTextType(typeText, registry);
        if (strictType != null) {
            return strictType;
        }
        if (registry == null) {
            return null;
        }
        var typeMeta = registry.resolveTypeMetaHere(typeText);
        return typeMeta != null ? typeMeta.instanceType() : null;
    }

    /// Splits the two type arguments of a strict `Dictionary[K, V]` expression.
    ///
    /// The current implementation is intentionally minimal and only looks for the first comma separator.
    /// This is sufficient for the current Phase 2 scope but not for deeply nested dictionary generics.
    private static @Nullable List<String> splitDictionaryTypeArgs(@NotNull String argsText) {
        var commaIndex = argsText.indexOf(',');
        if (commaIndex < 0) {
            return null;
        }
        var keyText = argsText.substring(0, commaIndex).trim();
        var valueText = argsText.substring(commaIndex + 1).trim();
        if (keyText.isEmpty() || valueText.isEmpty()) {
            return null;
        }
        return List.of(keyText, valueText);
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
        return new GdObjectType(t);
    }

    public @NotNull Map<String, FunctionDef> getVirtualMethods(@NotNull String className) {
        if (!virtualMethodsByClassName.containsKey(className)) {
            var vMethods = new HashMap<String, FunctionDef>();
            virtualMethodsByClassName.put(className, vMethods);
            ClassDef gdClass = gdClassByName.get(className);
            if (gdClass == null) {
                gdClass = gdccClassByName.get(className);
            }
            if (gdClass != null) {
                for (var method : gdClass.getFunctions()) {
                    if (method.isAbstract()) {
                        vMethods.put(method.getName(), method);
                    }
                }
                var superClassName = gdClass.getSuperName();
                if (!superClassName.isEmpty()) {
                    var superVMethods = getVirtualMethods(superClassName);
                    for (var svm : superVMethods.values()) {
                        if (!vMethods.containsKey(svm.getName())) {
                            vMethods.put(svm.getName(), svm);
                        }
                    }
                }
            }
        }
        return virtualMethodsByClassName.get(className);
    }

    public @Nullable ClassDef getClassDef(@NotNull GdObjectType type) {
        ClassDef classDef = gdClassByName.get(type.getTypeName());
        if (classDef != null) {
            return classDef;
        }
        classDef = gdccClassByName.get(type.getTypeName());
        return classDef;
    }

    /// Global assignability rules used across backend validation/codegen:
    /// - same type
    /// - object inheritance upcast
    /// - limited container covariance for Array/Dictionary
    public boolean checkAssignable(@NotNull GdType from, @NotNull GdType to) {
        if (from.getTypeName().equals(to.getTypeName())) {
            return true;
        }
        if (from instanceof GdArrayType fromArray && to instanceof GdArrayType toArray) {
            return checkContainerCovariantAssignable(fromArray.getValueType(), toArray.getValueType());
        }
        if (from instanceof GdDictionaryType fromDictionary && to instanceof GdDictionaryType toDictionary) {
            return checkContainerCovariantAssignable(fromDictionary.getKeyType(), toDictionary.getKeyType()) &&
                    checkContainerCovariantAssignable(fromDictionary.getValueType(), toDictionary.getValueType());
        }
        return checkObjectAssignable(from, to);
    }

    /// Container covariance:
    /// - target `Variant` accepts any source element/key/value type.
    /// - otherwise delegate to the general assignability rules recursively.
    private boolean checkContainerCovariantAssignable(@NotNull GdType fromType, @NotNull GdType toType) {
        if (toType instanceof GdVariantType) {
            return true;
        }
        return checkAssignable(fromType, toType);
    }

    private boolean checkObjectAssignable(@NotNull GdType from, @NotNull GdType to) {
        if (!(from instanceof GdObjectType(var fromClassName)) || !(to instanceof GdObjectType(var toClassName))) {
            return false;
        }
        // Check inheritance chain
        while (!fromClassName.isEmpty()) {
            if (fromClassName.equals(toClassName)) {
                return true;
            }
            var classDef = getClassDef(new GdObjectType(fromClassName));
            if (classDef == null) {
                break;
            }
            fromClassName = classDef.getSuperName();
        }
        return false;
    }

    /// Determine whether an object type is reference-counted.
    /// Returns YES if the type definitely inherits from RefCounted.
    /// Returns NO if the type definitely does NOT inherit from RefCounted (e.g., Resource).
    /// Returns UNKNOWN if we cannot determine (e.g., unknown engine type or missing class info).
    public @NotNull RefCountedStatus getRefCountedStatus(@NotNull GdObjectType objectType) {
        var className = objectType.getTypeName();
        // Check engine classes first
        var engineClass = gdClassByName.get(className);
        if (engineClass != null) {
            // Engine classes have isRefcounted flag from extension API
            return engineClass.isRefcounted() ? RefCountedStatus.YES : RefCountedStatus.NO;
        }
        // Check GDCC user classes
        var gdccClass = gdccClassByName.get(className);
        if (gdccClass != null) {
            // Traverse inheritance chain to check for RefCounted
            var current = gdccClass;
            while (current != null && !current.getSuperName().isEmpty()) {
                if (current.getSuperName().equals("RefCounted") || current.getSuperName().equals("Resource")) {
                    return RefCountedStatus.YES;
                }
                var superName = current.getSuperName();
                current = gdccClassByName.get(superName);
                if (superName.equals("Object") || superName.equals("Node")) {
                    // Reached Object without finding RefCounted, it's NO
                    return RefCountedStatus.NO;
                }
            }
            // If we reached Object without finding RefCounted, it's NO
            return RefCountedStatus.NO;
        }
        // Unknown type
        return RefCountedStatus.UNKNOWN;
    }

    public @NotNull @UnmodifiableView List<ExtensionGdClass> getExtensionGdClassList() {
        return gdClassByName.values().stream().toList();
    }
}
