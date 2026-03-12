package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Skeleton ownership relation for one parsed source file.
///
/// One `FrontendSourceUnit` always owns exactly:
/// - one top-level script `ClassDef`
/// - zero or more nested/inner `ClassDeclaration -> ClassDef` pairs discovered inside that file
///
/// Keeping this relation explicit removes the previous fragile "units and classDefs share the same
/// index" convention and gives later phases a stable place to hang source-local class skeleton
/// facts even when one file contributes multiple classes and nested classes need to be recovered
/// from their exact AST owner.
public record FrontendSourceClassRelation(
        @NotNull FrontendSourceUnit unit,
        @NotNull LirClassDef topLevelClassDef,
        @NotNull List<FrontendInnerClassRelation> innerClassRelations
) {
    public FrontendSourceClassRelation {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(topLevelClassDef, "topLevelClassDef must not be null");
        innerClassRelations = List.copyOf(Objects.requireNonNull(
                innerClassRelations,
                "innerClassRelations must not be null"
        ));
    }

    /// Compatibility view of nested class skeletons in source traversal order.
    ///
    /// Newer phases that need stable AST ownership should prefer `innerClassRelations()` so they
    /// can recover the exact `ClassDeclaration` matched by each source-local skeleton.
    public @NotNull List<LirClassDef> innerClassDefs() {
        return innerClassRelations.stream()
                .map(FrontendInnerClassRelation::classDef)
                .toList();
    }

    /// Resolves the source-local skeleton matched to one parsed inner class declaration.
    ///
    /// Identity lookup is deliberate: later phases share the exact AST object graph published by
    /// parse/skeleton, so using `==` avoids accidental matches against structurally equal but
    /// unrelated synthetic nodes.
    public @Nullable LirClassDef findInnerClassDef(@NotNull ClassDeclaration declaration) {
        Objects.requireNonNull(declaration, "declaration must not be null");
        for (var innerClassRelation : innerClassRelations) {
            if (innerClassRelation.declaration() == declaration) {
                return innerClassRelation.classDef();
            }
        }
        return null;
    }

    /// Returns the top-level class followed by every nested class discovered in the same source.
    public @NotNull List<LirClassDef> allClassDefs() {
        var classDefs = new ArrayList<LirClassDef>(1 + innerClassRelations.size());
        classDefs.add(topLevelClassDef);
        classDefs.addAll(innerClassDefs());
        return List.copyOf(classDefs);
    }
}
