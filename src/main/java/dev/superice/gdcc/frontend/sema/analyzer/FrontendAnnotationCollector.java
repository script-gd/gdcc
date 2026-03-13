package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendGdAnnotation;
import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import dev.superice.gdcc.frontend.parse.FrontendSourceUnit;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Collects semantically relevant GDScript annotations into an AST side table.
///
/// The collector currently ignores region-style warning annotations because this round only
/// prepares semantic attachment for node-local annotations. File-scoped annotations such as
/// `@tool` remain attached to the owning `SourceFile`/`ClassDeclaration` object.
public final class FrontendAnnotationCollector {
    private static final Set<String> OWNER_SCOPED_ANNOTATIONS = Set.of("tool", "icon");
    private static final Set<String> IGNORED_REGION_ANNOTATIONS = Set.of(
            "warning_ignore_start",
            "warning_ignore_restore"
    );

    public @NotNull FrontendAstSideTable<List<FrontendGdAnnotation>> collect(@NotNull FrontendSourceUnit unit) {
        Objects.requireNonNull(unit, "unit must not be null");
        var annotationsByAst = new FrontendAstSideTable<List<FrontendGdAnnotation>>();
        collectStatementList(unit.ast(), unit.ast().statements(), annotationsByAst);
        return annotationsByAst;
    }

    private void collectStatementList(
            @NotNull Node owner,
            @NotNull List<Statement> statements,
            @NotNull FrontendAstSideTable<List<FrontendGdAnnotation>> annotationsByAst
    ) {
        var pendingAnnotations = new ArrayList<FrontendGdAnnotation>();
        var atListStart = true;

        for (var statement : statements) {
            if (statement instanceof AnnotationStatement annotationStatement) {
                var annotation = FrontendGdAnnotation.fromAst(annotationStatement);
                if (!IGNORED_REGION_ANNOTATIONS.contains(annotation.name())) {
                    pendingAnnotations.add(annotation);
                }
                continue;
            }

            if (!pendingAnnotations.isEmpty()) {
                var target = atListStart && pendingAnnotations.stream()
                        .allMatch(annotation -> OWNER_SCOPED_ANNOTATIONS.contains(annotation.name()))
                        ? owner
                        : statement;
                appendAnnotations(target, pendingAnnotations, annotationsByAst);
                pendingAnnotations.clear();
            }

            atListStart = false;
            visitNested(statement, annotationsByAst);
        }
    }

    private void appendAnnotations(
            @NotNull Node target,
            @NotNull List<FrontendGdAnnotation> annotations,
            @NotNull FrontendAstSideTable<List<FrontendGdAnnotation>> annotationsByAst
    ) {
        annotationsByAst.compute(target, (_, existing) -> {
            if (existing == null) {
                return List.copyOf(annotations);
            }

            var merged = new ArrayList<>(existing);
            merged.addAll(annotations);
            return List.copyOf(merged);
        });
    }

    private void visitNested(
            @NotNull Statement statement,
            @NotNull FrontendAstSideTable<List<FrontendGdAnnotation>> annotationsByAst
    ) {
        switch (statement) {
            case Block block -> collectStatementList(block, block.statements(), annotationsByAst);
            case ClassDeclaration classDeclaration -> collectStatementList(
                    classDeclaration,
                    classDeclaration.body().statements(),
                    annotationsByAst
            );
            case FunctionDeclaration functionDeclaration -> collectStatementList(
                    functionDeclaration.body(),
                    functionDeclaration.body().statements(),
                    annotationsByAst
            );
            case ConstructorDeclaration constructorDeclaration -> collectStatementList(
                    constructorDeclaration.body(),
                    constructorDeclaration.body().statements(),
                    annotationsByAst
            );
            case IfStatement ifStatement -> {
                collectStatementList(ifStatement.body(), ifStatement.body().statements(), annotationsByAst);
                for (var elifClause : ifStatement.elifClauses()) {
                    collectStatementList(elifClause.body(), elifClause.body().statements(), annotationsByAst);
                }
                if (ifStatement.elseBody() != null) {
                    var elseBody = ifStatement.elseBody();
                    collectStatementList(elseBody, elseBody.statements(), annotationsByAst);
                }
            }
            case ForStatement forStatement -> collectStatementList(
                    forStatement.body(),
                    forStatement.body().statements(),
                    annotationsByAst
            );
            case WhileStatement whileStatement -> collectStatementList(
                    whileStatement.body(),
                    whileStatement.body().statements(),
                    annotationsByAst
            );
            case MatchStatement matchStatement -> {
                for (var section : matchStatement.sections()) {
                    collectStatementList(section.body(), section.body().statements(), annotationsByAst);
                }
            }
            default -> {
            }
        }
    }
}
