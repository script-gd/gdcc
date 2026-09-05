package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendGdAnnotation;
import dev.superice.gdparser.frontend.ast.AnnotationStatement;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.CommentStatement;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Collects semantically relevant GDScript annotations into an AST side table.
///
/// Attachment follows Godot's `parse_program` script-annotation recognition: while a statement
/// list's owner preamble is still open, script-scoped annotations (`@tool` / `@icon`) attach to
/// the owning `SourceFile` / `ClassDeclaration`; every other annotation attaches to the statement
/// that follows it. The preamble closes on the first real statement and immediately after any
/// member-target annotation, so e.g. `@export` followed by `@tool` no longer treats `@tool` as
/// script-level. Class/root-compatible or unknown annotations (`@abstract`, `@warning_ignore`,
/// ...) attach to the following statement without closing the preamble.
///
/// Comments and leading docstrings are transparent trivia. Annotations left dangling at the end
/// of a statement list anchor on their own `AnnotationStatement` so the usage analyzer keeps a
/// stable placement-diagnostic node and the skeleton never consumes them as member annotations.
///
/// Region-style warning annotations are ignored: they attach to no node and carry no semantic
/// payload for downstream phases.
public final class FrontendAnnotationCollector {
    /// Annotations Godot registers as `AnnotationInfo::SCRIPT`: legal only at the script root.
    private static final Set<String> OWNER_SCOPED_ANNOTATIONS = Set.of("tool", "icon");
    /// Annotations that target members; consuming one closes the owner preamble (Godot parity: a
    /// `@tool` written after a member annotation is rejected). Not-yet-supported export members are
    /// classified here as well, so adding support for them later cannot silently change preamble
    /// behavior.
    private static final Set<String> MEMBER_TARGET_ANNOTATIONS = Set.of(
            "onready",
            "rpc",
            "export",
            "export_range",
            "export_enum",
            "export_flags",
            "export_flags_2d_render",
            "export_flags_2d_physics",
            "export_flags_2d_navigation",
            "export_flags_3d_render",
            "export_flags_3d_physics",
            "export_flags_3d_navigation",
            "export_flags_avoidance",
            "export_file",
            "export_file_path",
            "export_dir",
            "export_global_file",
            "export_global_dir",
            "export_multiline",
            "export_placeholder",
            "export_exp_easing",
            "export_color_no_alpha",
            "export_node_path",
            "export_category",
            "export_group",
            "export_subgroup",
            "export_storage",
            "export_custom",
            "export_tool_button"
    );
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
        var pendingAnnotations = new ArrayList<PendingAnnotation>();
        var ownerPreambleOpen = true;

        for (var statement : statements) {
            if (statement instanceof AnnotationStatement annotationStatement) {
                var annotation = FrontendGdAnnotation.fromAst(annotationStatement);
                if (!IGNORED_REGION_ANNOTATIONS.contains(annotation.name())) {
                    pendingAnnotations.add(new PendingAnnotation(annotation, annotationStatement));
                }
                continue;
            }
            if (isPreambleTrivia(statement, ownerPreambleOpen)) {
                continue;
            }

            if (!pendingAnnotations.isEmpty()) {
                flushPendingAnnotations(
                        owner,
                        statement,
                        pendingAnnotations,
                        ownerPreambleOpen,
                        annotationsByAst
                );
                pendingAnnotations.clear();
            }

            // Any real statement ends the owner preamble: annotations written after it can no
            // longer be script-level, even when the pending queue was empty at this point.
            ownerPreambleOpen = false;
            visitNested(statement, annotationsByAst);
        }

        if (!pendingAnnotations.isEmpty()) {
            flushPendingAnnotations(owner, null, pendingAnnotations, ownerPreambleOpen, annotationsByAst);
        }
    }

    /// Flushes pending annotations one by one in source order. An owner-scoped annotation attaches
    /// to the list owner only while the preamble is open; any other annotation attaches to the
    /// following statement, or — when the list ends (`followingStatement == null`) — to its own
    /// `AnnotationStatement` anchor.
    ///
    /// The preamble state is threaded only within the batch (a member-target annotation disqualifies
    /// a later `@tool` in the same batch); the caller closes the preamble on every real statement,
    /// so the post-flush state never escapes this method.
    private void flushPendingAnnotations(
            @NotNull Node owner,
            @Nullable Node followingStatement,
            @NotNull List<PendingAnnotation> pendingAnnotations,
            boolean ownerPreambleOpen,
            @NotNull FrontendAstSideTable<List<FrontendGdAnnotation>> annotationsByAst
    ) {
        for (var pendingAnnotation : pendingAnnotations) {
            var annotation = pendingAnnotation.annotation();
            if (ownerPreambleOpen && OWNER_SCOPED_ANNOTATIONS.contains(annotation.name())) {
                // Script-scoped annotations keep the preamble open so a following `@tool` after
                // e.g. `@icon` is still recognized as script-level.
                appendAnnotations(owner, List.of(annotation), annotationsByAst);
                continue;
            }
            appendAnnotations(
                    followingStatement != null ? followingStatement : pendingAnnotation.anchor(),
                    List.of(annotation),
                    annotationsByAst
            );
            if (MEMBER_TARGET_ANNOTATIONS.contains(annotation.name())) {
                ownerPreambleOpen = false;
            }
        }
    }

    /// Comments never flush pending annotations nor close the preamble. A bare string literal
    /// statement (docstring) is trivia only while the preamble is still open; after real
    /// statements appeared it is an ordinary expression statement.
    private boolean isPreambleTrivia(@NotNull Statement statement, boolean ownerPreambleOpen) {
        if (statement instanceof CommentStatement) {
            return true;
        }
        return ownerPreambleOpen
                && statement instanceof ExpressionStatement expressionStatement
                && expressionStatement.expression() instanceof LiteralExpression literalExpression
                && literalExpression.kind().equals("string");
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

    /// A collected annotation paired with its source statement, which doubles as the diagnostic
    /// anchor when the annotation dangles at the end of a statement list.
    private record PendingAnnotation(
            @NotNull FrontendGdAnnotation annotation,
            @NotNull AnnotationStatement anchor
    ) {
    }
}
