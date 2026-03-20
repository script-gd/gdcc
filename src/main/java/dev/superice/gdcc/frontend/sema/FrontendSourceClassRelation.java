package dev.superice.gdcc.frontend.sema;

import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import dev.superice.gdcc.lir.LirClassDef;
import dev.superice.gdparser.frontend.ast.Node;
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
/// index" convention and gives frontend semantic consumers a stable place to hang source-local
/// class skeleton facts even when one file contributes multiple classes and nested classes need to
/// be recovered from their exact AST owner and lexical owner chain.
///
/// The top-level script class intentionally stores only one `name` field here. By invariant,
/// top-level gdcc classes use the same source-facing and canonical name, so keeping two separate
/// fields would add ambiguity without carrying extra information. The record itself also
/// implements the shared ownership protocol for that top-level class, so callers do not need a
/// separate adapter object.
public record FrontendSourceClassRelation(
        @NotNull FrontendSourceUnit unit,
        @NotNull String name,
        @NotNull FrontendSuperClassRef superClassRef,
        @NotNull LirClassDef topLevelClassDef,
        @NotNull List<FrontendInnerClassRelation> innerClassRelations
) implements FrontendOwnedClassRelation {
    public FrontendSourceClassRelation {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(superClassRef, "superClassRef must not be null");
        Objects.requireNonNull(topLevelClassDef, "topLevelClassDef must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        innerClassRelations = List.copyOf(Objects.requireNonNull(
                innerClassRelations,
                "innerClassRelations must not be null"
        ));
        FrontendOwnedClassRelation.validateOwnedRelation(
                unit.ast(),
                unit.ast(),
                name,
                name,
                superClassRef,
                topLevelClassDef,
                "Top-level relation"
        );
    }

    @Override
    public @NotNull Node lexicalOwner() {
        return unit.ast();
    }

    @Override
    public @NotNull Node astOwner() {
        return unit.ast();
    }

    @Override
    public @NotNull String sourceName() {
        return name;
    }

    @Override
    public @NotNull String canonicalName() {
        return name;
    }

    @Override
    public @NotNull LirClassDef classDef() {
        return topLevelClassDef;
    }

    @Override
    public @NotNull FrontendSuperClassRef superClassRef() {
        return superClassRef;
    }

    /// Compatibility view of nested class skeletons in source traversal order.
    ///
    /// Callers that need stable AST ownership should prefer `innerClassRelations()` so they can
    /// recover the exact `ClassDeclaration` matched by each source-local skeleton.
    public @NotNull List<LirClassDef> innerClassDefs() {
        return innerClassRelations.stream()
                .map(FrontendInnerClassRelation::classDef)
                .toList();
    }

    /// Resolves the source-local skeleton matched to one parsed inner class declaration.
    ///
    /// Identity lookup is deliberate: semantic consumers share the exact AST object graph
    /// published by parse/skeleton, so using `==` avoids accidental matches against structurally
    /// equal but unrelated synthetic nodes.
    public @Nullable FrontendOwnedClassRelation findRelation(
            @NotNull Node astOwner
    ) {
        Objects.requireNonNull(astOwner, "astOwner must not be null");
        if (unit.ast() == astOwner) {
            return this;
        }
        for (var innerClassRelation : innerClassRelations) {
            if (innerClassRelation.declaration() == astOwner) {
                return innerClassRelation;
            }
        }
        return null;
    }

    /// Returns only the inner classes declared directly under one lexical owner.
    ///
    /// This deliberately excludes deeper descendants so later type namespace publication can stay
    /// aligned with lexical visibility instead of flattening the whole subtree at once.
    public @NotNull List<FrontendInnerClassRelation> findImmediateInnerRelations(
            @NotNull Node lexicalOwner
    ) {
        Objects.requireNonNull(lexicalOwner, "lexicalOwner must not be null");
        return innerClassRelations.stream()
                .filter(innerClassRelation -> innerClassRelation.lexicalOwner() == lexicalOwner)
                .toList();
    }

    /// Returns the top-level class followed by every nested class discovered in the same source.
    public @NotNull List<LirClassDef> allClassDefs() {
        var classDefs = new ArrayList<LirClassDef>(1 + innerClassRelations.size());
        classDefs.add(topLevelClassDef);
        classDefs.addAll(innerClassDefs());
        return List.copyOf(classDefs);
    }
}
