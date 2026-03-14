package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

/// Source-local ownership pairing for one parsed inner `class` declaration and the skeleton built
/// from that subtree.
///
/// The declaration keeps the source-facing class name, while `classDef.getName()` is already the
/// canonical identity that later phases and the registry will use. The record itself is also the
/// shared ownership view consumed by later phases; no extra adapter object is needed.
public record FrontendInnerClassRelation(
        @NotNull Node lexicalOwner,
        @NotNull ClassDeclaration declaration,
        @NotNull String sourceName,
        @NotNull String canonicalName,
        @NotNull FrontendSuperClassRef superClassRef,
        @NotNull LirClassDef classDef
) implements FrontendOwnedClassRelation {
    public FrontendInnerClassRelation {
        FrontendOwnedClassRelation.validateOwnedRelation(
                lexicalOwner,
                declaration,
                sourceName,
                canonicalName,
                superClassRef,
                classDef,
                "Inner class relation"
        );
    }

    @Override
    public @NotNull ClassDeclaration astOwner() {
        return declaration;
    }
}
