package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FrontendASTTraversalDirective;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendBodyDeclarationIndex;
import gd.script.gdcc.frontend.sema.FrontendBodyLocalDeclaration;
import gd.script.gdcc.frontend.sema.FrontendExecutableInventorySupport;
import gd.script.gdcc.frontend.sema.FrontendInterfaceSurface;
import gd.script.gdcc.frontend.sema.FrontendMatchSupport;
import gd.script.gdcc.frontend.sema.FrontendSuiteEntryRoots;
import gd.script.gdcc.frontend.sema.FrontendTypedLexicalBaseline;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.Scope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Builds the Interface-layer surface consumed by the future body `SuiteResolver`.
///
/// This phase is deliberately read-only with respect to stable semantic side tables. It consumes the
/// already-published skeleton, scope graph, and variable inventory, then produces a separate surface
/// describing supported body entries, declaration source order, and typed baseline slots.
public class FrontendInterfacePhase {
    public @NotNull FrontendInterfaceSurface analyze(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        Objects.requireNonNull(analysisData, "analysisData must not be null");

        var moduleSkeleton = analysisData.moduleSkeleton();
        analysisData.diagnostics();
        var builder = new InterfaceSurfaceBuilder(analysisData.scopesByAst());
        for (var relation : moduleSkeleton.sourceClassRelations()) {
            builder.walk(relation.unit().path(), relation.unit().ast());
        }
        return builder.build();
    }

    private static final class InterfaceSurfaceBuilder implements ASTNodeHandler {
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull Map<Node, List<FrontendBodyLocalDeclaration>> declarationsByBodyRoot =
                new IdentityHashMap<>();
        private final @NotNull Map<Node, Path> sourcePathsByEntryRoot = new IdentityHashMap<>();
        private final @NotNull FrontendTypedLexicalBaseline.Builder typedBaselineBuilder =
                FrontendTypedLexicalBaseline.builder();
        private final @NotNull List<Node> callableOwners = new ArrayList<>();
        private final @NotNull List<VariableDeclaration> propertyInitializers = new ArrayList<>();
        private final @NotNull List<Block> supportedBlocks = new ArrayList<>();
        private @NotNull List<FrontendBodyLocalDeclaration> currentBodyDeclarations = List.of();
        private @NotNull Path currentSourcePath = Path.of("");
        private int supportedBodyDepth;

        private InterfaceSurfaceBuilder(@NotNull FrontendAstSideTable<Scope> scopesByAst) {
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst");
            astWalker = new ASTWalker(this);
        }

        private void walk(@NotNull Path sourcePath, @NotNull SourceFile sourceFile) {
            currentSourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            astWalker.walk(sourceFile);
        }

        private @NotNull FrontendInterfaceSurface build() {
            return new FrontendInterfaceSurface(
                    new FrontendBodyDeclarationIndex(declarationsByBodyRoot),
                    typedBaselineBuilder.build(),
                    new FrontendSuiteEntryRoots(
                            callableOwners,
                            propertyInitializers,
                            supportedBlocks,
                            sourcePathsByEntryRoot
                    )
            );
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleNode(@NotNull Node node) {
            return FrontendASTTraversalDirective.CONTINUE;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleSourceFile(@NotNull SourceFile sourceFile) {
            walkStatements(sourceFile.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleClassDeclaration(@NotNull ClassDeclaration classDeclaration) {
            if (isNotPublished(classDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkStatements(classDeclaration.body().statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration
        ) {
            // A statement-position `func ...` is a bare lambda statement, not a class member
            // (Godot parses the same shape as a lambda expression statement and rejects it as an
            // inaccessible standalone lambda): it never gets a class skeleton, so it must not
            // become a suite-entry callable owner. The subtree stays unpublished here and the
            // enclosing body resolution owns the boundary diagnostic.
            if (supportedBodyDepth > 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            recordCallable(functionDeclaration, functionDeclaration.parameters(), functionDeclaration.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration
        ) {
            // Same statement-position boundary as `handleFunctionDeclaration`: a nested `_init`
            // definition is a bare lambda statement, never a class constructor.
            if (supportedBodyDepth > 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            recordCallable(constructorDeclaration, constructorDeclaration.parameters(), constructorDeclaration.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            // Lambdas inside supported executable bodies become suite entries of their own; class
            // level (property initializer) lambdas stay unpublished and fail-closed downstream.
            if (supportedBodyDepth > 0) {
                recordCallable(lambdaExpression, lambdaExpression.parameters(), lambdaExpression.body());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleBlock(@NotNull Block block) {
            if (supportedBodyDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            enterSupportedBlock(block);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            if (supportedBodyDepth <= 0 || isNotPublished(ifStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            astWalker.walk(ifStatement.condition());
            enterSupportedBlock(ifStatement.body());
            for (var elifClause : ifStatement.elifClauses()) {
                astWalker.walk(elifClause);
            }
            if (ifStatement.elseBody() != null) {
                enterSupportedBlock(ifStatement.elseBody());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleElifClause(@NotNull ElifClause elifClause) {
            if (supportedBodyDepth <= 0 || isNotPublished(elifClause)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            astWalker.walk(elifClause.condition());
            enterSupportedBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            if (supportedBodyDepth <= 0 || isNotPublished(whileStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            astWalker.walk(whileStatement.condition());
            enterSupportedBlock(whileStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            if (supportedBodyDepth <= 0 || isNotPublished(forStatement.body())) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (forStatement.iteratorType() != null) {
                astWalker.walk(forStatement.iteratorType());
            }
            astWalker.walk(forStatement.iterable());
            enterSupportedBlock(forStatement.body(), forStatement);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            if (supportedBodyDepth <= 0 || isNotPublished(matchStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            astWalker.walk(matchStatement.value());
            for (var section : matchStatement.sections()) {
                enterMatchSectionBody(section);
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleVariableDeclaration(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            if (supportedBodyDepth <= 0) {
                recordPropertyInitializer(variableDeclaration);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (variableDeclaration.kind() == DeclarationKind.CONST) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (variableDeclaration.kind() == DeclarationKind.VAR) {
                recordLocalDeclaration(variableDeclaration);
                if (variableDeclaration.value() != null) {
                    astWalker.walk(variableDeclaration.value());
                }
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        private void recordCallable(
                @NotNull Node callableOwner,
                @NotNull List<Parameter> parameters,
                @NotNull Block body
        ) {
            if (isNotPublished(callableOwner)) {
                return;
            }
            callableOwners.add(callableOwner);
            sourcePathsByEntryRoot.put(callableOwner, currentSourcePath);
            recordParameterBaselines(parameters);
            enterSupportedBlock(body);
        }

        private void recordParameterBaselines(@NotNull List<Parameter> parameters) {
            for (var parameter : parameters) {
                var scope = scopesByAst.get(parameter);
                if (!(scope instanceof CallableScope callableScope)) {
                    continue;
                }
                var binding = callableScope.resolveValueHere(parameter.name().trim());
                if (binding != null && binding.declaration() == parameter) {
                    typedBaselineBuilder.put(parameter, binding.type());
                }
            }
        }

        private void recordPropertyInitializer(@NotNull VariableDeclaration variableDeclaration) {
            if (variableDeclaration.value() == null) {
                return;
            }
            propertyInitializers.add(variableDeclaration);
            sourcePathsByEntryRoot.put(variableDeclaration, currentSourcePath);
            astWalker.walk(variableDeclaration.value());
        }

        private void enterSupportedBlock(@NotNull Block block) {
            enterSupportedBlock(block, null);
        }

        /// Opens one supported body inventory list.
        ///
        /// For a `for` body, the iterator is published first as a synthetic 0th item at `sourceOrder == 0`
        /// before walking body statements, so ordinary locals receive contiguous `sourceOrder >= 1`.
        /// Match section bodies use a dedicated overload that inserts `PATTERN_BIND` entries first
        /// rather than reusing the `ForStatement` owner parameter. That shape is part of the
        /// Interface surface contract certified by [FrontendBodyStructuralCompleteness].
        private void enterSupportedBlock(@NotNull Block block, @Nullable ForStatement ownerFor) {
            var scope = scopesByAst.get(block);
            if (!(scope instanceof BlockScope blockScope)
                    || !FrontendExecutableInventorySupport.isSupportedSuiteBodyRoot(blockScope.kind())) {
                return;
            }
            supportedBlocks.add(block);
            sourcePathsByEntryRoot.put(block, currentSourcePath);
            var previousDeclarations = currentBodyDeclarations;
            var bodyDeclarations = new ArrayList<FrontendBodyLocalDeclaration>();
            currentBodyDeclarations = bodyDeclarations;
            supportedBodyDepth++;
            try {
                if (ownerFor != null) {
                    recordIteratorDeclaration(ownerFor, blockScope);
                }
                walkStatements(block.statements());
            } finally {
                supportedBodyDepth--;
                declarationsByBodyRoot.put(block, List.copyOf(bodyDeclarations));
                currentBodyDeclarations = previousDeclarations;
            }
        }

        private void recordLocalDeclaration(@NotNull VariableDeclaration variableDeclaration) {
            var scope = scopesByAst.get(variableDeclaration);
            if (!(scope instanceof BlockScope blockScope)
                    || !FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(blockScope.kind())) {
                return;
            }
            var binding = blockScope.resolveValueHere(variableDeclaration.name().trim());
            if (binding == null || binding.declaration() != variableDeclaration) {
                return;
            }
            // When this body is a FOR_BODY, index 0 is already occupied by the iterator entry.
            currentBodyDeclarations.add(new FrontendBodyLocalDeclaration(
                    variableDeclaration,
                    binding,
                    FrontendBodyLocalDeclaration.Kind.ORDINARY_VAR,
                    currentBodyDeclarations.size()
            ));
            typedBaselineBuilder.put(variableDeclaration, binding.type());
        }

        /// Publishes the sole for-body iterator inventory entry as the synthetic 0th item at list head with
        /// `sourceOrder == 0`.
        private void recordIteratorDeclaration(
                @NotNull ForStatement forStatement,
                @NotNull BlockScope blockScope
        ) {
            var binding = blockScope.resolveValueHere(forStatement.iterator().trim());
            if (binding == null || binding.declaration() != forStatement) {
                return;
            }
            currentBodyDeclarations.add(new FrontendBodyLocalDeclaration(
                    forStatement,
                    binding,
                    FrontendBodyLocalDeclaration.Kind.ITERATOR,
                    0
            ));
            typedBaselineBuilder.put(forStatement, binding.type());
        }

        /// Opens a match section body: records every pattern bind in source order as a contiguous
        /// `PATTERN_BIND` prefix, then walks body statements so ordinary locals receive
        /// `sourceOrder >= k`.
        private void enterMatchSectionBody(@NotNull MatchSection section) {
            var block = section.body();
            var scope = scopesByAst.get(block);
            if (!(scope instanceof BlockScope blockScope)
                    || !FrontendExecutableInventorySupport.isSupportedSuiteBodyRoot(blockScope.kind())) {
                return;
            }
            supportedBlocks.add(block);
            sourcePathsByEntryRoot.put(block, currentSourcePath);
            var previousDeclarations = currentBodyDeclarations;
            var bodyDeclarations = new ArrayList<FrontendBodyLocalDeclaration>();
            currentBodyDeclarations = bodyDeclarations;
            supportedBodyDepth++;
            try {
                recordMatchPatternBindDeclarations(section, blockScope);
                for (var pattern : section.patterns()) {
                    astWalker.walk(pattern);
                }
                if (section.guard() != null) {
                    astWalker.walk(section.guard());
                }
                walkStatements(block.statements());
            } finally {
                supportedBodyDepth--;
                declarationsByBodyRoot.put(block, List.copyOf(bodyDeclarations));
                currentBodyDeclarations = previousDeclarations;
            }
        }

        private void recordMatchPatternBindDeclarations(
                @NotNull MatchSection section,
                @NotNull BlockScope blockScope
        ) {
            for (var pattern : section.patterns()) {
                for (var bindingPlan : FrontendMatchSupport.collectPatternBindings(pattern, true)) {
                    recordPatternBindDeclaration(bindingPlan.declaration(), blockScope);
                }
            }
        }

        private void recordPatternBindDeclaration(
                @NotNull PatternBindingExpression patternBinding,
                @NotNull BlockScope blockScope
        ) {
            var binding = blockScope.resolveValueHere(patternBinding.name());
            if (binding == null || binding.declaration() != patternBinding) {
                return;
            }
            currentBodyDeclarations.add(new FrontendBodyLocalDeclaration(
                    patternBinding,
                    binding,
                    FrontendBodyLocalDeclaration.Kind.PATTERN_BIND,
                    currentBodyDeclarations.size()
            ));
            typedBaselineBuilder.put(patternBinding, binding.type());
        }

        private void walkStatements(@NotNull List<Statement> statements) {
            for (var statement : statements) {
                astWalker.walk(statement);
            }
        }

        private boolean isNotPublished(@NotNull Node node) {
            return !scopesByAst.containsKey(node);
        }
    }
}
