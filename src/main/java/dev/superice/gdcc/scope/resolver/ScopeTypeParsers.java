package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdIntType;
import dev.superice.gdcc.type.GdPackedArrayType;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// Shared type-text helpers used by metadata-driven scope resolvers.
///
/// Godot's extension metadata does not only expose canonical type names such as `String` or `Node`.
/// It also uses compatibility spellings like:
/// - `enum::Variant.Type`
/// - `bitfield::MethodFlags`
/// - `typedarray::PackedByteArray`
///
/// Backend code used to normalize those spellings through `CGenHelper.parseExtensionType(...)`.
/// The normalization now lives in the neutral `scope` layer so shared member resolvers can reuse the
/// same rules without depending on backend-only helpers.
public final class ScopeTypeParsers {
    private ScopeTypeParsers() {
    }

    /// Parse a type string as it appears in extension metadata.
    ///
    /// Compatibility rules:
    /// - blank metadata means `void`
    /// - `enum::...` / `bitfield::...` collapse to `int`
    /// - `typedarray::T` becomes `Packed*Array` when `T` is packed, otherwise `Array[T]`
    /// - all other names fall back to the existing compatibility parser in `ClassRegistry`
    public static @NotNull GdType parseExtensionTypeMetadata(@Nullable String rawTypeName,
                                                             @NotNull String typeUseSite) {
        return parseExtensionTypeMetadata(rawTypeName, typeUseSite, null);
    }

    /// Parse extension metadata with optional access to the active class registry.
    ///
    /// When a registry is available, class-like names and typedarray element types are resolved
    /// against the already indexed engine/GDCC classes instead of degrading into guessed object types.
    public static @NotNull GdType parseExtensionTypeMetadata(@Nullable String rawTypeName,
                                                             @NotNull String typeUseSite,
                                                             @Nullable ClassRegistry registry) {
        if (rawTypeName == null || rawTypeName.isBlank()) {
            return GdVoidType.VOID;
        }
        var normalized = rawTypeName.trim();
        if (normalized.startsWith("enum::") || normalized.startsWith("bitfield::")) {
            return GdIntType.INT;
        }
        if (normalized.startsWith("typedarray::")) {
            var elementTypeName = normalized.substring("typedarray::".length()).trim();
            if (elementTypeName.isBlank()) {
                throw new IllegalArgumentException(
                        typeUseSite + " has malformed typedarray metadata: '" + rawTypeName + "'"
                );
            }
            // Godot's `typedarray::T` metadata models a single element type, not an arbitrary
            // nested container expression. Reject bracketed forms up front so malformed metadata
            // like `typedarray::Array[]` does not silently degrade into `Array[Object]`.
            if (elementTypeName.contains("[") || elementTypeName.contains("]")) {
                throw new IllegalArgumentException(
                        typeUseSite + " has unsupported type metadata: '" + rawTypeName + "'"
                );
            }
            var elementType = parseExtensionTypeMetadata(elementTypeName, typeUseSite, registry);
            if (elementType instanceof GdPackedArrayType) {
                return elementType;
            }
            return new GdArrayType(elementType);
        }
        var parsed = registry != null
                ? registry.tryResolveDeclaredType(normalized)
                : ClassRegistry.tryParseTextType(normalized);
        if (parsed == null) {
            throw new IllegalArgumentException(
                    typeUseSite + " has unsupported type metadata: '" + rawTypeName + "'"
            );
        }
        return parsed;
    }
}
