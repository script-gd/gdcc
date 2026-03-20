package dev.superice.gdcc.scope.resolver;

import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.type.GdArrayType;
import dev.superice.gdcc.type.GdDictionaryType;
import dev.superice.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Shared strict type-name resolver built on top of the `Scope` type/meta protocol.
///
/// Responsibilities:
/// - resolve bare type names through the current lexical type namespace
/// - parse top-level strict container texts `Array[T]` / `Dictionary[K, V]`
/// - keep nested structured container texts rejected, matching the current strict declared-type rules
///
/// Non-responsibilities:
/// - inventing unknown object types
/// - producing diagnostics
/// - deciding caller-specific fallback policies such as `Variant`
public final class ScopeTypeResolver {
    private ScopeTypeResolver() {
    }

    /// Resolves one type/meta symbol in the given lexical scope under the current always-allowed
    /// type namespace contract.
    public static @Nullable ScopeTypeMeta tryResolveTypeMeta(
            @NotNull Scope scope,
            @NotNull String typeText
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(typeText, "typeText");
        var trimmed = typeText.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return scope.resolveTypeMeta(trimmed, ResolveRestriction.unrestricted()).allowedValueOrNull();
    }

    /// Resolves a strict declared type in the given scope.
    ///
    /// Exact type/meta lookup runs first so:
    /// - lexical `sourceName` bindings can shadow global names
    /// - the registry may keep resolving fully strict global texts such as `Array[String]`
    ///
    /// If that direct lookup misses, top-level container parsing retries with nested leaf arguments
    /// resolved through the same lexical scope. This is the path that makes texts such as
    /// `Array[Inner]` work for inner classes published only in local type namespaces.
    public static @Nullable GdType tryResolveDeclaredType(
            @NotNull Scope scope,
            @NotNull String typeText
    ) {
        return tryResolveDeclaredType(scope, typeText, null);
    }

    /// Resolves a strict declared type in the given scope and optionally maps unresolved leaf names.
    ///
    /// The mapper is consulted only after strict lookup fails for a bare identifier or for a nested
    /// leaf inside the top-level `Array[...]` / `Dictionary[..., ...]` forms. It is never used to
    /// recover malformed structured texts, which stay rejected so strict container rules remain frozen.
    public static @Nullable GdType tryResolveDeclaredType(
            @NotNull Scope scope,
            @NotNull String typeText,
            @Nullable UnresolvedTypeMapper unresolvedTypeMapper
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(typeText, "typeText");
        var trimmed = typeText.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        var directTypeMeta = tryResolveTypeMeta(scope, trimmed);
        if (directTypeMeta != null) {
            return directTypeMeta.instanceType();
        }
        if (trimmed.startsWith("Array[") && trimmed.endsWith("]")) {
            var innerText = trimmed.substring(6, trimmed.length() - 1).trim();
            if (innerText.isEmpty()) {
                return null;
            }
            var innerType = resolveNestedDeclaredType(scope, innerText, unresolvedTypeMapper);
            return innerType != null ? new GdArrayType(innerType) : null;
        }
        if (trimmed.startsWith("Dictionary[") && trimmed.endsWith("]")) {
            var innerText = trimmed.substring(11, trimmed.length() - 1).trim();
            var dictionaryArgs = ScopeTypeTextSupport.splitDictionaryTypeArgs(innerText);
            if (dictionaryArgs == null) {
                return null;
            }
            var keyType = resolveNestedDeclaredType(scope, dictionaryArgs.getFirst(), unresolvedTypeMapper);
            var valueType = resolveNestedDeclaredType(scope, dictionaryArgs.getLast(), unresolvedTypeMapper);
            if (keyType == null || valueType == null) {
                return null;
            }
            return new GdDictionaryType(keyType, valueType);
        }
        return mapUnresolvedType(scope, trimmed, unresolvedTypeMapper);
    }

    /// Nested container arguments intentionally stay stricter than the top-level declared type slot.
    ///
    /// Once we are already inside `Array[...]` or `Dictionary[..., ...]`, another structured
    /// container declaration is rejected instead of being recursively flattened. Bare family names
    /// such as `Array` / `Dictionary` remain valid leaf types because the scope root still exposes
    /// them as strict builtin type-meta bindings.
    private static @Nullable GdType resolveNestedDeclaredType(
            @NotNull Scope scope,
            @NotNull String typeText,
            @Nullable UnresolvedTypeMapper unresolvedTypeMapper
    ) {
        var trimmed = typeText.trim();
        if (trimmed.isEmpty() || ScopeTypeTextSupport.looksStructuredTypeText(trimmed)) {
            return null;
        }
        var typeMeta = tryResolveTypeMeta(scope, trimmed);
        return typeMeta != null ? typeMeta.instanceType() : mapUnresolvedType(scope, trimmed, unresolvedTypeMapper);
    }

    private static @Nullable GdType mapUnresolvedType(
            @NotNull Scope scope,
            @NotNull String unresolvedTypeText,
            @Nullable UnresolvedTypeMapper unresolvedTypeMapper
    ) {
        if (unresolvedTypeMapper == null) {
            return null;
        }
        return unresolvedTypeMapper.mapUnresolvedType(scope, unresolvedTypeText);
    }
}
