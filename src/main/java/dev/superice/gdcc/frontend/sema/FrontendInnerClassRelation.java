package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/// Source-local ownership pairing for one parsed inner `class` declaration and the skeleton built
/// from that subtree.
///
/// Keeping the AST declaration alongside the `LirClassDef` lets later phases materialize inner
/// class scope/binding state without guessing from list order or class name alone.
public record FrontendInnerClassRelation(
        @NotNull ClassDeclaration declaration,
        @NotNull LirClassDef classDef
) {
    public FrontendInnerClassRelation {
        Objects.requireNonNull(declaration, "declaration must not be null");
        Objects.requireNonNull(classDef, "classDef must not be null");
    }
}
