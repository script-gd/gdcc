package gd.script.gdcc.frontend.sema.analyzer.support;

import dev.superice.gdparser.frontend.ast.TypeRef;
import gd.script.gdcc.frontend.constant.GdConstantValue;
import gd.script.gdcc.frontend.sema.FrontendDeclaredTypeSupport;
import gd.script.gdcc.frontend.sema.FrontendModuleSkeleton;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/// Strict declared-type validation and payload conversion for `const` declarations.
///
/// Unlike `FrontendDeclaredTypeSupport` — which silently widens unresolvable annotations to
/// `Variant` for ordinary members — a const annotation is part of the frozen contract and must
/// be exact. This helper therefore returns an explicit tri-state instead of warning+fallback,
/// and the payload conversion reuses the frontend implicit-conversion matrix rather than any
/// ad-hoc widening so class and local const owners share one rule.
public final class FrontendConstDeclaredTypeSupport {
    private FrontendConstDeclaredTypeSupport() {
    }

    /// Tri-state of a `const` declaration's explicit type annotation.
    public sealed interface DeclaredTypeCheck {
        /// No annotation (or `:=` inference marker): the owner infers the frozen value's type.
        record NoAnnotation() implements DeclaredTypeCheck {
        }

        /// The annotation resolved to a supported constant type.
        record Resolved(@NotNull GdType declaredType) implements DeclaredTypeCheck {
            public Resolved {
                Objects.requireNonNull(declaredType, "declaredType must not be null");
            }
        }

        /// The annotation is unusable for constants (unresolved name, explicit `Variant`, or a
        /// type outside the frozen-value whitelist such as objects/containers/resources).
        record Rejected(@NotNull String reason) implements DeclaredTypeCheck {
            public Rejected {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }
    }

    /// Validates the optional annotation of one `const` declaration against the strict declared
    /// type surface. Named script enum aliases resolve to `int` through their published type-meta
    /// and therefore pass the constant-type whitelist naturally.
    public static @NotNull DeclaredTypeCheck checkDeclaredType(
            @Nullable TypeRef typeRef,
            @NotNull Scope declaredTypeScope,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) {
        Objects.requireNonNull(declaredTypeScope, "declaredTypeScope must not be null");
        Objects.requireNonNull(topLevelCanonicalNameMap, "topLevelCanonicalNameMap must not be null");
        if (typeRef == null || FrontendDeclaredTypeSupport.isInferredTypeRef(typeRef)) {
            return new DeclaredTypeCheck.NoAnnotation();
        }
        var typeText = typeRef.sourceText().trim();
        var resolvedType = FrontendModuleSkeleton.tryResolveSourceFacingDeclaredType(
                declaredTypeScope,
                typeText,
                topLevelCanonicalNameMap
        );
        if (resolvedType == null) {
            return new DeclaredTypeCheck.Rejected(
                    "constant declared type '" + typeText + "' cannot be resolved");
        }
        if (resolvedType instanceof GdVariantType) {
            return new DeclaredTypeCheck.Rejected(
                    "constant declared type 'Variant' is not supported; "
                            + "annotate an exact type or omit the annotation");
        }
        if (!GdConstantValue.isSupportedConstantType(resolvedType)) {
            return new DeclaredTypeCheck.Rejected(
                    "constant declared type '" + resolvedType.getTypeName()
                            + "' is outside the supported constant type set");
        }
        return new DeclaredTypeCheck.Resolved(resolvedType);
    }

    /// Narrows/widens a frozen payload to the annotated declared type.
    ///
    /// Only conversions the frontend implicit-conversion matrix already blesses are applied at
    /// this boundary — identity, `int -> float`, and same-dimension `Vector*i -> Vector*` — so a
    /// const annotation can never launder a payload across an unsupported edge. Returns null
    /// when the pair has no allowed conversion; the caller reports that as a declaration error.
    public static @Nullable GdConstantValue convertToDeclaredType(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdConstantValue value,
            @NotNull GdType declaredType
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(declaredType, "declaredType must not be null");
        var decision = FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                classRegistry,
                value.type(),
                declaredType
        );
        return switch (decision) {
            case ALLOW_DIRECT -> value;
            case ALLOW_WITH_INTRINSIC_CAST -> convertIntrinsic(value, declaredType);
            default -> null;
        };
    }

    /// Applies the intrinsic-cast payload conversion. The frontend matrix only produces this
    /// decision for `int -> float` and same-dimension `Vector*i -> Vector*`, so any other pair
    /// reaching here is a programming error in the boundary helper, not a source failure.
    private static @NotNull GdConstantValue convertIntrinsic(
            @NotNull GdConstantValue value,
            @NotNull GdType declaredType
    ) {
        if (value instanceof GdConstantValue.IntValue intValue
                && declaredType instanceof GdFloatType) {
            return new GdConstantValue.FloatValue(intValue.value());
        }
        if (value instanceof GdConstantValue.BuiltinValue builtinValue
                && value.type() instanceof GdIntVectorType
                && declaredType instanceof GdFloatVectorType) {
            var converted = builtinValue.components().stream()
                    .map(leaf -> (GdConstantValue) new GdConstantValue.FloatValue(
                            ((GdConstantValue.IntValue) leaf).value()))
                    .toList();
            return new GdConstantValue.BuiltinValue(declaredType, converted);
        }
        throw new IllegalStateException(
                "intrinsic-cast decision reached for unsupported pair "
                        + value.type().getTypeName() + " -> " + declaredType.getTypeName()
        );
    }
}
