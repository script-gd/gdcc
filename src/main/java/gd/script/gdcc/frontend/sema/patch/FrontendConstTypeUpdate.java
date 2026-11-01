package gd.script.gdcc.frontend.sema.patch;

import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// One constant slot type rewrite produced by `CONST_RESOLUTION`.
///
/// The update itself is owner-scoped metadata. `FrontendAnalysisData` owns the final scope
/// mutation rules (`BlockScope.resetConstantType`: `CONSTANT` kind, declaration identity, and a
/// Variant-baseline/idempotent rule), while `FrontendTypedLexicalEnvironment` holds the same
/// update as pending data until suite export applies it.
public record FrontendConstTypeUpdate(
        @NotNull BlockScope scope,
        @NotNull String name,
        @NotNull Object declaration,
        @NotNull GdType type
) {
    public FrontendConstTypeUpdate {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(declaration, "declaration must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
