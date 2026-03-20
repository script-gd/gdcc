package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.resolver.ScopeTypeResolver;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.TypeRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/// Shared strict declared-type resolution helper for frontend semantic phases.
///
/// The contract is intentionally small:
/// - missing declarations stay `Variant`
/// - variable inventory initially publishes inferred declarations (`:=`) as `Variant`
/// - later body phases may backfill supported local `:=` bindings from published RHS expression types
/// - declared type lookup stays on the strict shared resolver
/// - unresolved declared types emit one warning and fall back to `Variant`
public final class FrontendDeclaredTypeSupport {
    private FrontendDeclaredTypeSupport() {
    }

    /// Returns whether the parser-level type marker represents a `:=` inferred declaration.
    public static boolean isInferredTypeRef(@Nullable TypeRef typeRef) {
        if (typeRef == null) {
            return false;
        }
        return typeRef.sourceText().trim().equals(":=");
    }

    public static @NotNull GdType resolveTypeOrVariant(
            @Nullable TypeRef typeRef,
            @NotNull Scope declaredTypeScope,
            @NotNull Path sourcePath,
            @NotNull DiagnosticManager diagnostics
    ) {
        Objects.requireNonNull(declaredTypeScope, "declaredTypeScope must not be null");
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        if (typeRef == null) {
            return GdVariantType.VARIANT;
        }

        var typeText = typeRef.sourceText().trim();
        if (typeText.isEmpty() || isInferredTypeRef(typeRef)) {
            return GdVariantType.VARIANT;
        }

        var resolvedType = ScopeTypeResolver.tryResolveDeclaredType(declaredTypeScope, typeText);
        if (resolvedType != null) {
            return resolvedType;
        }

        diagnostics.warning(
                "sema.type_resolution",
                "Unknown type '" + typeRef.sourceText() + "', fallback to Variant",
                sourcePath,
                FrontendRange.fromAstRange(typeRef.range())
        );
        return GdVariantType.VARIANT;
    }
}
