package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.*;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendModuleSkeleton;
import dev.superice.gdcc.frontend.sema.FrontendSourceClassRelation;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.BlockScopeKind;
import dev.superice.gdcc.frontend.scope.CallableScope;
import dev.superice.gdcc.frontend.scope.CallableScopeKind;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.Scope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/// Scope analysis worker that sits between skeleton publication and future binder/body passes.
///
/// It builds the lexical scope graph documented in
/// `scope_analyzer_implementation.md`:
/// - top-level script `ClassScope` per `SourceFile`
/// - nested `ClassDeclaration` -> `ClassScope` boundaries driven by source-local skeleton relations
/// - immediate inner-class type-meta publication on each class boundary
/// - callable `CallableScope` for functions, constructors, and lambdas
/// - dedicated callable-body `BlockScope`
/// - dedicated control-flow `BlockScope`s for `if` / `elif` / `else`, `while`, `for`, and
///   `match` branches
/// - scope side-table entries for parameters, return types, default-value expressions,
///   control-flow conditions, loop iterables, and match patterns/guards
///
/// It still intentionally defers:
/// - parameter prefill, captures, and other bindings
///
/// Keeping this class separate from `frontend.scope` preserves the layering boundary between
/// protocol objects and semantic-stage orchestration.
public class FrontendScopeAnalyzer {
    /// Runs scope analysis against the shared analysis carrier.
    ///
    /// Scope analysis requires the previous skeleton stage to have already published both:
    /// - `moduleSkeleton()`
    /// - the diagnostics snapshot captured right after skeleton
    ///
    /// Once that boundary is present, the analyzer rebuilds `scopesByAst` from scratch so later
    /// stages see one stable lexical-scope side table instead of incrementally mutated leftovers.
    public void analyze(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        // Pipeline ordering matters: scope analysis starts only after skeleton facts and the
        // corresponding boundary snapshot have both become observable to later stages.
        var moduleSkeleton = analysisData.moduleSkeleton();
        analysisData.diagnostics();

        var scopesByAst = new FrontendAstSideTable<Scope>();
        new ScopeBuildingHandler(classRegistry, moduleSkeleton, scopesByAst).walk();
        analysisData.updateScopesByAst(scopesByAst);
    }

    /// Scope builder that reacts to nodes traversed by `gdparser`'s built-in `ASTWalker`.
    ///
    /// The handler keeps semantic policy local while delegating traversal mechanics to the parser
    /// library:
    /// - `SourceFile` creates one top-level `ClassScope` and publishes its direct inner classes
    /// - callables create their own `CallableScope`
    /// - callable bodies create a separate `BlockScope`
    /// - control-flow bodies create dedicated branch/loop `BlockScope`s
    ///
    /// - nested `ClassDeclaration` nodes reopen a `ClassScope` from source-local skeleton facts
    ///   and publish only their direct inner classes into the local type namespace
    ///
    /// Everything else is either:
    /// - visited under the current already-established lexical scope, or
    /// - skipped on purpose because its dedicated scope semantics are still deferred to later work.
    private static final class ScopeBuildingHandler implements ASTNodeHandler {
        private final @NotNull ClassRegistry classRegistry;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull List<SourceFile> sourceFilesInOrder;
        private final @NotNull IdentityHashMap<Node, ClassDef> classByAstOwner = new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Node, FrontendSourceClassRelation> sourceRelationByAstOwner =
                new IdentityHashMap<>();
        private final @NotNull ArrayDeque<Scope> scopeStack = new ArrayDeque<>();
        private final @NotNull ASTWalker astWalker;

        private ScopeBuildingHandler(
                @NotNull ClassRegistry classRegistry,
                @NotNull FrontendModuleSkeleton moduleSkeleton,
                @NotNull FrontendAstSideTable<Scope> scopesByAst
        ) {
            this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst");
            sourceFilesInOrder = indexClassDefsByAstOwner(
                    Objects.requireNonNull(moduleSkeleton, "moduleSkeleton")
            );
            astWalker = new ASTWalker(this);
        }

        private void walk() {
            for (var sourceFile : sourceFilesInOrder) {
                astWalker.walk(sourceFile);
            }
            if (!scopeStack.isEmpty()) {
                throw new IllegalStateException("Scope stack must be empty after AST traversal");
            }
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleNode(@NotNull Node node) {
            recordScope(node, currentScope());
            return FrontendASTTraversalDirective.CONTINUE;
        }

        /// Each parsed source unit now carries an explicit skeleton relation with exactly one
        /// top-level script class and zero or more nested `ClassDeclaration -> ClassDef` pairs.
        ///
        /// The source-wide `ClassScope` still materializes from the top-level script class, but
        /// inner classes are no longer guessed from source order or silently skipped. Their exact
        /// AST owner is indexed up front so later traversal can reopen the correct class boundary.
        @Override
        public @NotNull FrontendASTTraversalDirective handleSourceFile(@NotNull SourceFile sourceFile) {
            var sourceFileScope = new ClassScope(
                    classRegistry,
                    classRegistry,
                    requireClassDef(sourceFile)
            );
            recordScope(sourceFile, sourceFileScope);
            for (var innerRelation : requireSourceClassRelation(sourceFile).findImmediateInnerRelations(sourceFile)) {
                sourceFileScope.defineTypeMeta(innerRelation.toTypeMeta());
            }
            withCurrentScope(sourceFileScope, () -> walkChildren(sourceFile));
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration
        ) {
            visitCallableBoundary(
                    functionDeclaration,
                    functionDeclaration.parameters(),
                    functionDeclaration.returnType(),
                    functionDeclaration.body(),
                    new CallableScope(currentScope(), CallableScopeKind.FUNCTION_DECLARATION),
                    BlockScopeKind.FUNCTION_BODY
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration
        ) {
            visitCallableBoundary(
                    constructorDeclaration,
                    constructorDeclaration.parameters(),
                    constructorDeclaration.returnType(),
                    constructorDeclaration.body(),
                    new CallableScope(currentScope(), CallableScopeKind.CONSTRUCTOR_DECLARATION),
                    BlockScopeKind.CONSTRUCTOR_BODY
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(
                @NotNull LambdaExpression lambdaExpression
        ) {
            visitCallableBoundary(
                    lambdaExpression,
                    lambdaExpression.parameters(),
                    lambdaExpression.returnType(),
                    lambdaExpression.body(),
                    new CallableScope(currentScope(), CallableScopeKind.LAMBDA_EXPRESSION),
                    BlockScopeKind.LAMBDA_BODY
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleBlock(@NotNull Block node) {
            visitBlockBoundary(node, BlockScopeKind.BLOCK_STATEMENT);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            recordScope(ifStatement, currentScope());
            astWalker.walk(ifStatement.condition());
            visitBlockBoundary(ifStatement.body(), BlockScopeKind.IF_BODY);
            walkNodes(ifStatement.elifClauses());
            if (ifStatement.elseBody() != null) {
                visitBlockBoundary(ifStatement.elseBody(), BlockScopeKind.ELSE_BODY);
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleElifClause(@NotNull ElifClause elifClause) {
            recordScope(elifClause, currentScope());
            astWalker.walk(elifClause.condition());
            visitBlockBoundary(elifClause.body(), BlockScopeKind.ELIF_BODY);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            recordScope(whileStatement, currentScope());
            astWalker.walk(whileStatement.condition());
            visitBlockBoundary(whileStatement.body(), BlockScopeKind.WHILE_BODY);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            recordScope(forStatement, currentScope());
            if (forStatement.iteratorType() != null) {
                astWalker.walk(forStatement.iteratorType());
            }
            astWalker.walk(forStatement.iterable());
            visitBlockBoundary(forStatement.body(), BlockScopeKind.FOR_BODY);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            recordScope(matchStatement, currentScope());
            astWalker.walk(matchStatement.value());
            walkNodes(matchStatement.sections());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleClassDeclaration(@NotNull ClassDeclaration node) {
            var classDef = classByAstOwner.get(node);
            if (classDef == null) {
                // Skeleton build may already have rejected this subtree because its class metadata
                // could not be published. Scope build should keep the pipeline alive by skipping
                // only that subtree instead of escalating to a whole-module failure.
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }

            var classScope = new ClassScope(currentScope(), classRegistry, classDef);
            recordScope(node, classScope);
            for (var innerRelation : requireSourceClassRelation(node).findImmediateInnerRelations(node)) {
                classScope.defineTypeMeta(innerRelation.toTypeMeta());
            }

            // `ClassDeclaration.body` is a `Block` in the AST, but semantically it is only the
            // member container of the class boundary. It must reuse the enclosing `ClassScope`
            // instead of materializing an extra `BlockScope`.
            recordScope(node.body(), classScope);
            withCurrentScope(classScope, () -> walkNodes(node.body().statements()));
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchSection(@NotNull MatchSection node) {
            var branchScope = new BlockScope(currentScope(), BlockScopeKind.MATCH_SECTION_BODY);
            recordScope(node, branchScope);
            withCurrentScope(branchScope, () -> {
                walkNodes(node.patterns());
                if (node.guard() != null) {
                    astWalker.walk(node.guard());
                }
                recordScope(node.body(), branchScope);
                walkNodes(node.body().statements());
            });
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// All callable-like regions share the same two-layer shape:
        /// - callable declaration/expression -> `CallableScope`
        /// - executable body block -> dedicated `BlockScope`
        ///
        /// Parameters and return types are traversed under the callable layer, while executable
        /// statements descend under the separate body block layer. Variable bindings remain
        /// deferred; the handler only records lexical-scope facts.
        private void visitCallableBoundary(
                @NotNull Node callableOwner,
                @NotNull List<Parameter> parameters,
                @Nullable TypeRef returnType,
                @NotNull Block body,
                @NotNull CallableScope callableScope,
                @NotNull BlockScopeKind bodyKind
        ) {
            recordScope(callableOwner, callableScope);
            withCurrentScope(callableScope, () -> {
                walkNodes(parameters);
                if (returnType != null) {
                    astWalker.walk(returnType);
                }
            });

            var bodyScope = new BlockScope(callableScope, bodyKind);
            recordScope(body, bodyScope);
            withCurrentScope(bodyScope, () -> walkNodes(body.statements()));
        }

        /// Creates one lexical `BlockScope` for a block boundary that should behave as a fresh
        /// local region, then walks only the statements inside that block under the new scope.
        ///
        /// The caller is responsible for deciding when the surrounding expression context should
        /// stay in the outer scope. For example:
        /// - `if` / `elif` / `while` conditions remain in the enclosing scope
        /// - `for` iterator type and iterable remain in the enclosing scope
        /// - only the executable block body switches to the new `BlockScope`
        private void visitBlockBoundary(@NotNull Block block, @NotNull BlockScopeKind kind) {
            var blockScope = new BlockScope(currentScope(), kind);
            recordScope(block, blockScope);
            withCurrentScope(blockScope, () -> walkNodes(block.statements()));
        }

        private void withCurrentScope(@NotNull Scope scope, @NotNull Runnable action) {
            scopeStack.push(Objects.requireNonNull(scope, "scope"));
            try {
                action.run();
            } finally {
                scopeStack.pop();
            }
        }

        private void walkChildren(@NotNull Node node) {
            walkNodes(node.getChildren());
        }

        private void walkNodes(@NotNull Iterable<? extends Node> nodes) {
            for (var node : nodes) {
                astWalker.walk(node);
            }
        }

        private void recordScope(@NotNull Node astNode, @NotNull Scope scope) {
            scopesByAst.put(astNode, scope);
        }

        private @NotNull Scope currentScope() {
            var scope = scopeStack.peek();
            if (scope == null) {
                throw new IllegalStateException("Current lexical scope is unavailable during AST traversal");
            }
            return scope;
        }

        private @NotNull ClassDef requireClassDef(@NotNull Node astOwner) {
            var classDef = classByAstOwner.get(astOwner);
            if (classDef == null) {
                throw new IllegalStateException(
                        "No class skeleton was indexed for "
                                + astOwner.getClass().getSimpleName()
                                + "@"
                                + System.identityHashCode(astOwner)
                );
            }
            return classDef;
        }

        private @NotNull FrontendSourceClassRelation requireSourceClassRelation(@NotNull Node astOwner) {
            var sourceClassRelation = sourceRelationByAstOwner.get(astOwner);
            if (sourceClassRelation == null) {
                throw new IllegalStateException(
                        "No source class relation was indexed for "
                                + astOwner.getClass().getSimpleName()
                                + "@"
                                + System.identityHashCode(astOwner)
                );
            }
            return sourceClassRelation;
        }

        private @NotNull List<SourceFile> indexClassDefsByAstOwner(
                @NotNull FrontendModuleSkeleton moduleSkeleton
        ) {
            var sourceClassRelations = moduleSkeleton.sourceClassRelations();
            var sourceFiles = new ArrayList<SourceFile>(sourceClassRelations.size());
            for (var sourceClassRelation : sourceClassRelations) {
                var sourceFile = sourceClassRelation.unit().ast();
                sourceFiles.add(sourceFile);
                indexClassOwner(sourceFile, sourceClassRelation.topLevelClassDef());
                indexSourceClassRelation(sourceFile, sourceClassRelation);
                for (var innerClassRelation : sourceClassRelation.innerClassRelations()) {
                    indexClassOwner(innerClassRelation.declaration(), innerClassRelation.classDef());
                    indexSourceClassRelation(innerClassRelation.declaration(), sourceClassRelation);
                }
            }
            return List.copyOf(sourceFiles);
        }

        private void indexClassOwner(@NotNull Node astOwner, @NotNull ClassDef classDef) {
            var previous = classByAstOwner.put(astOwner, classDef);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate AST class owner encountered while indexing class skeletons"
                );
            }
        }

        private void indexSourceClassRelation(
                @NotNull Node astOwner,
                @NotNull FrontendSourceClassRelation sourceClassRelation
        ) {
            var previous = sourceRelationByAstOwner.put(astOwner, sourceClassRelation);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate source relation owner encountered while indexing class skeletons"
                );
            }
        }
    }
}
