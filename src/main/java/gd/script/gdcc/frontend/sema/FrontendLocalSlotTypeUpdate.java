package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// One local-slot type rewrite produced by local type stabilization.
///
/// The update itself is stage-scoped metadata. `FrontendAnalysisData.applyPatch(...)` owns the
/// actual scope mutation rules, compiler-only guards, and published binding payload refresh.
public record FrontendLocalSlotTypeUpdate(
        @NotNull BlockScope scope,
        @NotNull String name,
        @NotNull Object declaration,
        @NotNull GdType type
) {
    public FrontendLocalSlotTypeUpdate {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(declaration, "declaration must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
