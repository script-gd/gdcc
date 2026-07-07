package gd.script.gdcc.frontend.sema;

import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.scope.ScopeValue;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// One ordinary local declaration published by the baseline inventory layer for a supported body.
///
/// `sourceOrder` is local to the owning body root. It preserves the complete-inventory plus
/// source-order model required by `FrontendVisibleValueResolver`: the resolver can see future locals
/// in the scope graph, then decide whether a use-site is before or after this declaration.
public record FrontendBodyLocalDeclaration(
        @NotNull VariableDeclaration declaration,
        @NotNull ScopeValue binding,
        int sourceOrder
) {
    public FrontendBodyLocalDeclaration {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(binding, "binding");
        if (binding.declaration() != declaration) {
            throw new IllegalArgumentException("binding declaration must match the local declaration");
        }
        if (sourceOrder < 0) {
            throw new IllegalArgumentException("sourceOrder must not be negative");
        }
    }
}
