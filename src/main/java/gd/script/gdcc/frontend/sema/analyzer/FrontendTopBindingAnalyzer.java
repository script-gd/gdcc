package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendModuleSkeleton;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDeferredBoundary;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDeferredReason;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueDomain;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueResolveRequest;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueResolution;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueResolver;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueStatus;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.gdextension.ExtensionUtilityFunction;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.*;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.type.GdObjectType;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
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
import java.util.HashSet;
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
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
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
                    moduleSkeleton,
                    scopesByAst,
                    symbolBindings,
                    diagnosticManager,
                    visibleValueResolver,
                    classRegistry
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
        private final @NotNull FrontendModuleSkeleton moduleSkeleton;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull FrontendVisibleValueResolver visibleValueResolver;
        private final @NotNull ClassRegistry classRegistry;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull IdentityHashMap<Node, Boolean> reportedUnsupportedRoots = new IdentityHashMap<>();
        private int supportedExecutableBlockDepth;
        private int supportedPropertyInitializerDepth;
        private @NotNull ResolveRestriction currentRestriction = ResolveRestriction.unrestricted();
        private boolean currentStaticContext;
        private @Nullable FrontendPropertyInitializerSupport.PropertyInitializerContext currentPropertyInitializerContext;

        private AstWalkerTopBindingBinder(
                @NotNull Path sourcePath,
                @NotNull FrontendModuleSkeleton moduleSkeleton,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
                @NotNull DiagnosticManager diagnosticManager,
                @NotNull FrontendVisibleValueResolver visibleValueResolver,
                @NotNull ClassRegistry classRegistry
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            this.moduleSkeleton = Objects.requireNonNull(moduleSkeleton, "moduleSkeleton must not be null");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            this.symbolBindings = Objects.requireNonNull(symbolBindings, "symbolBindings must not be null");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            this.visibleValueResolver = Objects.requireNonNull(
                    visibleValueResolver,
                    "visibleValueResolver must not be null"
            );
            this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
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
            if (supportedExecutableBlockDepth > 0) {
                if (variableDeclaration.value() == null) {
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
            if (!FrontendPropertyInitializerSupport.isSupportedPropertyInitializer(scopesByAst, variableDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkPropertyInitializer(variableDeclaration);
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

        /// Property initializers need the class member lookup contract and static restriction of the
        /// declaring property, but they still must not widen the whole class body into an executable
        /// region.
        private void walkPropertyInitializer(@NotNull VariableDeclaration variableDeclaration) {
            var initializer = Objects.requireNonNull(
                    variableDeclaration.value(),
                    "property initializer value must not be null"
            );
            var previousRestriction = currentRestriction;
            var previousStaticContext = currentStaticContext;
            var previousPropertyInitializerContext = currentPropertyInitializerContext;
            currentRestriction = FrontendPropertyInitializerSupport.restrictionFor(variableDeclaration);
            currentStaticContext = variableDeclaration.isStatic();
            currentPropertyInitializerContext = FrontendPropertyInitializerSupport.contextFor(
                    scopesByAst,
                    variableDeclaration
            );
            supportedPropertyInitializerDepth++;
            try {
                walkValueExpression(initializer);
            } finally {
                supportedPropertyInitializerDepth--;
                currentPropertyInitializerContext = previousPropertyInitializerContext;
                currentRestriction = previousRestriction;
                currentStaticContext = previousStaticContext;
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
            //
            // Dual-role bias: when the base is a bare IdentifierExpression whose value namespace
            // resolves to SINGLETON and whose type-meta namespace also resolves to ENGINE_CLASS,
            // inspect the first suffix step to decide whether the head should publish TYPE_META
            // (static/constructor route) or stay on the ordinary SINGLETON value path. This runs
            // before walking the base so the ordinary identifier handler never publishes a
            // SINGLETON binding that would then need to be overwritten.
            if (!tryApplyDualRoleTypeMetaBias(attributeExpression)) {
                walkChainHeadBaseExpression(attributeExpression.base());
            }
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
            if (supportedPropertyInitializerDepth > 0) {
                reportPropertyInitializerUnsupportedBoundary(
                        selfExpression,
                        FrontendPropertyInitializerSupport.unsupportedSelfMessage()
                );
                return;
            }
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
            if (trySealPropertyInitializerValueBoundary(identifierExpression)) {
                return;
            }
            var valueResolution = resolveVisibleValue(identifierExpression);
            if (valueResolution.status() != FrontendVisibleValueStatus.NOT_FOUND) {
                publishValueResolution(identifierExpression, valueResolution);
                return;
            }

            if (trySealPropertyInitializerFunctionBoundary(identifierExpression)) {
                return;
            }

            var currentScope = findCurrentScope(identifierExpression);
            if (currentScope == null) {
                reportMissingScopeUnsupported(identifierExpression, identifierExpression.name());
                return;
            }

            var functionResult = currentScope.resolveFunctions(identifierExpression.name(), currentRestriction);
            switch (functionResult.status()) {
                case FOUND_ALLOWED -> {
                    publishFunctionBinding(identifierExpression, functionResult.requireValue(), false);
                    return;
                }
                case FOUND_BLOCKED -> {
                    publishFunctionBinding(identifierExpression, functionResult.requireValue(), true);
                    return;
                }
                case NOT_FOUND -> {
                }
            }

            var typeMetaResult = moduleSkeleton.resolveSourceFacingTypeMeta(
                    currentScope,
                    identifierExpression.name(),
                    currentRestriction
            );
            if (typeMetaResult.isAllowed()) {
                var typeMeta = typeMetaResult.requireValue();
                if (supportsTopLevelTypeMeta(typeMeta)) {
                    publishBinding(
                            identifierExpression,
                            identifierExpression.name(),
                            FrontendBindingKind.TYPE_META,
                            typeMeta.declaration()
                    );
                    reportBindingError(
                            identifierExpression,
                            "Type-meta '" + identifierExpression.name()
                                    + "' can only be used as a static-route head, not as an ordinary value; "
                                    + "use routes such as '" + identifierExpression.name()
                                    + ".build(...)', '" + identifierExpression.name()
                                    + ".new()', or a static constant access like 'Vector3.BACK'"
                    );
                    return;
                }
                reportBindingError(
                        identifierExpression,
                        "Top-level type-meta binding for '" + identifierExpression.name()
                                + "' currently supports class-like types, builtin static receivers, and global enums"
                );
                return;
            }

            publishValueResolution(identifierExpression, valueResolution);
        }

        private @NotNull FrontendVisibleValueResolution resolveVisibleValue(
                @NotNull IdentifierExpression identifierExpression
        ) {
            if (supportedPropertyInitializerDepth > 0) {
                return resolvePropertyInitializerValue(identifierExpression);
            }
            return visibleValueResolver.resolve(new FrontendVisibleValueResolveRequest(
                    identifierExpression.name(),
                    identifierExpression,
                    currentRestriction,
                    FrontendVisibleValueDomain.EXECUTABLE_BODY
            ));
        }

        /// Property initializer lookup deliberately bypasses `FrontendVisibleValueResolver`: class
        /// member initializers do not have callable-local declaration-order or local inventory rules,
        /// so they should consume the shared scope/class/global contract directly.
        private @NotNull FrontendVisibleValueResolution resolvePropertyInitializerValue(
                @NotNull IdentifierExpression identifierExpression
        ) {
            var currentScope = findCurrentScope(identifierExpression);
            if (currentScope == null) {
                return FrontendVisibleValueResolution.deferredUnsupported(
                        new FrontendVisibleValueDeferredBoundary(
                                FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE,
                                FrontendVisibleValueDeferredReason.MISSING_SCOPE_OR_SKIPPED_SUBTREE
                        )
                );
            }
            var valueResult = currentScope.resolveValue(identifierExpression.name(), currentRestriction);
            return switch (valueResult.status()) {
                case FOUND_ALLOWED ->
                        FrontendVisibleValueResolution.foundAllowed(valueResult.requireValue(), List.of());
                case FOUND_BLOCKED ->
                        FrontendVisibleValueResolution.foundBlocked(valueResult.requireValue(), List.of());
                case NOT_FOUND -> FrontendVisibleValueResolution.notFound(List.of());
            };
        }

        private void publishValueResolution(
                @NotNull IdentifierExpression identifierExpression,
                @NotNull FrontendVisibleValueResolution resolution
        ) {
            switch (resolution.status()) {
                case FOUND_ALLOWED -> publishScopeValueBinding(
                        identifierExpression,
                        resolution.visibleValue(),
                        ScopeLookupStatus.FOUND_ALLOWED
                );
                case FOUND_BLOCKED -> {
                    publishScopeValueBinding(
                            identifierExpression,
                            resolution.visibleValue(),
                            ScopeLookupStatus.FOUND_BLOCKED
                    );
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

            if (trySealPropertyInitializerValueBoundary(identifierExpression)) {
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

            var typeMetaResult = moduleSkeleton.resolveSourceFacingTypeMeta(
                    currentScope,
                    identifierExpression.name(),
                    currentRestriction
            );
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
            if (trySealPropertyInitializerFunctionBoundary(identifierExpression)) {
                return;
            }

            var functionResult = currentScope.resolveFunctions(identifierExpression.name(), currentRestriction);
            switch (functionResult.status()) {
                case FOUND_ALLOWED -> {
                    publishFunctionBinding(identifierExpression, functionResult.requireValue(), false);
                    return;
                }
                case FOUND_BLOCKED -> {
                    publishFunctionBinding(identifierExpression, functionResult.requireValue(), true);
                    return;
                }
                case NOT_FOUND -> {
                }
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
                @NotNull ScopeLookupResult<ScopeTypeMeta> typeMetaResult
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
            if (trySealPropertyInitializerFunctionBoundary(identifierExpression)) {
                return;
            }

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
                    var typeMetaResult = moduleSkeleton.resolveSourceFacingTypeMeta(
                            currentScope,
                            identifierExpression.name(),
                            currentRestriction
                    );
                    if (typeMetaResult.isAllowed() && supportsTopLevelTypeMeta(typeMetaResult.requireValue())) {
                        publishBinding(
                                identifierExpression,
                                identifierExpression.name(),
                                FrontendBindingKind.TYPE_META,
                                typeMetaResult.requireValue().declaration()
                        );
                        return;
                    }
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
                @Nullable ScopeValue scopeValue,
                @NotNull ScopeLookupStatus accessStatus
        ) {
            var resolvedValue = Objects.requireNonNull(scopeValue, "scopeValue must not be null");
            if (accessStatus == ScopeLookupStatus.NOT_FOUND) {
                throw new IllegalArgumentException("scope value binding must be a found result");
            }
            publishBinding(
                    identifierExpression,
                    identifierExpression.name(),
                    toBindingKind(resolvedValue.kind()),
                    resolvedValue.declaration(),
                    resolvedValue,
                    accessStatus
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
                @NotNull ScopeLookupResult<ScopeTypeMeta> typeMetaResult
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

        /// Dual-role chain-head route bias entry point.
        ///
        /// When the `AttributeExpression` base is a bare `IdentifierExpression` whose value
        /// namespace resolves to `SINGLETON` (via `resolveVisibleValue`, which honors
        /// declaration-order filtering and self-reference sealing) and whose type-meta
        /// namespace also resolves to `ENGINE_CLASS`, inspect the first suffix step to decide
        /// whether the head should publish `TYPE_META` (for static constant / enum value /
        /// static method / constructor `.new()` routes) or stay on the ordinary `SINGLETON`
        /// value path (for instance method / property routes).
        ///
        /// This method MUST consume `resolveVisibleValue(...)` as the value-winner authority,
        /// matching the contract of `bindTopLevelTypeMetaCandidate(...)`: if a local, parameter,
        /// property, or other non-singleton value wins the visible-value resolution, the bias
        /// must not override it — the caller falls through to the ordinary
        /// `bindTopLevelTypeMetaCandidate(...)` flow which publishes the value winner and
        /// reports shadowing diagnostics. Similarly, `FOUND_BLOCKED` and `DEFERRED_UNSUPPORTED`
        /// value states are handled by the ordinary flow, not here.
        ///
        /// The decision is fail-closed: if the first suffix can be satisfied by both the
        /// singleton instance namespace (instance method, instance property, or signal) and the
        /// type-meta static namespace, the head keeps `SINGLETON` to avoid silently changing the
        /// route based on registry traversal order.
        ///
        /// Returns `true` when a `TYPE_META` binding was published and the caller must skip the
        /// ordinary base walk; returns `false` when the ordinary walk should proceed.
        private boolean tryApplyDualRoleTypeMetaBias(@NotNull AttributeExpression attributeExpression) {
            if (!(attributeExpression.base() instanceof IdentifierExpression identifierExpression)) {
                return false;
            }
            if (attributeExpression.steps().isEmpty()) {
                return false;
            }
            var currentScope = findCurrentScope(identifierExpression);
            if (currentScope == null) {
                return false;
            }
            var name = identifierExpression.name();

            // Value-winner authority: resolveVisibleValue honors declaration-order filtering,
            // self-reference sealing, and deferred boundaries. Only a FOUND_ALLOWED SINGLETON
            // value winner is eligible for the dual-role bias. All other value states (local,
            // parameter, property, blocked, deferred, not-found) must fall through to the
            // ordinary bindTopLevelTypeMetaCandidate flow.
            if (trySealPropertyInitializerValueBoundary(identifierExpression)) {
                return false;
            }
            var valueResolution = resolveVisibleValue(identifierExpression);
            if (valueResolution.status() != FrontendVisibleValueStatus.FOUND_ALLOWED) {
                return false;
            }
            var visibleValue = valueResolution.visibleValue();
            if (visibleValue == null || visibleValue.kind() != ScopeValueKind.SINGLETON) {
                return false;
            }
            var singletonType = classRegistry.findSingletonType(name);
            if (singletonType == null) {
                return false;
            }

            // Type-meta namespace must also resolve the same name to ENGINE_CLASS (or GDCC_CLASS).
            var typeMetaResult = moduleSkeleton.resolveSourceFacingTypeMeta(
                    currentScope,
                    name,
                    currentRestriction
            );
            if (!typeMetaResult.isAllowed()) {
                return false;
            }
            var typeMeta = typeMetaResult.requireValue();
            if (typeMeta.kind() != ScopeTypeMetaKind.ENGINE_CLASS
                    && typeMeta.kind() != ScopeTypeMetaKind.GDCC_CLASS) {
                return false;
            }
            if (!supportsTopLevelTypeMeta(typeMeta)) {
                return false;
            }

            var firstStep = attributeExpression.steps().getFirst();
            var stepName = extractStepName(firstStep);
            if (stepName == null) {
                return false;
            }

            // Constructor-like `.new()` route: prefer TYPE_META, but still respect fail-closed.
            // If the singleton declared type has an instance method, instance property, or signal
            // named "new", the suffix resolves in the singleton instance namespace, so the head
            // must stay SINGLETON to avoid silently stealing an instance-member route. Only when
            // "new" does NOT resolve as a singleton instance member do we switch to TYPE_META and
            // let the downstream constructor route decide legality.
            if (firstStep instanceof AttributeCallStep && stepName.equals("new")) {
                if (!resolvesInSingletonInstanceNamespace(singletonType, stepName)) {
                    publishBinding(
                            identifierExpression,
                            name,
                            FrontendBindingKind.TYPE_META,
                            typeMeta.declaration()
                    );
                    return true;
                }
                return false;
            }

            // For property steps, check whether the suffix only resolves in the type-meta static
            // namespace (engine class constant, class enum value, or static method reference).
            // For call steps (non-`new`), check whether the suffix only resolves as a static method.
            boolean inTypeMetaStatic = resolvesInTypeMetaStaticNamespace(typeMeta, stepName);
            boolean inSingletonInstance = resolvesInSingletonInstanceNamespace(singletonType, stepName);

            // Fail-closed: only switch to TYPE_META when the suffix is NOT available as a
            // singleton instance member (instance method, instance property, or signal). This
            // prevents silently changing the route when both namespaces can satisfy the same
            // suffix name.
            if (inTypeMetaStatic && !inSingletonInstance) {
                publishBinding(
                        identifierExpression,
                        name,
                        FrontendBindingKind.TYPE_META,
                        typeMeta.declaration()
                );
                return true;
            }
            return false;
        }

        /// Extracts the member name from the first attribute step, or `null` for unknown step types.
        private @Nullable String extractStepName(@NotNull AttributeStep step) {
            return switch (step) {
                case AttributePropertyStep propertyStep -> propertyStep.name();
                case AttributeCallStep callStep -> callStep.name();
                case AttributeSubscriptStep subscriptStep -> subscriptStep.name();
                default -> null;
            };
        }

        /// Checks whether `stepName` resolves in the type-meta static namespace. Engine class
        /// constants and class enum values use the registry's inherited lookup, while static
        /// methods keep the existing hierarchy walk shared with GDCC classes.
        private boolean resolvesInTypeMetaStaticNamespace(
                @NotNull ScopeTypeMeta typeMeta,
                @NotNull String stepName
        ) {
            if (typeMeta.declaration() instanceof ExtensionGdClass engineClass) {
                if (classRegistry.findEngineClassConstantInHierarchy(engineClass.getName(), stepName) != null) {
                    return true;
                }
                if (classRegistry.findEngineClassEnumValueInHierarchy(engineClass.getName(), stepName) != null) {
                    return true;
                }
            } else if (classRegistry.getClassDef(
                    typeMeta.instanceType() instanceof GdObjectType ot ? ot : new GdObjectType(typeMeta.canonicalName())
            ) instanceof ExtensionGdClass engineClass) {
                if (classRegistry.findEngineClassConstantInHierarchy(engineClass.getName(), stepName) != null) {
                    return true;
                }
                if (classRegistry.findEngineClassEnumValueInHierarchy(engineClass.getName(), stepName) != null) {
                    return true;
                }
            }
            return hasStaticMethodInHierarchy(typeMeta, stepName);
        }

        /// Checks whether `stepName` resolves as a singleton instance member: instance method,
        /// instance property, or signal (walking the class hierarchy of the singleton declared
        /// type). Signal is included defensively so that when signal chain access enters the
        /// supported grammar, the fail-closed rule already covers it instead of silently
        /// switching the head to TYPE_META.
        private boolean resolvesInSingletonInstanceNamespace(
                @NotNull GdObjectType singletonType,
                @NotNull String stepName
        ) {
            return hasInstanceMethodInHierarchy(singletonType, stepName)
                    || hasInstancePropertyInHierarchy(singletonType, stepName)
                    || hasSignalInHierarchy(singletonType, stepName);
        }

        /// Walks the class hierarchy starting from `typeMeta` to check if a static method named
        /// `stepName` exists. Covers both ENGINE_CLASS and GDCC_CLASS.
        private boolean hasStaticMethodInHierarchy(@NotNull ScopeTypeMeta typeMeta, @NotNull String stepName) {
            ClassDef current = classRegistry.resolveClassDefFromTypeMeta(typeMeta);
            var visited = new HashSet<String>();
            while (current != null && visited.add(current.getName())) {
                var found = current.getFunctions().stream()
                        .anyMatch(fn -> fn.getName().equals(stepName) && fn.isStatic());
                if (found) {
                    return true;
                }
                current = classRegistry.resolveSuperclass(current);
            }
            return false;
        }

        /// Walks the class hierarchy of the singleton declared type to check if an instance method
        /// named `stepName` exists.
        private boolean hasInstanceMethodInHierarchy(@NotNull GdObjectType singletonType, @NotNull String stepName) {
            ClassDef current = classRegistry.getClassDef(singletonType);
            var visited = new HashSet<String>();
            while (current != null && visited.add(current.getName())) {
                var found = current.getFunctions().stream()
                        .anyMatch(fn -> fn.getName().equals(stepName) && !fn.isStatic());
                if (found) {
                    return true;
                }
                current = classRegistry.resolveSuperclass(current);
            }
            return false;
        }

        /// Walks the class hierarchy of the singleton declared type to check if an instance
        /// property named `stepName` exists.
        private boolean hasInstancePropertyInHierarchy(@NotNull GdObjectType singletonType, @NotNull String stepName) {
            ClassDef current = classRegistry.getClassDef(singletonType);
            var visited = new HashSet<String>();
            while (current != null && visited.add(current.getName())) {
                var found = current.getProperties().stream()
                        .anyMatch(prop -> prop.getName().equals(stepName) && !prop.isStatic());
                if (found) {
                    return true;
                }
                current = classRegistry.resolveSuperclass(current);
            }
            return false;
        }

        /// Walks the class hierarchy of the singleton declared type to check if a signal named
        /// `stepName` exists. Signal is checked defensively so the fail-closed rule covers it
        /// even before signal chain access enters the supported grammar.
        private boolean hasSignalInHierarchy(@NotNull GdObjectType singletonType, @NotNull String stepName) {
            ClassDef current = classRegistry.getClassDef(singletonType);
            var visited = new HashSet<String>();
            while (current != null && visited.add(current.getName())) {
                var found = current.getSignals().stream()
                        .anyMatch(signal -> signal.getName().equals(stepName));
                if (found) {
                    return true;
                }
                current = classRegistry.resolveSuperclass(current);
            }
            return false;
        }

        private @Nullable Scope findCurrentScope(@NotNull IdentifierExpression identifierExpression) {
            return scopesByAst.get(Objects.requireNonNull(identifierExpression, "identifierExpression must not be null"));
        }

        private boolean trySealPropertyInitializerValueBoundary(@NotNull IdentifierExpression identifierExpression) {
            if (supportedPropertyInitializerDepth <= 0) {
                return false;
            }
            var currentInstanceValueKind = FrontendPropertyInitializerSupport.currentInstanceHierarchyNonStaticValueKind(
                    currentPropertyInitializerContext,
                    identifierExpression.name()
            );
            if (currentInstanceValueKind == null) {
                return false;
            }
            publishBinding(
                    identifierExpression,
                    identifierExpression.name(),
                    toBindingKind(currentInstanceValueKind),
                    null
            );
            reportPropertyInitializerUnsupportedBoundary(
                    identifierExpression,
                    FrontendPropertyInitializerSupport.unsupportedValueMessage(
                            identifierExpression.name(),
                            currentInstanceValueKind
                    )
            );
            return true;
        }

        private boolean trySealPropertyInitializerFunctionBoundary(@NotNull IdentifierExpression identifierExpression) {
            if (supportedPropertyInitializerDepth <= 0 || !FrontendPropertyInitializerSupport.hasCurrentInstanceHierarchyNonStaticFunction(
                    currentPropertyInitializerContext,
                    identifierExpression.name()
            )) {
                return false;
            }
            publishBinding(
                    identifierExpression,
                    identifierExpression.name(),
                    FrontendBindingKind.METHOD,
                    null
            );
            reportPropertyInitializerUnsupportedBoundary(
                    identifierExpression,
                    FrontendPropertyInitializerSupport.unsupportedMethodMessage(identifierExpression.name())
            );
            return true;
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
            publishBinding(useSite, symbolName, kind, declarationSite, null, null);
        }

        private void publishBinding(
                @NotNull Node useSite,
                @NotNull String symbolName,
                @NotNull FrontendBindingKind kind,
                @Nullable Object declarationSite,
                @Nullable ScopeValue resolvedValue,
                @Nullable ScopeLookupStatus valueAccessStatus
        ) {
            symbolBindings.put(
                    useSite,
                    new FrontendBinding(
                            Objects.requireNonNull(symbolName, "symbolName must not be null"),
                            Objects.requireNonNull(kind, "kind must not be null"),
                            declarationSite,
                            resolvedValue,
                            valueAccessStatus
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

        private void reportPropertyInitializerUnsupportedBoundary(
                @NotNull Node useSite,
                @NotNull String message
        ) {
            diagnosticManager.error(
                    UNSUPPORTED_BINDING_SUBTREE_CATEGORY,
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
            reportBindingBoundary(subtreeRoot, domain, false, null);
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
            reportBindingBoundary(
                    useSite,
                    boundary.domain(),
                    boundary.reason() == FrontendVisibleValueDeferredReason.MISSING_SCOPE_OR_SKIPPED_SUBTREE,
                    symbolName
            );
        }

        @SuppressWarnings("SwitchStatementWithTooFewBranches")
        private void reportBindingBoundary(
                @NotNull Node anchor,
                @NotNull FrontendVisibleValueDomain domain,
                boolean skippedRecoveryBoundary,
                @Nullable String symbolName
        ) {
            var formattedDomain = formatDomain(domain);
            var message = switch (Objects.requireNonNull(domain, "domain must not be null")) {
                case UNKNOWN_OR_SKIPPED_SUBTREE -> symbolName == null
                        ? "Binding analysis skipped in " + formattedDomain
                        : "Binding analysis for '" + symbolName + "' was skipped in " + formattedDomain;
                default -> symbolName == null
                        ? "Binding analysis is not supported in " + formattedDomain
                        : "Binding analysis for '" + symbolName + "' is not supported in " + formattedDomain;
            };
            if (domain == FrontendVisibleValueDomain.UNKNOWN_OR_SKIPPED_SUBTREE || skippedRecoveryBoundary) {
                diagnosticManager.warning(
                        UNSUPPORTED_BINDING_SUBTREE_CATEGORY,
                        message,
                        sourcePath,
                        FrontendRange.fromAstRange(anchor.range())
                );
                return;
            }
            diagnosticManager.error(
                    UNSUPPORTED_BINDING_SUBTREE_CATEGORY,
                    message,
                    sourcePath,
                    FrontendRange.fromAstRange(anchor.range())
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
