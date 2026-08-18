package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// One frozen lambda capture: source-facing name, declaration-site type, and the outer binding that
/// produced it.
///
/// Inventory first records a `Variant` placeholder. Nested resolve fills `type` from the
/// declaration-site type via [`withType`](#withType) and must also call
/// `CallableScope.resetCaptureType` so the scope binding stays in sync.
///
/// @param name              source identifier copied into the lambda (`self` uses this same shape)
/// @param type              declaration-site type of the captured binding; never compiler-only
/// @param sourceKind        `PARAMETER` / `LOCAL` / `CAPTURE` of the first hit outside this lambda
/// @param sourceDeclaration declaration identity of that outer binding (`VariableDeclaration`,
///                          `Parameter`, `ForStatement`, or a later `self` descriptor)
public record LambdaCaptureEntry(
        @NotNull String name,
        @NotNull GdType type,
        @NotNull ScopeValueKind sourceKind,
        @Nullable Object sourceDeclaration
) {
    public LambdaCaptureEntry {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(sourceKind, "sourceKind must not be null");
        if (sourceKind != ScopeValueKind.PARAMETER
                && sourceKind != ScopeValueKind.LOCAL
                && sourceKind != ScopeValueKind.CAPTURE) {
            throw new IllegalArgumentException(
                    "sourceKind must be PARAMETER, LOCAL, or CAPTURE, got " + sourceKind
            );
        }
    }

    /// Returns a copy with a replacement type. Used when nested resolve fills the declaration-site
    /// type; name, kind, and declaration identity stay frozen.
    public @NotNull LambdaCaptureEntry withType(@NotNull GdType newType) {
        return new LambdaCaptureEntry(name, Objects.requireNonNull(newType, "newType must not be null"), sourceKind, sourceDeclaration);
    }

    /// Logical equivalence for idempotent side-table merge. Types compare by class+name so a
    /// republished equivalent instance is not a conflict; `sourceDeclaration` is identity.
    public static boolean sameEntry(@NotNull LambdaCaptureEntry first, @NotNull LambdaCaptureEntry second) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");
        return first.name().equals(second.name())
                && FrontendAnalysisData.sameType(first.type(), second.type())
                && first.sourceKind() == second.sourceKind()
                && first.sourceDeclaration() == second.sourceDeclaration();
    }
}
