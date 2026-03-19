package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import dev.superice.gdcc.scope.ClassDef;
import dev.superice.gdcc.scope.ClassRegistry;
import dev.superice.gdcc.scope.ResolveRestriction;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVoidType;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.FrontendASTTraversalDirective;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Diagnostics-only frontend type-check phase skeleton.
///
/// T1 intentionally stops at the framework boundary:
/// - require the stable upstream phase order to have already published scope/binding/member/call/type facts
/// - walk only the statement roots that later typed contracts care about
/// - maintain the minimal contextual state T2-T5 will need, without creating any new side table
///
/// The protected callback surface exists so tests can lock the traversal/context contract now,
/// before concrete type-check diagnostics land in later tasks.
public class FrontendTypeCheckAnalyzer {
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
        // Touch the already-published frontend facts explicitly so future typed-contract work stays
        // anchored to the current phase boundary instead of re-deriving information ad hoc.
        analysisData.symbolBindings();
        analysisData.resolvedMembers();
        analysisData.resolvedCalls();
        analysisData.expressionTypes();

        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            var sourceFile = sourceClassRelation.unit().ast();
            if (!scopesByAst.containsKey(sourceFile)) {
                throw new IllegalStateException(
                        "Scope graph has not been published for source file: " + sourceClassRelation.unit().path()
                );
            }
        }

        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            new AstWalkerTypeCheckVisitor(
                    sourceClassRelation.unit().path(),
                    analysisData,
                    scopesByAst,
                    diagnosticManager
            ).walk(sourceClassRelation.unit().ast());
        }
    }

    protected void visitOrdinaryLocalInitializer(
            @NotNull TypeCheckAccess access,
            @NotNull VariableDeclaration variableDeclaration
    ) {
    }

    protected void visitPropertyInitializer(
            @NotNull TypeCheckAccess access,
            @NotNull VariableDeclaration variableDeclaration
    ) {
    }

    protected void visitReturnStatement(
            @NotNull TypeCheckAccess access,
            @NotNull ReturnStatement returnStatement
    ) {
    }

    protected void visitConditionExpression(
            @NotNull TypeCheckAccess access,
            @NotNull Expression condition,
            @NotNull Node owner
    ) {
    }

    protected record TypeCheckAccess(
            @NotNull Path sourcePath,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull TypeCheckVisitContext context
    ) {
        public TypeCheckAccess {
            Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            Objects.requireNonNull(analysisData, "analysisData must not be null");
            Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            Objects.requireNonNull(context, "context must not be null");
        }
    }

    protected record TypeCheckVisitContext(
            @Nullable ClassDef currentClass,
            @Nullable GdType currentCallableReturnSlot,
            @NotNull ResolveRestriction currentRestriction,
            boolean currentStaticContext,
            int executableBodyDepth,
            @Nullable FrontendPropertyInitializerSupport.PropertyInitializerContext currentPropertyInitializerContext
    ) {
        public TypeCheckVisitContext {
            Objects.requireNonNull(currentRestriction, "currentRestriction must not be null");
        }
    }

    private final class AstWalkerTypeCheckVisitor implements ASTNodeHandler {
        private final @NotNull Path sourcePath;
        private final @NotNull FrontendAnalysisData analysisData;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull ASTWalker astWalker;
        private int supportedExecutableBlockDepth;
        private @Nullable ClassDef currentClass;
        private @Nullable GdType currentCallableReturnSlot;
        private @NotNull ResolveRestriction currentRestriction = ResolveRestriction.unrestricted();
        private boolean currentStaticContext;
        private @Nullable FrontendPropertyInitializerSupport.PropertyInitializerContext currentPropertyInitializerContext;

        private AstWalkerTypeCheckVisitor(
                @NotNull Path sourcePath,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            astWalker = new ASTWalker(this);
        }

        private void walk(@NotNull SourceFile sourceFile) {
            astWalker.walk(Objects.requireNonNull(sourceFile, "sourceFile must not be null"));
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleNode(@NotNull Node node) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleSourceFile(@NotNull SourceFile sourceFile) {
            walkClassContainer(sourceFile, sourceFile.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleClassDeclaration(@NotNull ClassDeclaration classDeclaration) {
            if (isNotPublished(classDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkClassContainer(classDeclaration, classDeclaration.body().statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration
        ) {
            if (isNotPublished(functionDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkCallableBody(
                    functionDeclaration,
                    functionDeclaration.body(),
                    resolveFunctionReturnSlot(functionDeclaration),
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
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkCallableBody(
                    constructorDeclaration,
                    constructorDeclaration.body(),
                    GdVoidType.VOID,
                    ResolveRestriction.instanceContext(),
                    false
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleBlock(@NotNull Block block) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(block)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkStatements(block.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleVariableDeclaration(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            if (supportedExecutableBlockDepth > 0) {
                if (isOrdinaryLocalInitializer(variableDeclaration)) {
                    visitOrdinaryLocalInitializer(callbackAccess(), variableDeclaration);
                }
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (!FrontendPropertyInitializerSupport.isSupportedPropertyInitializer(scopesByAst, variableDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkPropertyInitializer(variableDeclaration);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleReturnStatement(@NotNull ReturnStatement returnStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitReturnStatement(callbackAccess(), returnStatement);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleAssertStatement(@NotNull AssertStatement assertStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitConditionExpression(callbackAccess(), assertStatement.condition(), assertStatement);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(ifStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitConditionExpression(callbackAccess(), ifStatement.condition(), ifStatement);
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
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(elifClause)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitConditionExpression(callbackAccess(), elifClause.condition(), elifClause);
            walkSupportedExecutableBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(whileStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            visitConditionExpression(callbackAccess(), whileStatement.condition(), whileStatement);
            walkSupportedExecutableBlock(whileStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        private void walkClassContainer(@NotNull Node classOwner, @NotNull List<Statement> statements) {
            var previousClass = currentClass;
            var previousRestriction = currentRestriction;
            var previousStaticContext = currentStaticContext;
            var previousCallableReturnSlot = currentCallableReturnSlot;
            var previousPropertyInitializerContext = currentPropertyInitializerContext;
            currentClass = requireClassScope(classOwner).getCurrentClass();
            currentRestriction = ResolveRestriction.unrestricted();
            currentStaticContext = false;
            currentCallableReturnSlot = null;
            currentPropertyInitializerContext = null;
            try {
                walkNonExecutableContainerStatements(statements);
            } finally {
                currentPropertyInitializerContext = previousPropertyInitializerContext;
                currentCallableReturnSlot = previousCallableReturnSlot;
                currentStaticContext = previousStaticContext;
                currentRestriction = previousRestriction;
                currentClass = previousClass;
            }
        }

        private void walkCallableBody(
                @NotNull Node callableOwner,
                @Nullable Block body,
                @NotNull GdType returnSlot,
                @NotNull ResolveRestriction restriction,
                boolean staticContext
        ) {
            if (isNotPublished(callableOwner) || isNotPublished(body)) {
                return;
            }
            var previousRestriction = currentRestriction;
            var previousStaticContext = currentStaticContext;
            var previousCallableReturnSlot = currentCallableReturnSlot;
            var previousPropertyInitializerContext = currentPropertyInitializerContext;
            currentRestriction = Objects.requireNonNull(restriction, "restriction must not be null");
            currentStaticContext = staticContext;
            currentCallableReturnSlot = Objects.requireNonNull(returnSlot, "returnSlot must not be null");
            currentPropertyInitializerContext = null;
            try {
                walkSupportedExecutableBlock(body);
            } finally {
                currentPropertyInitializerContext = previousPropertyInitializerContext;
                currentCallableReturnSlot = previousCallableReturnSlot;
                currentStaticContext = previousStaticContext;
                currentRestriction = previousRestriction;
            }
        }

        private void walkPropertyInitializer(@NotNull VariableDeclaration variableDeclaration) {
            var previousRestriction = currentRestriction;
            var previousStaticContext = currentStaticContext;
            var previousCallableReturnSlot = currentCallableReturnSlot;
            var previousPropertyInitializerContext = currentPropertyInitializerContext;
            currentRestriction = FrontendPropertyInitializerSupport.restrictionFor(variableDeclaration);
            currentStaticContext = variableDeclaration.isStatic();
            currentCallableReturnSlot = null;
            currentPropertyInitializerContext = FrontendPropertyInitializerSupport.contextFor(
                    scopesByAst,
                    variableDeclaration
            );
            try {
                visitPropertyInitializer(callbackAccess(), variableDeclaration);
            } finally {
                currentPropertyInitializerContext = previousPropertyInitializerContext;
                currentCallableReturnSlot = previousCallableReturnSlot;
                currentStaticContext = previousStaticContext;
                currentRestriction = previousRestriction;
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
                return;
            }
            supportedExecutableBlockDepth++;
            try {
                astWalker.walk(block);
            } finally {
                supportedExecutableBlockDepth--;
            }
        }

        private boolean isOrdinaryLocalInitializer(@NotNull VariableDeclaration variableDeclaration) {
            return variableDeclaration.kind() == DeclarationKind.VAR
                    && variableDeclaration.value() != null
                    && scopesByAst.get(variableDeclaration) instanceof BlockScope;
        }

        private @NotNull GdType resolveFunctionReturnSlot(@NotNull FunctionDeclaration functionDeclaration) {
            Objects.requireNonNull(functionDeclaration, "functionDeclaration must not be null");
            if (functionDeclaration.name().equals("_init")) {
                return GdVoidType.VOID;
            }
            var currentClassDef = Objects.requireNonNull(
                    currentClass,
                    "currentClass must not be null while resolving function return slot"
            );
            return currentClassDef.getFunctions().stream()
                    .filter(function -> function.getName().equals(functionDeclaration.name()))
                    .filter(function -> function.isStatic() == functionDeclaration.isStatic())
                    .filter(function -> function.getParameterCount() == functionDeclaration.parameters().size())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Function skeleton has not been published for: "
                                    + currentClassDef.getName() + "." + functionDeclaration.name()
                    ))
                    .getReturnType();
        }

        private @NotNull ClassScope requireClassScope(@NotNull Node classOwner) {
            var publishedScope = scopesByAst.get(Objects.requireNonNull(classOwner, "classOwner must not be null"));
            if (publishedScope instanceof ClassScope classScope) {
                return classScope;
            }
            throw new IllegalStateException("Class scope has not been published for node: " + classOwner.getClass().getSimpleName());
        }

        private boolean isNotPublished(@Nullable Node node) {
            return node == null || !scopesByAst.containsKey(node);
        }

        private @NotNull TypeCheckAccess callbackAccess() {
            return new TypeCheckAccess(sourcePath, analysisData, diagnosticManager, snapshotContext());
        }

        private @NotNull TypeCheckVisitContext snapshotContext() {
            return new TypeCheckVisitContext(
                    currentClass,
                    currentCallableReturnSlot,
                    currentRestriction,
                    currentStaticContext,
                    supportedExecutableBlockDepth,
                    currentPropertyInitializerContext
            );
        }
    }
}
