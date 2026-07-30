package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Published resolution result for the RHS of a `TypeTestExpression` (`value is T` / `is not T`).
///
/// The type-test expression itself always publishes `RESOLVED(bool)` through `expressionTypes()`.
/// This side-table fact carries the target type separately so lowering / backend can read:
/// - a compile-time-known `GdType` (`TargetKnown`), or
/// - an unresolved but legal object class name (`TargetUnresolvedObject`) that must stay runtime-open.
///
/// Unresolved object targets are intentionally **not** modeled as `GdType` / `GdCompilerType` so they
/// never leak into source-facing type publication surfaces guarded by `FrontendPublishedFactTypeGuard`.
public sealed interface FrontendTypeTestTarget {

    /// Target type was resolved through the same declared-type path as variable annotations.
    record TargetKnown(@NotNull GdType type) implements FrontendTypeTestTarget {
        public TargetKnown {
            Objects.requireNonNull(type, "type must not be null");
        }
    }

    /// RHS is a legal identifier that `ScopeTypeResolver` did not resolve.
    ///
    /// Downstream must force a runtime ClassDB inheritance check and must not constant-fold.
    /// `typeName` is the source identifier text (not a canonical remap).
    record TargetUnresolvedObject(@NotNull String typeName) implements FrontendTypeTestTarget {
        public TargetUnresolvedObject {
            Objects.requireNonNull(typeName, "typeName must not be null");
            if (typeName.isBlank()) {
                throw new IllegalArgumentException("typeName must not be blank");
            }
        }
    }

    /// Logical equality for idempotent side-table merge / conflict checks.
    static boolean sameTarget(@NotNull FrontendTypeTestTarget first, @NotNull FrontendTypeTestTarget second) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");
        if (first instanceof TargetKnown(var type1) && second instanceof TargetKnown(var type2)) {
            return FrontendAnalysisData.sameType(type1, type2);
        }
        if (first instanceof TargetUnresolvedObject(var unresolvedTypeName1)
                && second instanceof TargetUnresolvedObject(var unresolvedTypeName2)) {
            return unresolvedTypeName1.equals(unresolvedTypeName2);
        }
        return false;
    }
}
