package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.gdscript.DiagnosticCodes;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendParseRecovery;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
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
        var raw = typeRef.sourceText();
        var end = raw.length();
        var colon = StringUtil.skipGdWhitespace(raw, 0, end);
        if (colon == end || raw.charAt(colon) != ':') {
            return false;
        }
        var equals = StringUtil.skipGdTrivia(raw, colon + 1, end);
        return equals < end && raw.charAt(equals) == '='
                && StringUtil.skipGdWhitespace(raw, equals + 1, end) == end;
    }

    /// Resolves a callable's declared type slot without re-reporting shared parser errors.
    public static @NotNull GdType resolveTypeOrVariant(
            @Nullable TypeRef typeRef,
            @NotNull Scope declaredTypeScope,
            @NotNull Map<String, String> topLevelCanonicalNameMap,
            @NotNull Path sourcePath,
            @NotNull DiagnosticManager diagnostics,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull Node owner,
            @NotNull String facet
    ) {
        var byOwner = analysisData.parseRecoveriesByOwner();
        var ownerRecoveries = byOwner.get(owner);
        var typeRecoveries = typeRef == null || typeRef == owner ? null : byOwner.get(typeRef);
        if (ownerRecoveries == null && typeRecoveries == null) {
            return resolveTypeOrVariant(typeRef, declaredTypeScope, topLevelCanonicalNameMap, sourcePath, diagnostics);
        }
        var matching = new ArrayList<FrontendParseRecovery>();
        if (ownerRecoveries != null) {
            for (var recovery : ownerRecoveries) {
                if (recovery.severity() == FrontendDiagnosticSeverity.ERROR
                        && (facet.equals(recovery.facet()) || isDamagedTypeArgument(recovery))) {
                    matching.add(recovery);
                }
            }
        }
        if (typeRecoveries != null) {
            for (var recovery : typeRecoveries) {
                if (recovery.severity() == FrontendDiagnosticSeverity.ERROR && isDamagedTypeArgument(recovery)) {
                    matching.add(recovery);
                }
            }
        }
        if (matching.isEmpty()) {
            return resolveTypeOrVariant(typeRef, declaredTypeScope, topLevelCanonicalNameMap, sourcePath, diagnostics);
        }

        var source = FrontendDiagnostic.sourcePathText(sourcePath);
        var coveringDiagnostics = new ArrayList<FrontendDiagnostic>(matching.size());
        for (var recovery : matching) {
            var cover = FrontendParseRecovery.findActiveCoveringDiagnostic(recovery, analysisData, diagnostics, source);
            if (cover == null) {
                // One fallback claims the whole slot for both skeleton and parameter binding.
                var fallback = FrontendDiagnostic.error(
                        "sema.type_resolution",
                        "Invalid declared type, fallback to Variant: " + recovery.message(),
                        source,
                        typeRef == null ? recovery.range() : FrontendRange.fromAstRange(typeRef.range())
                );
                diagnostics.report(fallback);
                for (var item : matching) {
                    analysisData.claimParseRecovery(item, fallback);
                }
                return GdVariantType.VARIANT;
            }
            coveringDiagnostics.add(cover);
        }
        for (var i = 0; i < matching.size(); i++) {
            matching.get(i).claimCoveringDiagnostic(analysisData, diagnostics, coveringDiagnostics.get(i));
        }
        return GdVariantType.VARIANT;
    }

    private static boolean isDamagedTypeArgument(@NotNull FrontendParseRecovery recovery) {
        return "type.argument".equals(recovery.facet()) && switch (recovery.code()) {
            case DiagnosticCodes.ATTRIBUTE_MISSING_NAME,
                 DiagnosticCodes.SUPER_MISSING_NAME,
                 DiagnosticCodes.SUPER_MISSING_CALL,
                 DiagnosticCodes.SUPER_MISSING_ACCESS,
                 DiagnosticCodes.MISSING_SEPARATOR_SUITE_COLON,
                 DiagnosticCodes.MISSING_SEPARATOR_MATCH_COLON -> false;
            default -> true;
        };
    }

    public static @NotNull GdType resolveTypeOrVariant(
            @Nullable TypeRef typeRef,
            @NotNull Scope declaredTypeScope,
            @NotNull Map<String, String> topLevelCanonicalNameMap,
            @NotNull Path sourcePath,
            @NotNull DiagnosticManager diagnostics
    ) {
        Objects.requireNonNull(declaredTypeScope, "declaredTypeScope must not be null");
        Objects.requireNonNull(topLevelCanonicalNameMap, "topLevelCanonicalNameMap must not be null");
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        if (typeRef == null) {
            return GdVariantType.VARIANT;
        }

        var typeText = typeRef.sourceText().trim();
        if (typeText.isEmpty() || isInferredTypeRef(typeRef)) {
            return GdVariantType.VARIANT;
        }

        var resolvedType = FrontendModuleSkeleton.tryResolveSourceFacingDeclaredType(
                declaredTypeScope,
                typeText,
                topLevelCanonicalNameMap
        );
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
