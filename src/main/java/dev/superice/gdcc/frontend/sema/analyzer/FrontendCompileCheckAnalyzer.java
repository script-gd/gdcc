package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnostic;
import dev.superice.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import dev.superice.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.scope.ClassScope;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionKind;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendExecutableInventorySupport;
import dev.superice.gdcc.frontend.sema.FrontendExpressionType;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.frontend.sema.FrontendResolvedMember;
import dev.superice.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import dev.superice.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FrontendASTTraversalDirective;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Compile-only final frontend gate that runs after the shared semantic pipeline.
///
/// The default shared `analyze(...)` path intentionally does not call this analyzer so inspection
/// and future LSP-style entrypoints can keep consuming raw frontend recovery facts. Compile-only
/// entrypoints invoke it as the final diagnostics-only barrier before lowering is allowed to start.
///
/// The gate itself stays deliberately narrow:
/// - explicit AST compile blocks for the currently recognized forms whose lowering/backend support
///   is not ready yet
/// - generic side-table scans over published `expressionTypes()` / `resolvedMembers()` /
///   `resolvedCalls()` facts that are still blocked/deferred/failed/unsupported on compile surface
/// - no new side tables and no rewrites of upstream semantic ownership
public class FrontendCompileCheckAnalyzer {
    private static final @NotNull String COMPILE_CHECK_CATEGORY = "sema.compile_check";
    /// Some upstream diagnostics explain a lowering-only gap instead of competing with the compile
    /// gate's own hard-stop diagnostic. Those categories stay configurable here so future warning-
    /// based blockers do not need another dedicated ignore-upstream branch.
    private static final @NotNull Map<String, String> NON_CONFLICTING_UPSTREAM_DIAGNOSTIC_CATEGORIES = Map.of(
            FrontendVarTypePostAnalyzer.VARIABLE_SLOT_PUBLICATION_CATEGORY,
            "slot-publication warning explains the missing lowering-ready fact and must coexist with compile_check"
    );
    /// Compile mode usually blocks only on upstream `ERROR`s. This set is the narrow exception list
    /// for already-published non-error diagnostics that still represent a lowering-blocking gap.
    private static final @NotNull Set<String> NON_ERROR_BLOCKING_DIAGNOSTIC_CATEGORIES = Set.of(
            FrontendVarTypePostAnalyzer.VARIABLE_SLOT_PUBLICATION_CATEGORY
    );

    public void analyze(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var moduleSkeleton = analysisData.moduleSkeleton();
        var publishedDiagnostics = analysisData.diagnostics();
        var scopesByAst = analysisData.scopesByAst();

        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            var sourceFile = sourceClassRelation.unit().ast();
            if (!scopesByAst.containsKey(sourceFile)) {
                throw new IllegalStateException(
                        "Scope graph has not been published for source file: " + sourceClassRelation.unit().path()
                );
            }
        }

        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            new AstWalkerCompileCheckVisitor(
                    sourceClassRelation.unit().path(),
                    publishedDiagnostics,
                    scopesByAst,
                    analysisData.expressionTypes(),
                    analysisData.resolvedMembers(),
                    analysisData.resolvedCalls(),
                    analysisData.slotTypes(),
                    diagnosticManager
            ).walk(sourceClassRelation.unit().ast());
        }
    }

    private static @NotNull String assertCompileBlockedMessage() {
        return "assert statement is recognized by the frontend but is temporarily blocked in compile mode until "
                + "lowering/backend support lands";
    }

    private static @NotNull String conditionalCompileBlockedMessage() {
        return "Conditional expression is recognized by the frontend but is temporarily blocked in compile mode until "
                + "lowering CFG support lands";
    }

    private static @NotNull String expressionCompileBlockedMessage(@NotNull String expressionKind) {
        return Objects.requireNonNull(expressionKind, "expressionKind must not be null")
                + " is recognized by the frontend but is temporarily blocked in compile mode until "
                + "lowering support lands";
    }

    private static @NotNull String staticPropertyCompileBlockedMessage(@NotNull String propertyName) {
        return "Static property '" + Objects.requireNonNull(propertyName, "propertyName must not be null")
                + "' is recognized by the frontend but is blocked in compile mode because current backend "
                + "does not support script static fields";
    }

    private static @NotNull String gdccParameterizedConstructorCompileBlockedMessage(
            @NotNull FrontendResolvedCall publishedCall
    ) {
        var ownerName = publishedCall.receiverType() != null
                ? publishedCall.receiverType().getTypeName()
                : publishedCall.returnType() != null ? publishedCall.returnType().getTypeName() : "<unknown>";
        return "GDCC custom class constructor '" + ownerName
                + ".new(...)' is blocked in compile mode because current GDExtension registration "
                + "supports only zero-argument custom object construction";
    }

    private static @NotNull String publishedCompileBlockedMessage(
            @NotNull String surfaceKind,
            @NotNull Enum<?> publishedStatus,
            @Nullable String detailReason
    ) {
        var message = Objects.requireNonNull(surfaceKind, "surfaceKind must not be null")
                + " remains "
                + Objects.requireNonNull(publishedStatus, "publishedStatus must not be null").name().toLowerCase(Locale.ROOT)
                + " at compile surface and is not lowering-ready in compile mode";
        if (detailReason == null || detailReason.isBlank()) {
            return message;
        }
        return message + ": " + detailReason;
    }

    private static boolean isCompileBlocking(@NotNull FrontendExpressionTypeStatus status) {
        return switch (Objects.requireNonNull(status, "status must not be null")) {
            case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> true;
            case RESOLVED, DYNAMIC -> false;
        };
    }

    private static boolean isCompileBlocking(@NotNull FrontendMemberResolutionStatus status) {
        return switch (Objects.requireNonNull(status, "status must not be null")) {
            case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> true;
            case RESOLVED, DYNAMIC -> false;
        };
    }

    private static boolean isCompileBlocking(@NotNull FrontendCallResolutionStatus status) {
        return switch (Objects.requireNonNull(status, "status must not be null")) {
            case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> true;
            case RESOLVED, DYNAMIC -> false;
        };
    }

    /// One-file compile-surface walker that:
    /// - marks executable/property-init AST nodes that may reach lowering
    /// - emits explicit compile-only blockers for syntax routes still outside lowering support
    /// - replays published semantic facts against the marked surface and upgrades blocked routes
    ///   into final compile diagnostics
    private static final class AstWalkerCompileCheckVisitor implements ASTNodeHandler {
        private final @NotNull Path sourcePath;
        private final @NotNull DiagnosticSnapshot publishedDiagnostics;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes;
        private final @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers;
        private final @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls;
        private final @NotNull FrontendAstSideTable<GdType> slotTypes;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull Set<Node> compileSurfaceNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        private final @NotNull Set<Node> handledAnchors = Collections.newSetFromMap(new IdentityHashMap<>());
        private int supportedExecutableBlockDepth;

        /// Capture the shared semantic facts for one source file and prepare a dedicated walker.
        private AstWalkerCompileCheckVisitor(
                @NotNull Path sourcePath,
                @NotNull DiagnosticSnapshot publishedDiagnostics,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
                @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers,
                @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls,
                @NotNull FrontendAstSideTable<GdType> slotTypes,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            this.publishedDiagnostics = Objects.requireNonNull(
                    publishedDiagnostics,
                    "publishedDiagnostics must not be null"
            );
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            this.expressionTypes = Objects.requireNonNull(expressionTypes, "expressionTypes must not be null");
            this.resolvedMembers = Objects.requireNonNull(resolvedMembers, "resolvedMembers must not be null");
            this.resolvedCalls = Objects.requireNonNull(resolvedCalls, "resolvedCalls must not be null");
            this.slotTypes = Objects.requireNonNull(slotTypes, "slotTypes must not be null");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
            astWalker = new ASTWalker(this);
        }

        /// Walk the file once to mark compile surface, then scan published facts against that surface.
        private void walk(@NotNull SourceFile sourceFile) {
            astWalker.walk(Objects.requireNonNull(sourceFile, "sourceFile must not be null"));
            scanPublishedCompileBlocks();
        }

        /// Unknown nodes are ignored until a dedicated compile-surface rule is added.
        @Override
        public @NotNull FrontendASTTraversalDirective handleNode(@NotNull Node node) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// Top-level statements are scanned in non-executable mode so only supported declarations opt in.
        @Override
        public @NotNull FrontendASTTraversalDirective handleSourceFile(@NotNull SourceFile sourceFile) {
            walkNonExecutableContainerStatements(sourceFile.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// Class bodies behave like the source root until a supported executable/property-init child is found.
        @Override
        public @NotNull FrontendASTTraversalDirective handleClassDeclaration(@NotNull ClassDeclaration classDeclaration) {
            if (isNotPublished(classDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkNonExecutableContainerStatements(classDeclaration.body().statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// Executable function bodies define the primary compile-ready surface.
        @Override
        public @NotNull FrontendASTTraversalDirective handleFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration
        ) {
            if (isNotPublished(functionDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkCallableBody(functionDeclaration, functionDeclaration.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// Constructor bodies share the same compile-surface rules as ordinary executable functions.
        @Override
        public @NotNull FrontendASTTraversalDirective handleConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration
        ) {
            if (isNotPublished(constructorDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkCallableBody(constructorDeclaration, constructorDeclaration.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// Blocks are only traversed once they belong to a supported executable region.
        @Override
        public @NotNull FrontendASTTraversalDirective handleBlock(@NotNull Block block) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(block)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(block);
            walkStatements(block.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// Standalone expression statements keep their nested expression tree on compile surface.
        @Override
        public @NotNull FrontendASTTraversalDirective handleExpressionStatement(
                @NotNull ExpressionStatement expressionStatement
        ) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(expressionStatement);
            walkExpression(expressionStatement.expression());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// Return statements contribute to compile surface only when they actually materialize a value.
        @Override
        public @NotNull FrontendASTTraversalDirective handleReturnStatement(@NotNull ReturnStatement returnStatement) {
            if (supportedExecutableBlockDepth <= 0 || returnStatement.value() == null) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(returnStatement);
            walkExpression(returnStatement.value());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// `assert` is deliberately compile-blocked until lowering/backend own its semantics.
        @Override
        public @NotNull FrontendASTTraversalDirective handleAssertStatement(@NotNull AssertStatement assertStatement) {
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            reportExplicitCompileBlock(assertStatement, assertCompileBlockedMessage());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// Variable declarations participate differently depending on whether they are executable locals,
        /// blocked static properties, or supported property initializers.
        @Override
        public @NotNull FrontendASTTraversalDirective handleVariableDeclaration(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            if (supportedExecutableBlockDepth > 0) {
                if (variableDeclaration.kind() != DeclarationKind.VAR) {
                    return FrontendASTTraversalDirective.SKIP_CHILDREN;
                }
                markCompileSurfaceNode(variableDeclaration);
                if (variableDeclaration.value() != null) {
                    walkExpression(variableDeclaration.value());
                }
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (isStaticClassPropertyDeclaration(variableDeclaration)) {
                reportExplicitCompileBlock(
                        variableDeclaration,
                        staticPropertyCompileBlockedMessage(variableDeclaration.name())
                );
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (!FrontendPropertyInitializerSupport.isSupportedPropertyInitializer(scopesByAst, variableDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(variableDeclaration);
            walkExpression(Objects.requireNonNull(
                    variableDeclaration.value(),
                    "property initializer value must not be null"
            ));
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// `if` contributes its condition and each reachable branch body to compile surface.
        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(ifStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(ifStatement);
            walkExpression(ifStatement.condition());
            walkSupportedExecutableBlock(ifStatement.body());
            for (var elifClause : ifStatement.elifClauses()) {
                astWalker.walk(elifClause);
            }
            if (ifStatement.elseBody() != null) {
                walkSupportedExecutableBlock(ifStatement.elseBody());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// `elif` reuses the same compile-surface rules as the parent `if` chain.
        @Override
        public @NotNull FrontendASTTraversalDirective handleElifClause(@NotNull ElifClause elifClause) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(elifClause)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(elifClause);
            walkExpression(elifClause.condition());
            walkSupportedExecutableBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// `while` stays on compile surface because CFG/lowering already owns the loop route.
        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(whileStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(whileStatement);
            walkExpression(whileStatement.condition());
            walkSupportedExecutableBlock(whileStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// `for` remains outside compile mode until the lowering/backend route is implemented.
        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// `match` remains outside compile mode until the lowering/backend route is implemented.
        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// Enter one published callable body without leaking executable depth into sibling declarations.
        private void walkCallableBody(@NotNull Node callableOwner, @Nullable Block body) {
            if (isNotPublished(callableOwner) || isNotPublished(body)) {
                return;
            }
            walkSupportedExecutableBlock(body);
        }

        /// Replay the AST walker over a flat statement list in source order.
        private void walkStatements(@NotNull java.util.List<Statement> statements) {
            for (var statement : statements) {
                astWalker.walk(statement);
            }
        }

        /// Traverse declarations with executable depth pinned to zero so nested bodies must opt in explicitly.
        private void walkNonExecutableContainerStatements(@NotNull java.util.List<Statement> statements) {
            var previousDepth = supportedExecutableBlockDepth;
            supportedExecutableBlockDepth = 0;
            try {
                walkStatements(statements);
            } finally {
                supportedExecutableBlockDepth = previousDepth;
            }
        }

        /// Enter one lowering-ready executable block and restore the outer depth on exit.
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

        /// Mark supported expressions, while immediately blocking syntax forms whose lowering contract is absent.
        private void walkExpression(@Nullable Expression expression) {
            if (expression == null) {
                return;
            }
            switch (expression) {
                case LambdaExpression _ -> {
                    // Lambdas remain outside the current compile surface and keep their upstream
                    // unsupported-subtree owner.
                }
                case ConditionalExpression conditionalExpression -> reportExplicitCompileBlock(
                        conditionalExpression,
                        conditionalCompileBlockedMessage()
                );
                case ArrayExpression arrayExpression -> reportExplicitCompileBlock(
                        arrayExpression,
                        expressionCompileBlockedMessage("Array literal")
                );
                case DictionaryExpression dictionaryExpression -> reportExplicitCompileBlock(
                        dictionaryExpression,
                        expressionCompileBlockedMessage("Dictionary literal")
                );
                case PreloadExpression preloadExpression -> reportExplicitCompileBlock(
                        preloadExpression,
                        expressionCompileBlockedMessage("Preload expression")
                );
                case GetNodeExpression getNodeExpression -> reportExplicitCompileBlock(
                        getNodeExpression,
                        expressionCompileBlockedMessage("Get-node expression")
                );
                case CastExpression castExpression -> reportExplicitCompileBlock(
                        castExpression,
                        expressionCompileBlockedMessage("Cast expression")
                );
                case TypeTestExpression typeTestExpression -> reportExplicitCompileBlock(
                        typeTestExpression,
                        expressionCompileBlockedMessage("Type-test expression")
                );
                default -> {
                    markCompileSurfaceNode(expression);
                    walkNestedExpressionChildren(expression);
                }
            }
        }

        /// Some nodes such as `DictionaryExpression` wrap real expression payload under non-expression
        /// containers, so compile-surface scanning needs to recurse until it reaches nested
        /// expressions instead of assuming one child level is enough.
        private void walkNestedExpressionChildren(@NotNull Node node) {
            for (var child : node.getChildren()) {
                if (child instanceof Expression childExpression) {
                    walkExpression(childExpression);
                    continue;
                }
                markCompileSurfaceNode(child);
                walkNestedExpressionChildren(child);
            }
        }

        /// Re-check all published fact tables once compile surface marking is complete.
        private void scanPublishedCompileBlocks() {
            scanExpressionTypeCompileBlocks();
            scanResolvedMemberCompileBlocks();
            scanResolvedCallCompileBlocks();
            scanSlotTypeCompileBlocks();
        }

        /// Any blocked/deferred expression type that still sits on compile surface must stop compilation.
        private void scanExpressionTypeCompileBlocks() {
            for (var entry : expressionTypes.entrySet()) {
                var anchor = requireExpressionTypeAnchor(entry.getKey());
                var publishedType = Objects.requireNonNull(entry.getValue(), "publishedType must not be null");
                if (!isCompileBlocking(publishedType.status()) || !compileSurfaceNodes.contains(anchor)) {
                    continue;
                }
                if (isAssignmentRootCoveredByExplicitSelfPrefixDiagnostic(anchor, publishedType)) {
                    continue;
                }
                var compileAnchor = compileAnchorForExpressionType(anchor);
                if (!compileSurfaceNodes.contains(compileAnchor)) {
                    continue;
                }
                reportCompileBlock(
                        compileAnchor,
                        publishedCompileBlockedMessage(
                                describeExpressionTypeAnchor(anchor),
                                publishedType.status(),
                                publishedType.detailReason()
                        )
                );
            }
        }

        /// Member facts are reported at the exact property-step anchor to keep diagnostics precise.
        private void scanResolvedMemberCompileBlocks() {
            for (var entry : resolvedMembers.entrySet()) {
                var anchor = requireAttributePropertyStep(entry.getKey());
                var publishedMember = Objects.requireNonNull(entry.getValue(), "publishedMember must not be null");
                if (!isCompileBlocking(publishedMember.status()) || !compileSurfaceNodes.contains(anchor)) {
                    continue;
                }
                reportCompileBlock(
                        anchor,
                        publishedCompileBlockedMessage(
                                "Member access '" + publishedMember.memberName() + "'",
                                publishedMember.status(),
                                publishedMember.detailReason()
                        )
                );
            }
        }

        /// Call facts stop compile mode either because upstream resolution already failed, or because a
        /// route that used to resolve is still outside the supported compile-time contract.
        private void scanResolvedCallCompileBlocks() {
            for (var entry : resolvedCalls.entrySet()) {
                var anchor = requireCallAnchor(entry.getKey());
                var publishedCall = Objects.requireNonNull(entry.getValue(), "publishedCall must not be null");
                if (shouldBlockParameterizedGdccConstructor(anchor, publishedCall)) {
                    reportCompileBlock(
                            anchor,
                            gdccParameterizedConstructorCompileBlockedMessage(publishedCall),
                            true
                    );
                    continue;
                }
                if (!isCompileBlocking(publishedCall.status()) || !compileSurfaceNodes.contains(anchor)) {
                    continue;
                }
                reportCompileBlock(
                        anchor,
                        publishedCompileBlockedMessage(
                                describeCallAnchor(anchor, publishedCall),
                                publishedCall.status(),
                                publishedCall.detailReason()
                        )
                );
            }
        }

        /// Compile mode keeps a dedicated guard for GDCC parameterized constructors so regressions are
        /// still caught even if an upstream semantic change accidentally republishes the route as resolved.
        private static boolean shouldBlockParameterizedGdccConstructor(
                @NotNull Node anchor,
                @NotNull FrontendResolvedCall publishedCall
        ) {
            if (publishedCall.callKind() != FrontendCallResolutionKind.CONSTRUCTOR
                    || publishedCall.ownerKind() != ScopeOwnerKind.GDCC) {
                return false;
            }
            return switch (anchor) {
                case AttributeCallStep attributeCallStep -> !attributeCallStep.arguments().isEmpty();
                case CallExpression callExpression -> !callExpression.arguments().isEmpty();
                default -> false;
            };
        }

        /// Callable-local slot types are a lowering-only published fact. When the post analyzer had
        /// to warn that a supported declaration could not publish its slot type, compile mode must
        /// still stop even if the original publication issue was only emitted as a warning.
        private void scanSlotTypeCompileBlocks() {
            for (var compileSurfaceNode : compileSurfaceNodes) {
                if (!(compileSurfaceNode instanceof VariableDeclaration variableDeclaration)) {
                    continue;
                }
                if (!isSupportedCallableLocalDeclaration(variableDeclaration) || slotTypes.containsKey(variableDeclaration)) {
                    continue;
                }
                var publicationDiagnostic = findPublishedDiagnosticAt(
                        variableDeclaration,
                        diagnostic -> diagnostic.severity() != FrontendDiagnosticSeverity.ERROR
                                && NON_ERROR_BLOCKING_DIAGNOSTIC_CATEGORIES.contains(diagnostic.category())
                );
                if (publicationDiagnostic == null) {
                    continue;
                }
                // The upstream warning explains why `slotTypes()` is missing, while compile mode
                // still needs its own final hard stop before lowering starts.
                reportCompileBlock(
                        variableDeclaration,
                        slotTypeCompileBlockedMessage(variableDeclaration, publicationDiagnostic),
                        isNonConflictingPublishedDiagnostic(publicationDiagnostic)
                );
            }
        }

        /// Attribute expression typing often mirrors the final member/call step fact, so compile
        /// anchoring prefers that exact step to avoid reporting both the outer expression and the
        /// terminal chain step as separate generic compile blockers.
        private @NotNull Node compileAnchorForExpression(@NotNull Expression expression) {
            if (expression instanceof AttributeExpression attributeExpression && !attributeExpression.steps().isEmpty()) {
                var finalStep = attributeExpression.steps().getLast();
                if ((finalStep instanceof AttributePropertyStep || finalStep instanceof AttributeCallStep)
                        && compileSurfaceNodes.contains(finalStep)) {
                    return finalStep;
                }
            }
            return expression;
        }

        /// Expression-type facts anchored on full expressions may be remapped to a more specific terminal step.
        private @NotNull Node compileAnchorForExpressionType(@NotNull Node node) {
            if (node instanceof Expression expression) {
                return compileAnchorForExpression(expression);
            }
            return node;
        }

        /// Assignment root facts can propagate a prefix-owned blocked `self` route. When that exact
        /// prefix already has the upstream binding diagnostic, keep ownership there instead of adding
        /// a generic root-level compile blocker for the same cause.
        private boolean isAssignmentRootCoveredByExplicitSelfPrefixDiagnostic(
                @NotNull Node anchor,
                @NotNull FrontendExpressionType publishedType
        ) {
            if (!(anchor instanceof AssignmentExpression assignmentExpression)) {
                return false;
            }
            var selfExpression = directExplicitSelfAssignmentTargetPrefixOrNull(assignmentExpression);
            if (selfExpression == null) {
                return false;
            }
            var selfType = expressionTypes.get(selfExpression);
            if (selfType == null
                    || selfType.status() != publishedType.status()
                    || !isCompileBlocking(selfType.status())) {
                return false;
            }
            return hasPublishedConflictingDiagnosticAt(selfExpression);
        }

        private static @Nullable SelfExpression directExplicitSelfAssignmentTargetPrefixOrNull(
                @NotNull AssignmentExpression assignmentExpression
        ) {
            if (!"=".equals(assignmentExpression.operator())
                    || !(assignmentExpression.left() instanceof AttributeExpression attributeExpression)
                    || !(attributeExpression.base() instanceof SelfExpression selfExpression)
                    || attributeExpression.steps().size() != 1
                    || !(attributeExpression.steps().getFirst() instanceof AttributePropertyStep)) {
                return null;
            }
            return selfExpression;
        }

        /// Validate the key shape used by `expressionTypes()` before compile diagnostics rely on it.
        private static @NotNull Node requireExpressionTypeAnchor(@NotNull Node node) {
            if (node instanceof Expression
                    || node instanceof AttributePropertyStep
                    || node instanceof AttributeCallStep
                    || node instanceof AttributeSubscriptStep) {
                return node;
            }
            throw new IllegalStateException(
                    "expressionTypes must be keyed by Expression / AttributePropertyStep / "
                            + "AttributeCallStep / AttributeSubscriptStep"
            );
        }

        /// Member facts must stay anchored at the exact property step that produced them.
        private static @NotNull AttributePropertyStep requireAttributePropertyStep(@NotNull Node node) {
            if (node instanceof AttributePropertyStep attributePropertyStep) {
                return attributePropertyStep;
            }
            throw new IllegalStateException("resolvedMembers must be keyed by attribute property steps");
        }

        /// Call facts may be anchored either at an attribute step (`foo.bar()`) or a bare call expression.
        private static @NotNull Node requireCallAnchor(@NotNull Node node) {
            if (node instanceof AttributeCallStep || node instanceof CallExpression) {
                return node;
            }
            throw new IllegalStateException("resolvedCalls must be keyed by attribute call steps or CallExpression");
        }

        /// Render a stable human-facing label for generic expression-root diagnostics.
        private static @NotNull String describeExpression(@NotNull Expression expression) {
            return switch (Objects.requireNonNull(expression, "expression must not be null")) {
                case AttributeExpression _ -> "Attribute expression";
                default -> "Expression";
            };
        }

        /// Render the precise surface label used when an expression-type fact blocks compilation.
        private static @NotNull String describeExpressionTypeAnchor(@NotNull Node node) {
            return switch (Objects.requireNonNull(node, "node must not be null")) {
                case AttributePropertyStep attributePropertyStep ->
                        "Member access '" + attributePropertyStep.name() + "'";
                case AttributeCallStep attributeCallStep -> "Call step '" + attributeCallStep.name() + "(...)'";
                case AttributeSubscriptStep attributeSubscriptStep ->
                        "Subscript step '" + attributeSubscriptStep.name() + "[...]'";
                case Expression expression -> describeExpression(expression);
                default -> throw new IllegalStateException(
                        "unexpected expressionTypes anchor: " + node.getClass().getSimpleName()
                );
            };
        }

        /// Render the user-facing call label that pairs with generic published-call blocker messages.
        private static @NotNull String describeCallAnchor(
                @NotNull Node anchor,
                @NotNull FrontendResolvedCall publishedCall
        ) {
            return switch (anchor) {
                case AttributeCallStep _ -> "Call step '" + publishedCall.callableName() + "'";
                case CallExpression _ -> "Call expression '" + publishedCall.callableName() + "(...)'";
                default ->
                        throw new IllegalStateException("unexpected call anchor: " + anchor.getClass().getSimpleName());
            };
        }

        /// Emit an explicit syntax blocker and remember the anchor as part of compile surface.
        private void reportExplicitCompileBlock(@NotNull Node anchor, @NotNull String message) {
            markCompileSurfaceNode(anchor);
            reportCompileBlock(anchor, message);
        }

        /// Convenience overload for the common dedup-aware compile blocker path.
        private void reportCompileBlock(@NotNull Node anchor, @NotNull String message) {
            reportCompileBlock(anchor, message, false);
        }

        /// Emit one compile diagnostic unless the anchor has already been handled or an upstream
        /// conflicting error already owns that exact source range.
        private void reportCompileBlock(
                @NotNull Node anchor,
                @NotNull String message,
                boolean skipPublishedConflictDedup
        ) {
            Objects.requireNonNull(anchor, "anchor must not be null");
            Objects.requireNonNull(message, "message must not be null");
            if (!skipPublishedConflictDedup && hasPublishedConflictingDiagnosticAt(anchor)) {
                return;
            }
            if (!handledAnchors.add(anchor)) {
                return;
            }
            diagnosticManager.error(
                    COMPILE_CHECK_CATEGORY,
                    message,
                    sourcePath,
                    FrontendRange.fromAstRange(anchor.range())
            );
        }

        /// Return whether a non-whitelisted upstream blocker already owns this exact source location.
        private boolean hasPublishedConflictingDiagnosticAt(@NotNull Node anchor) {
            return findPublishedDiagnosticAt(
                    anchor,
                    diagnostic -> isCompileBlockingPublishedDiagnostic(diagnostic)
                            && !isNonConflictingPublishedDiagnostic(diagnostic)
            ) != null;
        }

        /// Find one previously published diagnostic that exactly matches the anchor range in the same file.
        private @Nullable FrontendDiagnostic findPublishedDiagnosticAt(
                @NotNull Node anchor,
                @NotNull java.util.function.Predicate<FrontendDiagnostic> predicate
        ) {
            var anchorRange = FrontendRange.fromAstRange(anchor.range());
            return publishedDiagnostics.asList().stream()
                    .filter(diagnostic -> Objects.equals(
                            diagnostic.sourcePath(),
                            FrontendDiagnostic.sourcePathText(sourcePath)
                    ))
                    .filter(diagnostic -> Objects.equals(diagnostic.range(), anchorRange))
                    .filter(predicate)
                    .findFirst()
                    .orElse(null);
        }

        /// Compile mode blocks on upstream errors and on the narrow warning categories that represent
        /// missing lowering-ready publication.
        private boolean isCompileBlockingPublishedDiagnostic(@NotNull FrontendDiagnostic diagnostic) {
            return diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                    || NON_ERROR_BLOCKING_DIAGNOSTIC_CATEGORIES.contains(diagnostic.category());
        }

        /// Some upstream diagnostics explain the same missing fact and should coexist with compile_check.
        private boolean isNonConflictingPublishedDiagnostic(@NotNull FrontendDiagnostic diagnostic) {
            return NON_CONFLICTING_UPSTREAM_DIAGNOSTIC_CATEGORIES.containsKey(diagnostic.category());
        }

        /// Compose the final compile-only hard-stop message for missing callable-local slot publication.
        private static @NotNull String slotTypeCompileBlockedMessage(
                @NotNull VariableDeclaration variableDeclaration,
                @NotNull FrontendDiagnostic publicationDiagnostic
        ) {
            return "Local variable '"
                    + variableDeclaration.name().trim()
                    + "' is missing a lowering-ready published slot type in compile mode: "
                    + publicationDiagnostic.message();
        }

        /// Record one AST node as reachable by the current compile-ready surface.
        private void markCompileSurfaceNode(@NotNull Node node) {
            compileSurfaceNodes.add(Objects.requireNonNull(node, "node must not be null"));
        }

        /// Static top-level/class properties are explicitly blocked because the backend has no script-static storage.
        private boolean isStaticClassPropertyDeclaration(@NotNull VariableDeclaration variableDeclaration) {
            return Objects.requireNonNull(variableDeclaration, "variableDeclaration must not be null").kind() == DeclarationKind.VAR
                    && variableDeclaration.isStatic()
                    && scopesByAst.get(variableDeclaration) instanceof ClassScope;
        }

        /// Only callable-local `var` declarations in lowering-ready block inventories are expected to publish slot types.
        private boolean isSupportedCallableLocalDeclaration(@NotNull VariableDeclaration variableDeclaration) {
            return variableDeclaration.kind() == DeclarationKind.VAR
                    && scopesByAst.get(variableDeclaration) instanceof dev.superice.gdcc.frontend.scope.BlockScope blockScope
                    && FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(blockScope.kind());
        }

        /// Compile mode only reasons about nodes that survived the shared publication pipeline.
        private boolean isNotPublished(@Nullable Node node) {
            return node == null || !scopesByAst.containsKey(node);
        }
    }
}
