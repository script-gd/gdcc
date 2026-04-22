package dev.superice.gdcc.scope;

import dev.superice.gdcc.exception.TypeParsingException;
import dev.superice.gdcc.gdextension.*;
import dev.superice.gdcc.scope.resolver.ScopeTypeParsers;
import dev.superice.gdcc.scope.resolver.ScopeTypeResolver;
import dev.superice.gdcc.scope.resolver.ScopeTypeTextSupport;
import dev.superice.gdcc.scope.resolver.UnresolvedTypeMapper;
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
    /// User-defined classes keyed strictly by canonical registration name.
    private final Map<String, ClassDef> gdccClassByName = new HashMap<>();
    /// Source-facing alias side table for canonical gdcc registrations whose source name differs.
    ///
    /// This is not a second global lookup namespace. It only preserves source-facing names for
    /// registry callers that already resolved a gdcc class by canonical identity.
    private final Map<String, String> gdccClassSourceNameByCanonicalName = new HashMap<>();
    /// Shared virtual metadata surface keyed by the queried class name and then virtual name.
    /// The visible map keeps local gdcc abstract methods and inherited engine virtuals in one place,
    /// while the engine-only map ignores gdcc-only shadow declarations so frontend/backend can still
    /// reach the underlying engine contract for strict override checks.
    private final Map<String, Map<String, VirtualMethodInfo>> virtualMethodsByClassName = new HashMap<>();
    private final Map<String, Map<String, VirtualMethodInfo>> engineVirtualMethodsByClassName = new HashMap<>();

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

    /// Add or replace a user-defined class using its canonical name as the only registry key.
    public void addGdccClass(@NotNull ClassDef classDef) {
        addGdccClass(classDef, null);
    }

    /// Add or replace a user-defined class and optionally remember a distinct source-facing name.
    ///
    /// @param sourceNameOverride Passing `null` or the same text as the canonical class name keeps the side table empty for that class.
    public void addGdccClass(@NotNull ClassDef classDef, @Nullable String sourceNameOverride) {
        Objects.requireNonNull(classDef, "classDef");
        var canonicalName = classDef.getName();
        gdccClassByName.put(canonicalName, classDef);
        gdccClassSourceNameByCanonicalName.remove(canonicalName);
        if (sourceNameOverride != null && !sourceNameOverride.equals(canonicalName)) {
            gdccClassSourceNameByCanonicalName.put(canonicalName, sourceNameOverride);
        }
        virtualMethodsByClassName.clear();
        engineVirtualMethodsByClassName.clear();
    }

    /// Remove a user-defined class by canonical name.
    public @Nullable ClassDef removeGdccClass(@NotNull String name) {
        gdccClassSourceNameByCanonicalName.remove(name);
        virtualMethodsByClassName.clear();
        engineVirtualMethodsByClassName.clear();
        return gdccClassByName.remove(name);
    }

    /// Get a user-defined class by canonical name.
    public @Nullable ClassDef findGdccClass(@NotNull String name) {
        return gdccClassByName.get(name);
    }

    /// Returns the explicit source-name override recorded for a gdcc class.
    ///
    /// Missing entries intentionally mean `sourceName == canonicalName`; callers should not treat
    /// this map as a second global lookup namespace.
    public @Nullable String findGdccClassSourceNameOverride(@NotNull String canonicalName) {
        return gdccClassSourceNameByCanonicalName.get(canonicalName);
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
                        new ScopeValue(name, singletonType, ScopeValueKind.SINGLETON, singleton, true, false, false)
                );
            }
        }

        var globalEnum = globalEnumByName.get(name);
        if (globalEnum != null) {
            return ScopeLookupResult.foundAllowed(
                    new ScopeValue(name, GdIntType.INT, ScopeValueKind.GLOBAL_ENUM, globalEnum, true, false, false)
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
                ? ScopeLookupResult.foundAllowed(List.of(utilityFunction))
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
    /// This method is the global-scope primitive consumed by `Scope#resolveTypeMeta(...)`.
    /// It must stay as a local lookup and must not delegate back into `ScopeTypeResolver`.
    ///
    /// The supplied `ResolveRestriction` is accepted only for protocol uniformity. Under the current
    /// type-meta contract, global bindings are always restriction-allowed, so this method may return
    /// only `FOUND_ALLOWED` or `NOT_FOUND`.
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
                    trimmed,
                    GdIntType.INT,
                    ScopeTypeMetaKind.GLOBAL_ENUM,
                    findGlobalEnum(trimmed),
                    true
            ));
        }
        if (isGdccClass(trimmed)) {
            var sourceName = resolveGdccSourceName(trimmed);
            return ScopeLookupResult.foundAllowed(new ScopeTypeMeta(
                    trimmed,
                    sourceName,
                    new GdObjectType(trimmed),
                    ScopeTypeMetaKind.GDCC_CLASS,
                    findGdccClass(trimmed),
                    false
            ));
        }
        if (isGdClass(trimmed)) {
            return ScopeLookupResult.foundAllowed(new ScopeTypeMeta(
                    trimmed,
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
    ///
    /// The implementation intentionally uses the shared strict declared-type resolver as its first pass,
    /// then reopens only the legacy compatibility fallback through an unresolved-leaf mapper. This keeps
    /// builtin/engine/gdcc/container rules aligned with `tryResolveDeclaredType(...)` without changing the
    /// historical guessed-object behavior of `findType(...)`.
    public @Nullable GdType findType(@NotNull String name) {
        var trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        // These names live in other namespaces and must not degrade into fake object types.
        if (isGlobalEnum(trimmed) || isUtilityFunction(trimmed) || isSingleton(trimmed)) {
            return null;
        }
        var strictType = ScopeTypeResolver.tryResolveDeclaredType(this, trimmed);
        if (strictType != null) {
            return strictType;
        }

        // If the extension API reports a builtin class but the compiler still cannot map it to a `GdType`,
        // that is a missing compiler mapping rather than an unsupported GDScript builtin.
        if (isBuiltinClass(trimmed)) {
            throw new TypeParsingException(
                    "Name '" + trimmed + "' is recognized as a Godot builtin class, but GDCC has no builtin-type mapping for it yet"
            );
        }
        return ScopeTypeResolver.tryResolveDeclaredType(this, trimmed, this::mapCompatibleUnresolvedType);
    }

    private @NotNull String resolveGdccSourceName(@NotNull String canonicalName) {
        return gdccClassSourceNameByCanonicalName.getOrDefault(canonicalName, canonicalName);
    }

    /// Resolve an explicitly written type name without inventing unknown object types.
    ///
    /// This is the registry-aware counterpart to `tryParseTextType(...)`:
    /// - builtins and builtin containers are parsed strictly
    /// - known engine / GDCC classes and global enums may participate through `resolveTypeMetaHere(...)`
    /// - unknown identifiers return `null` instead of degrading into `new GdObjectType(name)`
    public @Nullable GdType tryResolveDeclaredType(@NotNull String typeName) {
        Objects.requireNonNull(typeName, "typeName");
        return tryResolveDeclaredType(typeName, null);
    }

    /// Strict declared-type resolution with an optional unresolved-leaf mapper for compatibility callers.
    ///
    /// Frontend declared type positions intentionally continue to use the no-mapper overload so unknown
    /// names still surface diagnostics instead of being guessed as objects.
    public @Nullable GdType tryResolveDeclaredType(
            @NotNull String typeName,
            @Nullable UnresolvedTypeMapper unresolvedTypeMapper
    ) {
        Objects.requireNonNull(typeName, "typeName");
        return ScopeTypeResolver.tryResolveDeclaredType(this, typeName, unresolvedTypeMapper);
    }

    /// Compatibility-oriented textual type parsing.
    ///
    /// This is the older, looser parser used by existing code paths. It first attempts strict parsing,
    /// but if the name is still unknown it guesses only legal bare object identifiers. Container expressions
    /// keep their `Array[...]` / `Dictionary[...]` shape even when a leaf type argument is guessed as an
    /// object name; they must never collapse into `new GdObjectType("Array[...]")`.
    /// New binder-style code should prefer `resolveTypeMeta(...)` or `tryParseStrictTextType(...)` when it
    /// needs a definitive answer.
    public static @Nullable GdType tryParseTextType(@NotNull String typeName) {
        var trimmed = typeName.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        var strict = tryParseStrictTextType(trimmed, null);
        if (strict != null) {
            return strict;
        }

        var compatibleContainerType = tryParseCompatibleContainerType(trimmed, null);
        if (compatibleContainerType != null) {
            return compatibleContainerType;
        }
        if (ScopeTypeTextSupport.looksStructuredTypeText(trimmed)) {
            return null;
        }
        return guessObjectTypeIfValidIdentifier(trimmed);
    }

    /// Parses top-level container expressions in compatibility mode.
    ///
    /// Compatibility mode differs from strict parsing in exactly one way: an unknown leaf type argument may
    /// still be treated as a guessed `GdObjectType` when it is a legal Godot identifier. The outer container
    /// remains typed, so `Array[MissingType]` becomes `Array[MissingType]` instead of degrading into a fake
    /// top-level object type named `"Array[MissingType]"`.
    private static @Nullable GdType tryParseCompatibleContainerType(@NotNull String textType,
                                                                    @Nullable ClassRegistry registry) {
        if (textType.startsWith("Array[") && textType.endsWith("]")) {
            var inner = textType.substring(6, textType.length() - 1).trim();
            if (inner.isEmpty()) {
                return null;
            }
            var innerType = resolveCompatibleNestedType(inner, registry);
            return innerType != null ? new GdArrayType(innerType) : null;
        }
        if (textType.startsWith("Dictionary[") && textType.endsWith("]")) {
            var inner = textType.substring(11, textType.length() - 1).trim();
            var parts = ScopeTypeTextSupport.splitDictionaryTypeArgs(inner);
            if (parts == null) {
                return null;
            }
            var keyType = resolveCompatibleNestedType(parts.getFirst(), registry);
            var valueType = resolveCompatibleNestedType(parts.getLast(), registry);
            if (keyType == null || valueType == null) {
                return null;
            }
            return new GdDictionaryType(keyType, valueType);
        }
        return null;
    }

    /// Resolves a leaf container type argument in compatibility mode.
    ///
    /// GDScript does not allow nested *structured* container declarations, so once we are inside `Array[...]`
    /// or `Dictionary[..., ...]` we intentionally reject texts such as `Array[int]` or
    /// `Dictionary[String, int]`. Bare container family names like `Array` / `Dictionary` are still legal
    /// leaf types here and represent `Array[Variant]` / `Dictionary[Variant, Variant]`.
    private static @Nullable GdType resolveCompatibleNestedType(@NotNull String typeText,
                                                                @Nullable ClassRegistry registry) {
        var trimmed = typeText.trim();
        if (trimmed.isEmpty() || ScopeTypeTextSupport.looksStructuredTypeText(trimmed)) {
            return null;
        }

        var strictType = tryParseStrictTextType(trimmed, registry);
        if (strictType != null) {
            return strictType;
        }
        if (registry != null) {
            var typeMeta = registry.resolveTypeMetaHere(trimmed);
            if (typeMeta != null) {
                return typeMeta.instanceType();
            }
            if (registry.isUtilityFunction(trimmed)) {
                return null;
            }
        }
        return guessObjectTypeIfValidIdentifier(trimmed);
    }

    private static @Nullable GdObjectType guessObjectTypeIfValidIdentifier(@NotNull String typeName) {
        return isLegalGodotIdentifier(typeName) ? new GdObjectType(typeName) : null;
    }

    /// Godot identifiers follow UAX#31-style identifier rules: they cannot start with a digit and may only
    /// contain identifier characters plus `_`. We use this guard before inventing an unknown object type so
    /// malformed texts like `Array[bad-name]` or `123Foo` do not silently become fake class names.
    static boolean isLegalGodotIdentifier(@NotNull String text) {
        var trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        var firstCodePoint = trimmed.codePointAt(0);
        if (!isGodotIdentifierStart(firstCodePoint)) {
            return false;
        }
        for (var index = Character.charCount(firstCodePoint); index < trimmed.length(); ) {
            var codePoint = trimmed.codePointAt(index);
            if (!isGodotIdentifierPart(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isGodotIdentifierStart(int codePoint) {
        return codePoint == '_' || Character.isUnicodeIdentifierStart(codePoint);
    }

    private static boolean isGodotIdentifierPart(int codePoint) {
        return codePoint == '_' || Character.isUnicodeIdentifierPart(codePoint);
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
            var parts = ScopeTypeTextSupport.splitDictionaryTypeArgs(inner);
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
    /// container type expressions. Structured nested container texts are still rejected up front, but bare
    /// container family names such as `Array` remain valid leaf types.
    private static @Nullable GdType resolveStrictNestedType(@NotNull String typeText,
                                                            @Nullable ClassRegistry registry) {
        var trimmed = typeText.trim();
        if (trimmed.isEmpty() || ScopeTypeTextSupport.looksStructuredTypeText(trimmed)) {
            return null;
        }

        var strictType = tryParseStrictTextType(trimmed, registry);
        if (strictType != null) {
            return strictType;
        }
        if (registry == null) {
            return null;
        }
        var typeMeta = registry.resolveTypeMetaHere(trimmed);
        if (typeMeta == null) {
            return null;
        }
        return typeMeta.instanceType();
    }

    private @Nullable GdType mapCompatibleUnresolvedType(
            @NotNull Scope ignoredScope,
            @NotNull String unresolvedTypeText
    ) {
        if (isGlobalEnum(unresolvedTypeText) || isUtilityFunction(unresolvedTypeText) || isSingleton(unresolvedTypeText)) {
            return null;
        }
        return guessObjectTypeIfValidIdentifier(unresolvedTypeText);
    }

    /// Return the function signature for a global utility function by name.
    public @Nullable FunctionSignature findUtilityFunctionSignature(@NotNull String name) {
        var uf = utilityByName.get(name);
        if (uf == null) return null;
        var params = new ArrayList<FunctionSignature.Parameter>();
        if (uf.arguments() != null) {
            for (var i = 0; i < uf.arguments().size(); i++) {
                var a = uf.arguments().get(i);
                var ptype = parseUtilityMetadataTypeOrNull(
                        a.type(),
                        "utility parameter #" + (i + 1) + " of '" + uf.name() + "'"
                );
                params.add(new FunctionSignature.Parameter(a.name(), ptype, a.defaultValue()));
            }
        }
        // Utility metadata omits `return_type` for void-like calls such as `print(...)`.
        // The registry contract should still expose a stable return slot to both frontend and
        // backend consumers instead of encoding "no return" as an accidental `null` branch.
        var rtype = uf.returnType() == null || uf.returnType().isBlank()
                ? GdVoidType.VOID
                : ScopeTypeParsers.parseExtensionTypeMetadata(
                uf.returnType(),
                "return type of utility '" + uf.name() + "'",
                this
        );
        return new FunctionSignature(uf.name(), params, uf.isVararg(), rtype);
    }

    private @Nullable GdType parseUtilityMetadataTypeOrNull(@Nullable String rawTypeName,
                                                            @NotNull String typeUseSite) {
        if (rawTypeName == null || rawTypeName.isBlank()) {
            return null;
        }
        return ScopeTypeParsers.parseExtensionTypeMetadata(rawTypeName, typeUseSite, this);
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

    public @NotNull @UnmodifiableView Map<String, VirtualMethodInfo> getVirtualMethods(@NotNull String className) {
        var virtualMethods = virtualMethodsByClassName.get(className);
        if (virtualMethods != null) {
            return virtualMethods;
        }
        var builtVirtualMethods = buildVirtualMethodMap(className, false);
        virtualMethodsByClassName.put(className, builtVirtualMethods);
        return builtVirtualMethods;
    }

    public @NotNull @UnmodifiableView Map<String, VirtualMethodInfo> getEngineVirtualMethods(@NotNull String className) {
        var engineVirtualMethods = engineVirtualMethodsByClassName.get(className);
        if (engineVirtualMethods != null) {
            return engineVirtualMethods;
        }
        var builtEngineVirtualMethods = buildVirtualMethodMap(className, true);
        engineVirtualMethodsByClassName.put(className, builtEngineVirtualMethods);
        return builtEngineVirtualMethods;
    }

    public @Nullable VirtualMethodInfo findEngineVirtualMethod(
            @NotNull String className,
            @NotNull String methodName
    ) {
        return getEngineVirtualMethods(className).get(methodName);
    }

    private @NotNull Map<String, VirtualMethodInfo> buildVirtualMethodMap(
            @NotNull String className,
            boolean engineOnly
    ) {
        var virtualMethods = new LinkedHashMap<String, VirtualMethodInfo>();
        ClassDef classDef = gdClassByName.get(className);
        if (classDef == null) {
            classDef = gdccClassByName.get(className);
        }
        if (classDef == null) {
            return Map.of();
        }
        for (var method : classDef.getFunctions()) {
            if (!method.isAbstract()) {
                continue;
            }
            if (engineOnly && classDef.isGdccClass()) {
                continue;
            }
            virtualMethods.put(method.getName(), new VirtualMethodInfo(
                    classDef.getName(),
                    !classDef.isGdccClass(),
                    method
            ));
        }
        var superClassName = classDef.getSuperName();
        if (!superClassName.isEmpty()) {
            var inheritedVirtualMethods = engineOnly ? getEngineVirtualMethods(superClassName) : getVirtualMethods(superClassName);
            for (var inheritedEntry : inheritedVirtualMethods.entrySet()) {
                virtualMethods.putIfAbsent(inheritedEntry.getKey(), inheritedEntry.getValue());
            }
        }
        return Collections.unmodifiableMap(virtualMethods);
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
        // `ClassDef#getSuperName()` now always carries canonical registry identity, so assignability
        // can walk the inheritance chain without any frontend/source-name side channel.
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
                var superCanonicalName = current.getSuperName();
                if (superCanonicalName.equals("RefCounted") || superCanonicalName.equals("Resource")) {
                    return RefCountedStatus.YES;
                }
                current = gdccClassByName.get(superCanonicalName);
                if (superCanonicalName.equals("Object") || superCanonicalName.equals("Node")) {
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
