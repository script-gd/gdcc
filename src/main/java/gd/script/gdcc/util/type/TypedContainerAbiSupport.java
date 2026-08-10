package gd.script.gdcc.util.type;

import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdContainerType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedArrayType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Pure type-shape classifier for typed Array/Dictionary construction leaves.
///
/// Shared by frontend plan construction and backend typed-container guards so both sides fail closed
/// on the same unsupported shapes (nested typed containers, void/compiler-only, unresolved script
/// leaves). This helper never emits diagnostics or C metadata.
public final class TypedContainerAbiSupport {
    public enum LeafSupport {
        /// Leaf may appear under a non-generic typed container construction type.
        ALLOWED,
        /// Nested typed `Array[T]` / `Dictionary[K,V]` is not Godot 4.5 / GDCC ABI-ready.
        NESTED_TYPED_CONTAINER,
        /// `Array[Variant]` must stay generic outwardly; never a typed-array element leaf.
        VARIANT_ARRAY_ELEMENT,
        /// Void or compiler-only types never enter typed-container ABI.
        VOID_OR_COMPILER_ONLY,
        /// Object leaf that is neither engine nor GDCC class (needs non-nil typed_script).
        UNSUPPORTED_SCRIPT_LEAF,
        /// Type lacks outward GDExtension metadata.
        MISSING_METADATA
    }

    private TypedContainerAbiSupport() {
    }

    /// Returns a human-readable failure reason when `constructionType` is not ABI-ready, or null.
    public static @Nullable String unsupportedConstructionReason(
            @NotNull GdContainerType constructionType,
            @Nullable ClassRegistry classRegistry
    ) {
        Objects.requireNonNull(constructionType, "constructionType must not be null");
        return switch (constructionType) {
            case GdArrayType arrayType -> {
                if (arrayType.isGenericArray()) {
                    yield null;
                }
                yield failureReasonFor(
                        classifyArrayElementLeaf(arrayType.getValueType(), classRegistry),
                        arrayType.getValueType()
                );
            }
            case GdDictionaryType dictionaryType -> {
                if (dictionaryType.isGenericDictionary()) {
                    yield null;
                }
                var keyFailure = failureReasonFor(
                        classifyDictionarySideLeaf(dictionaryType.getKeyType(), classRegistry),
                        dictionaryType.getKeyType()
                );
                if (keyFailure != null) {
                    yield keyFailure;
                }
                yield failureReasonFor(
                        classifyDictionarySideLeaf(dictionaryType.getValueType(), classRegistry),
                        dictionaryType.getValueType()
                );
            }
            default -> "Unsupported container construction type: " + constructionType.getTypeName();
        };
    }

    public static @NotNull LeafSupport classifyArrayElementLeaf(
            @NotNull GdType leafType,
            @Nullable ClassRegistry classRegistry
    ) {
        return classifyLeaf(leafType, classRegistry, false);
    }

    public static @NotNull LeafSupport classifyDictionarySideLeaf(
            @NotNull GdType leafType,
            @Nullable ClassRegistry classRegistry
    ) {
        return classifyLeaf(leafType, classRegistry, true);
    }

    private static @Nullable String failureReasonFor(@NotNull LeafSupport support, @NotNull GdType leafType) {
        return switch (support) {
            case ALLOWED -> null;
            case NESTED_TYPED_CONTAINER ->
                    "Nested typed container leaf '" + leafType.getTypeName() + "' is not supported";
            case VARIANT_ARRAY_ELEMENT ->
                    "Variant element must stay generic Array; typed Array[Variant] is not an ABI leaf";
            case VOID_OR_COMPILER_ONLY ->
                    "Void/compiler-only type '" + leafType.getTypeName() + "' is not a valid typed-container leaf";
            case UNSUPPORTED_SCRIPT_LEAF ->
                    "Script-typed leaf '" + leafType.getTypeName()
                            + "' is not supported by the current typed-container ABI";
            case MISSING_METADATA ->
                    "Type '" + leafType.getTypeName() + "' lacks typed-container ABI metadata";
        };
    }

    private static @NotNull LeafSupport classifyLeaf(
            @NotNull GdType leafType,
            @Nullable ClassRegistry classRegistry,
            boolean allowVariantLeaf
    ) {
        Objects.requireNonNull(leafType, "leafType must not be null");
        return switch (leafType) {
            case GdCompilerType _, GdVoidType _ -> LeafSupport.VOID_OR_COMPILER_ONLY;
            case GdVariantType _ -> allowVariantLeaf
                    ? LeafSupport.ALLOWED
                    : LeafSupport.VARIANT_ARRAY_ELEMENT;
            case GdPackedArrayType _ -> LeafSupport.ALLOWED;
            case GdArrayType arrayType -> arrayType.isGenericArray()
                    ? LeafSupport.ALLOWED
                    : LeafSupport.NESTED_TYPED_CONTAINER;
            case GdDictionaryType dictionaryType -> dictionaryType.isGenericDictionary()
                    ? LeafSupport.ALLOWED
                    : LeafSupport.NESTED_TYPED_CONTAINER;
            case GdObjectType objectType -> {
                // Null registry is preview-only; without registry identity we cannot prove engine/GDCC leaf.
                if (classRegistry == null) {
                    yield LeafSupport.UNSUPPORTED_SCRIPT_LEAF;
                }
                if (objectType.checkEngineType(classRegistry) || objectType.checkGdccType(classRegistry)) {
                    yield LeafSupport.ALLOWED;
                }
                yield LeafSupport.UNSUPPORTED_SCRIPT_LEAF;
            }
            default -> leafType.getGdExtensionType() == null
                    ? LeafSupport.MISSING_METADATA
                    : LeafSupport.ALLOWED;
        };
    }
}
