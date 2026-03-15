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
/// - missing or inferred declarations (`:=`) stay `Variant`
/// - declared type lookup stays on the strict shared resolver
/// - unresolved declared types emit one warning and fall back to `Variant`
public final class FrontendDeclaredTypeSupport {
    private FrontendDeclaredTypeSupport() {
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
        // `gdparser` currently exposes inferred declarations as `:=` through `TypeRef.sourceText()`.
        if (typeText.isEmpty() || typeText.equals(":=")) {
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
