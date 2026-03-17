package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.resolver.FrontendVisibleValueDeferredBoundary;
import dev.superice.gdcc.frontend.sema.resolver.FrontendVisibleValueDeferredReason;
import dev.superice.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import dev.superice.gdcc.frontend.sema.resolver.FrontendVisibleValueResolveRequest;
import dev.superice.gdcc.frontend.sema.resolver.FrontendVisibleValueResolution;
import dev.superice.gdcc.frontend.sema.resolver.FrontendVisibleValueResolver;
import dev.superice.gdcc.frontend.sema.resolver.FrontendVisibleValueStatus;
import dev.superice.gdcc.gdextension.ExtensionUtilityFunction;
import dev.superice.gdcc.scope.FunctionDef;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeTypeMeta;
import dev.superice.gdcc.scope.ScopeTypeMetaKind;
import dev.superice.gdcc.scope.ScopeValue;
import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FrontendASTTraversalDirective;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Rebuilds frontend top-level symbol bindings from published skeleton, scope, and visible-value
/// facts.
///
/// The analyzer publishes only `symbolBindings()`. Member facts, call facts, and expression types
/// remain untouched here.
public class FrontendTopBindingAnalyzer {
    private static final @NotNull String BINDING_CATEGORY = "sema.binding";
    private static final @NotNull String UNSUPPORTED_BINDING_SUBTREE_CATEGORY =
            "sema.unsupported_binding_subtree";

    /// Runs top-binding analysis and refreshes `symbolBindings()` from scratch.
    public void analyze(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var moduleSkeleton = analysisData.moduleSkeleton();
        analysisData.diagnostics();

        var scopesByAst = analysisData.scopesByAst();
        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            var sourceFile = sourceClassRelation.unit().ast();
            if (!scopesByAst.containsKey(sourceFile)) {
                throw new IllegalStateException(
                        "Scope graph has not been published for source file: " + sourceClassRelation.unit().path()
                );
            }
        }

        var visibleValueResolver = new FrontendVisibleValueResolver(analysisData);
        var symbolBindings = new FrontendAstSideTable<FrontendBinding>();
        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            new AstWalkerTopBindingBinder(
                    sourceClassRelation.unit().path(),
                    scopesByAst,
                    symbolBindings,
                    diagnosticManager,
                    visibleValueResolver
            ).walk(sourceClassRelation.unit().ast());
        }
        analysisData.updateSymbolBindings(symbolBindings);
    }

    private enum ExpressionPosition {
        VALUE,
        BARE_CALLEE,
        TOP_LEVEL_TYPE_META_CANDIDATE
    }

    /// `ASTWalker` remains the typed dispatch engine, while this handler keeps subtree gating and
    /// namespace routing local to top-binding analysis.
    private static final class AstWalkerTopBindingBinder implements ASTNodeHandler {
        private final @NotNull Path sourcePath;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull FrontendVisibleValueResolver visibleValueResolver;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull IdentityHashMap<Node, Boolean> reportedUnsupportedRoots = new IdentityHashMap<>();
        private int supportedExecutableBlockDepth;
        private @NotNull ResolveRestriction currentRestriction = ResolveRestriction.unrestricted();
        private boolean currentStaticContext;

        private AstWalkerTopBindingBinder(
                @NotNull Path sourcePath,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
                @NotNull DiagnosticManager diagnosticManager,
                @NotNull FrontendVisibleValueResolver visibleValueResolver
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            this.symbolBindings = Objects.requireNonNull(symbolBindings, "symbolBindings must not be null");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            this.visibleValueResolver = Objects.requireNonNull(
                    visibleValueResolver,
                    "visibleValueResolver must not be null"
            );
            astWalker = new ASTWalker(this);
        }

        private void walk(@NotNull SourceFile sourceFile) {
            astWalker.walk(sourceFile);
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleNode(@NotNull Node node) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleSourceFile(@NotNull SourceFile sourceFile) {
            walkNonExecutableContainerStatements(sourceFile.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleClassDeclaration(@NotNull ClassDeclaration classDeclaration) {
            if (isNotPublished(classDeclaration)) {
                reportSkippedSubtree(classDeclaration);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkNonExecutableContainerStatements(classDeclaration.body().statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration
        ) {
            if (isNotPublished(functionDeclaration)) {
                reportSkippedSubtree(functionDeclaration);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            reportDeferredParameterDefaults(functionDeclaration.parameters());
            walkCallableBody(
                    functionDeclaration,
                    functionDeclaration.body(),
                    functionDeclaration.isStatic()
                            ? ResolveRestriction.staticContext()
                            : ResolveRestriction.instanceContext(),
                    functionDeclaration.isStatic()
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration
        ) {
            if (isNotPublished(constructorDeclaration)) {
                reportSkippedSubtree(constructorDeclaration);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            reportDeferredParameterDefaults(constructorDeclaration.parameters());
            walkCallableBody(
                    constructorDeclaration,
                    constructorDeclaration.body(),
                    ResolveRestriction.instanceContext(),
                    false
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleBlock(@NotNull Block block) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (isNotPublished(block)) {
                reportSkippedSubtree(block);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkStatements(block.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleExpressionStatement(
                @NotNull ExpressionStatement expressionStatement
        ) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(expressionStatement.expression());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleReturnStatement(@NotNull ReturnStatement returnStatement) {
            if (supportedExecutableBlockDepth <= 0 || returnStatement.value() == null) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(returnStatement.value());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleAssertStatement(@NotNull AssertStatement assertStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(assertStatement.condition());
            if (assertStatement.message() != null) {
                walkValueExpression(assertStatement.message());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleVariableDeclaration(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            if (supportedExecutableBlockDepth <= 0 || variableDeclaration.value() == null) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (variableDeclaration.kind() == DeclarationKind.CONST) {
                reportDeferredSubtree(variableDeclaration.value(), FrontendVisibleValueDomain.BLOCK_LOCAL_CONST_SUBTREE);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (variableDeclaration.kind() != DeclarationKind.VAR) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(variableDeclaration.value());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (isNotPublished(ifStatement)) {
                reportSkippedSubtree(ifStatement);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(ifStatement.condition());
            walkSupportedExecutableBlock(ifStatement.body());
            for (var elifClause : ifStatement.elifClauses()) {
                astWalker.walk(elifClause);
            }
            if (ifStatement.elseBody() != null) {
                walkSupportedExecutableBlock(ifStatement.elseBody());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleElifClause(@NotNull ElifClause elifClause) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (isNotPublished(elifClause)) {
                reportSkippedSubtree(elifClause);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(elifClause.condition());
            walkSupportedExecutableBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (isNotPublished(whileStatement)) {
                reportSkippedSubtree(whileStatement);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(whileStatement.condition());
            walkSupportedExecutableBlock(whileStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (isNotPublished(forStatement)) {
                reportSkippedSubtree(forStatement);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            reportDeferredSubtree(forStatement, FrontendVisibleValueDomain.FOR_SUBTREE);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (isNotPublished(matchStatement)) {
                reportSkippedSubtree(matchStatement);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkValueExpression(matchStatement.value());
            if (!matchStatement.sections().isEmpty()) {
                reportDeferredSubtree(matchStatement, FrontendVisibleValueDomain.MATCH_SUBTREE);
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        private void walkCallableBody(
                @NotNull Node callableOwner,
                @Nullable Block body,
                @NotNull ResolveRestriction restriction,
                boolean staticContext
        ) {
            if (isNotPublished(callableOwner)) {
                reportSkippedSubtree(callableOwner);
                return;
            }
            if (isNotPublished(body)) {
                reportSkippedSubtree(body);
                return;
            }
            var previousRestriction = currentRestriction;
            var previousStaticContext = currentStaticContext;
            currentRestriction = Objects.requireNonNull(restriction, "restriction must not be null");
            currentStaticContext = staticContext;
            try {
                walkSupportedExecutableBlock(body);
            } finally {
                currentRestriction = previousRestriction;
                currentStaticContext = previousStaticContext;
            }
        }

        private void walkStatements(@NotNull List<Statement> statements) {
            for (var statement : statements) {
                astWalker.walk(statement);
            }
        }

        private void walkNonExecutableContainerStatements(@NotNull List<Statement> statements) {
            var previousDepth = supportedExecutableBlockDepth;
            supportedExecutableBlockDepth = 0;
            try {
                walkStatements(statements);
            } finally {
                supportedExecutableBlockDepth = previousDepth;
            }
        }

        private void walkSupportedExecutableBlock(@Nullable Block block) {
            if (isNotPublished(block)) {
                reportSkippedSubtree(block);
                return;
            }
            supportedExecutableBlockDepth++;
            try {
                astWalker.walk(block);
            } finally {
                supportedExecutableBlockDepth--;
            }
        }

        private void walkValueExpression(@NotNull Expression expression) {
            walkExpression(expression, ExpressionPosition.VALUE);
        }

        private void walkExpression(
                @Nullable Expression expression,
                @NotNull ExpressionPosition position
        ) {
            if (expression == null) {
                return;
            }
            switch (expression) {
                case IdentifierExpression identifierExpression -> visitIdentifier(identifierExpression, position);
                case SelfExpression selfExpression -> visitSelf(selfExpression);
                case LiteralExpression literalExpression -> visitLiteral(literalExpression);
                case AssignmentExpression assignmentExpression -> walkAssignmentExpression(assignmentExpression);
                case AttributeExpression attributeExpression -> walkAttributeExpression(attributeExpression);
                case SubscriptExpression subscriptExpression -> walkSubscriptExpression(subscriptExpression);
                case CallExpression callExpression -> walkCallExpression(callExpression);
                case LambdaExpression lambdaExpression -> reportDeferredSubtree(
                        lambdaExpression,
                        FrontendVisibleValueDomain.LAMBDA_SUBTREE
                );
                default -> walkGenericExpressionChildren(expression);
            }
        }

        /// `FrontendBinding` does not encode usage semantics, so assignment chain heads are
        /// published through the same binding table as ordinary reads.
        private void walkAssignmentExpression(@NotNull AssignmentExpression assignmentExpression) {
            walkValueExpression(assignmentExpression.left());
            walkValueExpression(assignmentExpression.right());
        }

        private void walkAttributeExpression(@NotNull AttributeExpression attributeExpression) {
            // Only the outermost chain head is bound here, but arguments nested inside
            // attribute-call/subscript steps still belong to the executable expression tree.
            walkChainHeadBaseExpression(attributeExpression.base());
            for (var step : attributeExpression.steps()) {
                switch (step) {
                    case AttributeCallStep attributeCallStep -> walkExpressionList(attributeCallStep.arguments());
                    case AttributeSubscriptStep attributeSubscriptStep ->
                            walkExpressionList(attributeSubscriptStep.arguments());
                    default -> {
                    }
                }
            }
        }

        private void walkSubscriptExpression(@NotNull SubscriptExpression subscriptExpression) {
            walkValueExpression(subscriptExpression.base());
            for (var argument : subscriptExpression.arguments()) {
                walkValueExpression(argument);
            }
        }

        private void walkCallExpression(@NotNull CallExpression callExpression) {
            switch (callExpression.callee()) {
                case IdentifierExpression identifierExpression ->
                        visitIdentifier(identifierExpression, ExpressionPosition.BARE_CALLEE);
                case AttributeExpression attributeExpression -> walkAttributeExpression(attributeExpression);
                default -> walkValueExpression(callExpression.callee());
            }
            walkExpressionList(callExpression.arguments());
        }

        private void walkChainHeadBaseExpression(@NotNull Expression expression) {
            switch (expression) {
                case IdentifierExpression identifierExpression ->
                        visitIdentifier(identifierExpression, ExpressionPosition.TOP_LEVEL_TYPE_META_CANDIDATE);
                case SelfExpression selfExpression -> visitSelf(selfExpression);
                case LiteralExpression literalExpression -> visitLiteral(literalExpression);
                case AttributeExpression attributeExpression -> walkChainHeadBaseExpression(attributeExpression.base());
                default -> walkValueExpression(expression);
            }
        }

        private void walkGenericExpressionChildren(@NotNull Expression expression) {
            walkNestedExpressionChildren(expression);
        }

        private void walkExpressionList(@NotNull List<? extends Expression> expressions) {
            for (var expression : expressions) {
                walkValueExpression(expression);
            }
        }

        /// Some expressions, such as `DictionaryExpression`, wrap their real expression payload in
        /// intermediate non-expression nodes (`DictEntry`). Generic traversal therefore needs to
        /// descend through those containers until it reaches nested expressions.
        private void walkNestedExpressionChildren(@NotNull Node node) {
            for (var child : node.getChildren()) {
                if (child instanceof Expression childExpression) {
                    walkValueExpression(childExpression);
                    continue;
                }
                walkNestedExpressionChildren(child);
            }
        }

        private void reportDeferredParameterDefaults(@NotNull List<Parameter> parameters) {
            for (var parameter : parameters) {
                if (parameter.defaultValue() != null) {
                    reportDeferredSubtree(parameter.defaultValue(), FrontendVisibleValueDomain.PARAMETER_DEFAULT);
                }
            }
        }

        private void visitIdentifier(
                @NotNull IdentifierExpression identifierExpression,
                @NotNull ExpressionPosition position
        ) {
            Objects.requireNonNull(identifierExpression, "identifierExpression must not be null");
            switch (Objects.requireNonNull(position, "position must not be null")) {
                case VALUE -> bindValueIdentifier(identifierExpression);
                case TOP_LEVEL_TYPE_META_CANDIDATE -> bindTopLevelTypeMetaCandidate(identifierExpression);
                case BARE_CALLEE -> bindBareCalleeIdentifier(identifierExpression);
            }
        }

        private void visitSelf(@NotNull SelfExpression selfExpression) {
            publishBinding(selfExpression, "self", FrontendBindingKind.SELF, null);
            if (currentStaticContext) {
                reportBindingError(
                        selfExpression,
                        "Keyword 'self' is not available in static context"
                );
            }
        }

        private void visitLiteral(@NotNull LiteralExpression literalExpression) {
            publishBinding(
                    literalExpression,
                    literalExpression.sourceText(),
                    FrontendBindingKind.LITERAL,
                    null
            );
        }

        private void bindValueIdentifier(@NotNull IdentifierExpression identifierExpression) {
            publishValueResolution(identifierExpression, resolveVisibleValue(identifierExpression));
        }

        private @NotNull FrontendVisibleValueResolution resolveVisibleValue(
                @NotNull IdentifierExpression identifierExpression
        ) {
            return visibleValueResolver.resolve(new FrontendVisibleValueResolveRequest(
                    identifierExpression.name(),
                    identifierExpression,
                    currentRestriction,
                    FrontendVisibleValueDomain.EXECUTABLE_BODY
            ));
        }

        private void publishValueResolution(
                @NotNull IdentifierExpression identifierExpression,
                @NotNull FrontendVisibleValueResolution resolution
        ) {
            switch (resolution.status()) {
                case FOUND_ALLOWED -> publishScopeValueBinding(identifierExpression, resolution.visibleValue());
                case FOUND_BLOCKED -> {
                    publishScopeValueBinding(identifierExpression, resolution.visibleValue());
                    reportBindingError(
                            identifierExpression,
                            "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
                    );
                }
                case NOT_FOUND -> {
                    publishBinding(identifierExpression, identifierExpression.name(), FrontendBindingKind.UNKNOWN, null);
                    reportBindingError(
                            identifierExpression,
                            "Unable to resolve value binding '" + identifierExpression.name() + "'"
                    );
                }
                case DEFERRED_UNSUPPORTED -> reportDeferredUnsupported(
                        identifierExpression,
                        identifierExpression.name(),
                        resolution.deferredBoundary()
                );
            }
        }

        private void bindTopLevelTypeMetaCandidate(@NotNull IdentifierExpression identifierExpression) {
            var currentScope = findCurrentScope(identifierExpression);
            if (currentScope == null) {
                reportMissingScopeUnsupported(identifierExpression, identifierExpression.name());
                return;
            }

            var valueResolution = resolveVisibleValue(identifierExpression);
            if (valueResolution.status() == FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED) {
                reportDeferredUnsupported(
                        identifierExpression,
                        identifierExpression.name(),
                        valueResolution.deferredBoundary()
                );
                return;
            }

            var typeMetaResult = currentScope.resolveTypeMeta(identifierExpression.name(), currentRestriction);
            if (shouldPreferGlobalEnumTypeMeta(valueResolution, typeMetaResult)) {
                var typeMeta = typeMetaResult.requireValue();
                publishBinding(
                        identifierExpression,
                        identifierExpression.name(),
                        FrontendBindingKind.TYPE_META,
                        typeMeta.declaration()
                );
                return;
            }
            if (valueResolution.status() == FrontendVisibleValueStatus.FOUND_ALLOWED
                    || valueResolution.status() == FrontendVisibleValueStatus.FOUND_BLOCKED) {
                publishValueResolution(identifierExpression, valueResolution);
                reportLocalTypeMetaShadowing(identifierExpression, valueResolution.visibleValue(), typeMetaResult);
                return;
            }

            if (typeMetaResult.isAllowed()) {
                var typeMeta = typeMetaResult.requireValue();
                if (supportsTopLevelTypeMeta(typeMeta)) {
                    publishBinding(
                            identifierExpression,
                            identifierExpression.name(),
                            FrontendBindingKind.TYPE_META,
                            typeMeta.declaration()
                    );
                } else {
                    reportBindingError(
                            identifierExpression,
                            "Top-level type-meta binding for '" + identifierExpression.name()
                                    + "' currently supports class-like types, builtin static receivers, and global enums"
                    );
                }
                return;
            }

            publishValueResolution(identifierExpression, valueResolution);
        }

        private void reportLocalTypeMetaShadowing(
                @NotNull IdentifierExpression identifierExpression,
                @Nullable ScopeValue visibleValue,
                @NotNull dev.superice.gdcc.scope.ScopeLookupResult<ScopeTypeMeta> typeMetaResult
        ) {
            var resolvedValue = Objects.requireNonNull(visibleValue, "visibleValue must not be null");
            if (!isLocalLikeShadowingValue(resolvedValue)) {
                return;
            }
            if (!typeMetaResult.isAllowed()) {
                return;
            }
            var typeMeta = typeMetaResult.requireValue();
            if (!supportsTopLevelTypeMeta(typeMeta)) {
                return;
            }
            reportBindingError(
                    identifierExpression,
                    "Explicit receiver chain head '" + identifierExpression.name()
                            + "' resolves to a local value and shadows a visible type-meta candidate"
            );
        }

        private boolean isLocalLikeShadowingValue(@NotNull ScopeValue scopeValue) {
            return switch (scopeValue.kind()) {
                case LOCAL, PARAMETER, CAPTURE -> true;
                default -> false;
            };
        }

        private void bindBareCalleeIdentifier(@NotNull IdentifierExpression identifierExpression) {
            var currentScope = findCurrentScope(identifierExpression);
            if (currentScope == null) {
                reportMissingScopeUnsupported(identifierExpression, identifierExpression.name());
                return;
            }

            var functionResult = currentScope.resolveFunctions(identifierExpression.name(), currentRestriction);
            switch (functionResult.status()) {
                case FOUND_ALLOWED ->
                        publishFunctionBinding(identifierExpression, functionResult.requireValue(), false);
                case FOUND_BLOCKED -> publishFunctionBinding(identifierExpression, functionResult.requireValue(), true);
                case NOT_FOUND -> {
                    publishBinding(identifierExpression, identifierExpression.name(), FrontendBindingKind.UNKNOWN, null);
                    reportBindingError(
                            identifierExpression,
                            "Unable to resolve bare callee binding '" + identifierExpression.name() + "'"
                    );
                }
            }
        }

        /// Bare-callee binding consumes the nearest overload set chosen by
        /// `Scope.resolveFunctions(...)` and classifies only its symbol category.
        private void publishFunctionBinding(
                @NotNull IdentifierExpression identifierExpression,
                @NotNull List<FunctionDef> overloadSet,
                boolean blocked
        ) {
            var bindingKind = classifyFunctionBindingKind(identifierExpression, overloadSet);
            if (bindingKind == null) {
                return;
            }
            publishBinding(
                    identifierExpression,
                    identifierExpression.name(),
                    bindingKind,
                    List.copyOf(overloadSet)
            );
            if (blocked) {
                reportBindingError(
                        identifierExpression,
                        "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
                );
            }
        }

        private @Nullable FrontendBindingKind classifyFunctionBindingKind(
                @NotNull IdentifierExpression identifierExpression,
                @NotNull List<FunctionDef> overloadSet
        ) {
            var survivingOverloads = List.copyOf(Objects.requireNonNull(overloadSet, "overloadSet must not be null"));
            if (survivingOverloads.isEmpty()) {
                reportBindingError(
                        identifierExpression,
                        "Bare callee binding '" + identifierExpression.name() + "' resolved to an empty overload set"
                );
                return null;
            }

            var allUtilityFunctions = survivingOverloads.stream().allMatch(ExtensionUtilityFunction.class::isInstance);
            if (allUtilityFunctions) {
                return FrontendBindingKind.UTILITY_FUNCTION;
            }
            var anyUtilityFunction = survivingOverloads.stream().anyMatch(ExtensionUtilityFunction.class::isInstance);
            if (anyUtilityFunction) {
                reportBindingError(
                        identifierExpression,
                        "Bare callee binding '" + identifierExpression.name()
                                + "' produced a mixed utility/member overload set"
                );
                return null;
            }

            var allStatic = survivingOverloads.stream().allMatch(FunctionDef::isStatic);
            if (allStatic) {
                return FrontendBindingKind.STATIC_METHOD;
            }
            var anyStatic = survivingOverloads.stream().anyMatch(FunctionDef::isStatic);
            if (anyStatic) {
                reportBindingError(
                        identifierExpression,
                        "Bare callee binding '" + identifierExpression.name()
                                + "' produced a mixed static/non-static overload set"
                );
                return null;
            }
            return FrontendBindingKind.METHOD;
        }

        private void publishScopeValueBinding(
                @NotNull IdentifierExpression identifierExpression,
                @Nullable ScopeValue scopeValue
        ) {
            var resolvedValue = Objects.requireNonNull(scopeValue, "scopeValue must not be null");
            publishBinding(
                    identifierExpression,
                    identifierExpression.name(),
                    toBindingKind(resolvedValue.kind()),
                    resolvedValue.declaration()
            );
        }

        private boolean supportsTopLevelTypeMeta(@NotNull ScopeTypeMeta typeMeta) {
            return switch (typeMeta.kind()) {
                case GDCC_CLASS, ENGINE_CLASS, BUILTIN -> !typeMeta.pseudoType();
                case GLOBAL_ENUM -> typeMeta.declaration() != null;
            };
        }

        private boolean shouldPreferGlobalEnumTypeMeta(
                @NotNull FrontendVisibleValueResolution valueResolution,
                @NotNull dev.superice.gdcc.scope.ScopeLookupResult<ScopeTypeMeta> typeMetaResult
        ) {
            if (valueResolution.status() != FrontendVisibleValueStatus.FOUND_ALLOWED) {
                return false;
            }
            var visibleValue = valueResolution.visibleValue();
            if (visibleValue == null || visibleValue.kind() != ScopeValueKind.GLOBAL_ENUM) {
                return false;
            }
            return typeMetaResult.isAllowed()
                    && typeMetaResult.requireValue().kind() == ScopeTypeMetaKind.GLOBAL_ENUM
                    && supportsTopLevelTypeMeta(typeMetaResult.requireValue());
        }

        private @Nullable Scope findCurrentScope(@NotNull IdentifierExpression identifierExpression) {
            return scopesByAst.get(Objects.requireNonNull(identifierExpression, "identifierExpression must not be null"));
        }

        private void reportMissingScopeUnsupported(
                @NotNull IdentifierExpression identifierExpression,
                @NotNull String symbolName
        ) {
            reportDeferredUnsupported(
                    identifierExpression,
                    symbolName,
                    new FrontendVisibleValueDeferredBoundary(
                            FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE,
                            FrontendVisibleValueDeferredReason.MISSING_SCOPE_OR_SKIPPED_SUBTREE
                    )
            );
        }

        private @NotNull FrontendBindingKind toBindingKind(@NotNull ScopeValueKind scopeValueKind) {
            return switch (Objects.requireNonNull(scopeValueKind, "scopeValueKind must not be null")) {
                case LOCAL -> FrontendBindingKind.LOCAL_VAR;
                case PARAMETER -> FrontendBindingKind.PARAMETER;
                case CAPTURE -> FrontendBindingKind.CAPTURE;
                case PROPERTY -> FrontendBindingKind.PROPERTY;
                case SIGNAL -> FrontendBindingKind.SIGNAL;
                case CONSTANT -> FrontendBindingKind.CONSTANT;
                case SINGLETON -> FrontendBindingKind.SINGLETON;
                case GLOBAL_ENUM -> FrontendBindingKind.GLOBAL_ENUM;
                case TYPE_META -> FrontendBindingKind.TYPE_META;
            };
        }

        private void publishBinding(
                @NotNull Node useSite,
                @NotNull String symbolName,
                @NotNull FrontendBindingKind kind,
                @Nullable Object declarationSite
        ) {
            symbolBindings.put(
                    useSite,
                    new FrontendBinding(
                            Objects.requireNonNull(symbolName, "symbolName must not be null"),
                            Objects.requireNonNull(kind, "kind must not be null"),
                            declarationSite
                    )
            );
        }

        private void reportBindingError(@NotNull Node useSite, @NotNull String message) {
            diagnosticManager.error(
                    BINDING_CATEGORY,
                    Objects.requireNonNull(message, "message must not be null"),
                    sourcePath,
                    FrontendRange.fromAstRange(useSite.range())
            );
        }

        private void reportDeferredSubtree(
                @NotNull Node subtreeRoot,
                @NotNull FrontendVisibleValueDomain domain
        ) {
            if (reportedUnsupportedRoots.putIfAbsent(subtreeRoot, Boolean.TRUE) != null) {
                return;
            }
            diagnosticManager.warning(
                    UNSUPPORTED_BINDING_SUBTREE_CATEGORY,
                    "Binding analysis is deferred in " + formatDomain(domain),
                    sourcePath,
                    FrontendRange.fromAstRange(subtreeRoot.range())
            );
        }

        private void reportSkippedSubtree(@Nullable Node subtreeRoot) {
            if (subtreeRoot == null) {
                return;
            }
            reportDeferredSubtree(subtreeRoot, FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE);
        }

        private void reportDeferredUnsupported(
                @NotNull Node useSite,
                @NotNull String symbolName,
                @Nullable FrontendVisibleValueDeferredBoundary deferredBoundary
        ) {
            var boundary = Objects.requireNonNull(deferredBoundary, "deferredBoundary must not be null");
            diagnosticManager.warning(
                    UNSUPPORTED_BINDING_SUBTREE_CATEGORY,
                    "Binding analysis for '" + symbolName + "' is deferred in " + formatDomain(boundary.domain()),
                    sourcePath,
                    FrontendRange.fromAstRange(useSite.range())
            );
        }

        private @NotNull String formatDomain(@NotNull FrontendVisibleValueDomain domain) {
            return switch (Objects.requireNonNull(domain, "domain must not be null")) {
                case EXECUTABLE_BODY -> "executable body";
                case PARAMETER_DEFAULT -> "parameter default";
                case LAMBDA_SUBTREE -> "lambda subtree";
                case BLOCK_LOCAL_CONST_SUBTREE -> "block-local const initializer";
                case FOR_SUBTREE -> "for subtree";
                case MATCH_SUBTREE -> "match subtree";
                case UNKNOWN_OR_SKIPPED_SUBTREE -> "skipped subtree";
            };
        }

        private boolean isNotPublished(@Nullable Node node) {
            return node == null || !scopesByAst.containsKey(node);
        }
    }
}
