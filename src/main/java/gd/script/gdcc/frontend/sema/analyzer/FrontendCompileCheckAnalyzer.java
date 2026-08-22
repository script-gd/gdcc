package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.lowering.ForLoweringContractRegistry;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendExecutableInventorySupport;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendForIterationPlan;
import gd.script.gdcc.frontend.sema.FrontendForIterationRoute;
import gd.script.gdcc.frontend.sema.FrontendLambdaPlan;
import gd.script.gdcc.frontend.sema.FrontendMatchPlan;
import gd.script.gdcc.frontend.sema.FrontendMatchSupport;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.scope.Scope;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
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
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
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
import java.util.*;
import java.util.function.Predicate;

/// Compile-only final frontend gate that runs after the shared semantic pipeline.
///
/// The default shared `analyze(...)` path intentionally does not call this analyzer so inspection
/// and future LSP-style entrypoints can keep consuming raw frontend recovery facts. Compile-only
/// entrypoints invoke it as the final diagnostics-only barrier before lowering is allowed to start.
///
/// The gate itself stays deliberately narrow:
/// - explicit AST compile blocks for the currently recognized forms whose lowering/backend support
///   is not ready yet
/// - recorded lambdas (published `lambdaPlans()` entry + published body) are released onto the
///   compile surface and their bodies recursed; unrecorded lambdas (property initializer /
///   parameter default / skipped subtree) stay fail-closed behind a form-level blocker
/// - `MatchStatement` is shared-semantic supported but compile-ready only when every pattern
///   route is in `FrontendMatchSupport.isRouteLoweringReady`; WILDCARD / BINDING / LITERAL /
///   EXPRESSION are ready and their bodies are rescanned, while ARRAY / DICTIONARY still block
///   the whole statement at the root
/// - generic side-table scans over published `expressionTypes()` / `resolvedMembers()` /
///   `resolvedCalls()` facts that are still blocked/deferred/failed/unsupported on compile surface
/// - feature-specific RESOLVED blockers for Signal.connect/disconnect and bare
///   method-reference / static-method / utility-function value reads that would otherwise
///   crash CFG or be mis-lowered as property loads
/// - no new side tables and no rewrites of upstream semantic ownership
public class FrontendCompileCheckAnalyzer {
    private static final @NotNull String COMPILE_CHECK_CATEGORY = "sema.compile_check";
    /// Some upstream diagnostics explain a lowering-only gap instead of competing with the compile
    /// gate's own hard-stop diagnostic. Those categories stay configurable here so future warning-
    /// based blockers do not need another dedicated ignore-upstream branch.
    private static final @NotNull Map<String, String> NON_CONFLICTING_UPSTREAM_DIAGNOSTIC_CATEGORIES = Map.of(
            FrontendBodyOwnerProcedures.VARIABLE_SLOT_PUBLICATION_CATEGORY,
            "slot-publication warning explains the missing lowering-ready fact and must coexist with compile_check"
    );
    /// Compile mode usually blocks only on upstream `ERROR`s. This set is the narrow exception list
    /// for already-published non-error diagnostics that still represent a lowering-blocking gap.
    private static final @NotNull Set<String> NON_ERROR_BLOCKING_DIAGNOSTIC_CATEGORIES = Set.of(
            FrontendBodyOwnerProcedures.VARIABLE_SLOT_PUBLICATION_CATEGORY
    );
    /// Object/self, builtin instance, static, and utility value reads are already released.
    /// Keep this empty so callee-exclusion tests stay on the same scan path.
    private static final @NotNull Set<FrontendBindingKind> BARE_VALUE_REFERENCE_BINDING_KINDS = Set.of();

    public void analyze(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var moduleSkeleton = analysisData.moduleSkeleton();
        // Keep the stable boundary precondition even though dedup reads the live manager below.
        analysisData.diagnostics();
        // Freeze the live manager at compile-gate entry. This captures the latest interface/body
        // upstream diagnostics even if a caller has not yet copied that manager state back into
        // `FrontendAnalysisData`, while still preventing diagnostics emitted by this gate from
        // suppressing later checks in the same run.
        var publishedDiagnostics = diagnosticManager.snapshot();
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
                    analysisData.symbolBindings(),
                    analysisData.expressionTypes(),
                    analysisData.resolvedMembers(),
                    analysisData.resolvedCalls(),
                    analysisData.slotTypes(),
                    analysisData.forIterationPlans(),
                    analysisData.matchPlans(),
                    analysisData.lambdaPlans(),
                    diagnosticManager
            ).walk(sourceClassRelation.unit().ast());
        }
    }

    private static @NotNull String assertCompileBlockedMessage() {
        return "assert statement is recognized by the frontend but is blocked in compile mode because "
                + "lowering/backend support lands";
    }

    private static @NotNull String expressionCompileBlockedMessage(@NotNull String expressionKind) {
        return Objects.requireNonNull(expressionKind, "expressionKind must not be null")
                + " is recognized by the frontend but is blocked in compile mode because "
                + "lowering support lands";
    }

    /// A lambda that reaches the compile surface without a published plan is unrecorded: property
    /// initializer / parameter default / skipped subtrees never publish a plan or body facts. The
    /// gate keeps those positions fail-closed instead of silently releasing them for lowering.
    private static @NotNull String unrecordedLambdaCompileBlockedMessage() {
        return "Lambda expression has no published lambda plan on this compile path and stays blocked in "
                + "compile mode; only lambdas recorded inside supported executable bodies are compile-ready";
    }

    /// Route-not-ready blocker for a `for-in` whose iteration route has no registered lowering contract.
    /// The message names the missing lowering route rather than reporting the loop as an unsupported
    /// subtree, so the gap is attributed to the absent contract instead of shared semantic support.
    private static @NotNull String forRouteNotReadyMessage(@NotNull FrontendForIterationRoute route) {
        return "for-in loop is recognized by shared semantic analysis but is blocked in compile mode because its '"
                + Objects.requireNonNull(route, "route must not be null")
                + "' iteration route has no registered lowering contract yet";
    }

    /// Route-not-ready blocker for a `match` whose pattern routes are not yet lowering-ready.
    /// The message names the missing lowering route rather than reporting the match as an
    /// unsupported subtree. All six routes are currently ready; an unready route still blocks
    /// the whole statement at the root and skips the body rescan.
    private static @NotNull String matchRouteNotReadyMessage() {
        return "match statement is recognized by shared semantic analysis but is blocked in compile mode because "
                + "it contains a pattern route that is not lowering-ready yet";
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

    /// Feature-specific compile-only message for residual method-references that still cannot
    /// become `construct_callable` or `construct_standalone_callable`.
    private static @NotNull String unsupportedMethodReferenceCompileBlockedMessage(
            @NotNull FrontendResolvedMember publishedMember
    ) {
        var kindLabel = switch (Objects.requireNonNull(publishedMember, "publishedMember must not be null").bindingKind()) {
            case METHOD -> "method-reference";
            case STATIC_METHOD -> "static-method";
            default -> throw new IllegalStateException(
                    "unexpected method-reference kind: " + publishedMember.bindingKind()
            );
        };
        return "Qualified " + kindLabel + " '" + publishedMember.memberName()
                + "' is recognized by the frontend but is blocked in compile mode because "
                + "only Object/self, non-Dictionary builtin instance, and GDCC/engine static "
                + "method-references can materialize as Callable";
    }

    /// Feature-specific compile-only message for bare STATIC_METHOD / UTILITY_FUNCTION value reads.
    /// Kind is taken from the published binding so the helper does not guess from expression type.
    /// Bare METHOD value reads are intentionally excluded because they already materialize.
    private static @NotNull String bareValueReferenceCompileBlockedMessage(@NotNull FrontendBinding binding) {
        var kindLabel = switch (Objects.requireNonNull(binding, "binding must not be null").kind()) {
            case STATIC_METHOD -> "static-method";
            case UTILITY_FUNCTION -> "utility-function";
            default -> throw new IllegalStateException("unexpected bare value-reference kind: " + binding.kind());
        };
        return "Bare " + kindLabel + " '" + binding.symbolName()
                + "' is recognized by the frontend but is blocked in compile mode because "
                + "value-reference lowering support lands";
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
        private final @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings;
        private final @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes;
        private final @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers;
        private final @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls;
        private final @NotNull FrontendAstSideTable<GdType> slotTypes;
        private final @NotNull FrontendAstSideTable<FrontendForIterationPlan> forIterationPlans;
        private final @NotNull FrontendAstSideTable<FrontendMatchPlan> matchPlans;
        private final @NotNull FrontendAstSideTable<FrontendLambdaPlan> lambdaPlans;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull Set<Node> compileSurfaceNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        private final @NotNull Set<Node> handledAnchors = Collections.newSetFromMap(new IdentityHashMap<>());
        private final @NotNull Set<Node> bareCallCallees = Collections.newSetFromMap(new IdentityHashMap<>());
        private int supportedExecutableBlockDepth;

        /// Capture the shared semantic facts for one source file and prepare a dedicated walker.
        private AstWalkerCompileCheckVisitor(
                @NotNull Path sourcePath,
                @NotNull DiagnosticSnapshot publishedDiagnostics,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
                @NotNull FrontendAstSideTable<FrontendExpressionType> expressionTypes,
                @NotNull FrontendAstSideTable<FrontendResolvedMember> resolvedMembers,
                @NotNull FrontendAstSideTable<FrontendResolvedCall> resolvedCalls,
                @NotNull FrontendAstSideTable<GdType> slotTypes,
                @NotNull FrontendAstSideTable<FrontendForIterationPlan> forIterationPlans,
                @NotNull FrontendAstSideTable<FrontendMatchPlan> matchPlans,
                @NotNull FrontendAstSideTable<FrontendLambdaPlan> lambdaPlans,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            this.publishedDiagnostics = Objects.requireNonNull(
                    publishedDiagnostics,
                    "publishedDiagnostics must not be null"
            );
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
            this.symbolBindings = Objects.requireNonNull(symbolBindings, "symbolBindings must not be null");
            this.expressionTypes = Objects.requireNonNull(expressionTypes, "expressionTypes must not be null");
            this.resolvedMembers = Objects.requireNonNull(resolvedMembers, "resolvedMembers must not be null");
            this.resolvedCalls = Objects.requireNonNull(resolvedCalls, "resolvedCalls must not be null");
            this.slotTypes = Objects.requireNonNull(slotTypes, "slotTypes must not be null");
            this.forIterationPlans = Objects.requireNonNull(forIterationPlans, "forIterationPlans must not be null");
            this.matchPlans = Objects.requireNonNull(matchPlans, "matchPlans must not be null");
            this.lambdaPlans = Objects.requireNonNull(lambdaPlans, "lambdaPlans must not be null");
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

        /// `for` is shared-semantic supported; whether it is compile-ready is decided per route by the
        /// lowering contract registry. A route with a registered contract is released onto the compile
        /// surface (loop node, source operands and body); a route without one is blocked with a
        /// route-not-ready diagnostic instead of the old unconditional statement-root blocker.
        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(forStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            var plan = requirePublishedForIterationPlan(forStatement);
            if (ForLoweringContractRegistry.get(plan.route()) == null) {
                reportExplicitCompileBlock(forStatement, forRouteNotReadyMessage(plan.route()));
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(forStatement);
            for (var sourceOperand : plan.sourceOperands()) {
                walkExpression(sourceOperand);
            }
            walkSupportedExecutableBlock(forStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        /// `match` is shared-semantic supported. Compile readiness is route-aware: if any pattern
        /// route is not lowering-ready, the whole statement is blocked at the root and the body is
        /// not rescanned. Missing plans keep the upstream owner (fail-closed, no extra diagnostic).
        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(matchStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            var plan = matchPlans.get(matchStatement);
            if (plan == null) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (!allMatchRoutesReady(plan)) {
                reportExplicitCompileBlock(matchStatement, matchRouteNotReadyMessage());
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            markCompileSurfaceNode(matchStatement);
            walkExpression(matchStatement.value());
            for (var sectionPlan : plan.sections()) {
                for (var patternPlan : sectionPlan.patterns()) {
                    walkMatchPatternForCompileSurface(patternPlan.patternNode());
                }
                walkExpression(sectionPlan.section().guard());
                walkSupportedExecutableBlock(sectionPlan.section().body());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        private static boolean allMatchRoutesReady(@NotNull FrontendMatchPlan plan) {
            for (var sectionPlan : plan.sections()) {
                for (var patternPlan : sectionPlan.patterns()) {
                    if (!FrontendMatchSupport.isRouteLoweringReady(patternPlan.route())) {
                        return false;
                    }
                }
            }
            return true;
        }

        /// LITERAL / EXPRESSION leaves enter the ordinary compile-surface expression walk.
        /// BINDING is marked so missing `slotTypes()` can use the L49 hole upgrade; WILDCARD /
        /// ARRAY / DICTIONARY stay off the ordinary expression walk.
        private void walkMatchPatternForCompileSurface(@NotNull Expression pattern) {
            var route = FrontendMatchSupport.classifyPatternRoute(pattern);
            switch (route) {
                case WILDCARD -> {
                }
                case BINDING -> markCompileSurfaceNode(pattern);
                case ARRAY -> {
                    var array = (ArrayExpression) pattern;
                    for (var element : array.elements()) {
                        walkMatchPatternForCompileSurface(element);
                    }
                }
                case DICTIONARY -> {
                    var dictionary = (DictionaryExpression) pattern;
                    for (var entry : dictionary.entries()) {
                        walkExpression(entry.key());
                        walkMatchPatternForCompileSurface(entry.value());
                    }
                }
                case LITERAL, EXPRESSION -> walkExpression(pattern);
            }
        }

        /// Enter one published callable body without leaking executable depth into sibling declarations.
        private void walkCallableBody(@NotNull Node callableOwner, @Nullable Block body) {
            if (isNotPublished(callableOwner) || isNotPublished(body)) {
                return;
            }
            walkSupportedExecutableBlock(body);
        }

        /// Replay the AST walker over a flat statement list in source order.
        private void walkStatements(@NotNull List<Statement> statements) {
            for (var statement : statements) {
                astWalker.walk(statement);
            }
        }

        /// Traverse declarations with executable depth pinned to zero so nested bodies must opt in explicitly.
        private void walkNonExecutableContainerStatements(@NotNull List<Statement> statements) {
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
                case LambdaExpression lambdaExpression -> walkLambdaExpression(lambdaExpression);
                // ConditionalExpression: compile-ready via branch-result merge (value context) and
                // pure control-flow expansion (condition context); see
                // frontend_conditional_expression_implementation.md and
                // frontend_lowering_cfg_pass_implementation.md §5.1/§5.2.
                // ArrayExpression / DictionaryExpression: compile-ready via ContainerLiteralItem +
                // construct_container_literal (see frontend_container_literal_implementation.md).
                case PreloadExpression preloadExpression -> reportExplicitCompileBlock(
                        preloadExpression,
                        expressionCompileBlockedMessage("Preload expression")
                );
                case GetNodeExpression getNodeExpression -> reportExplicitCompileBlock(
                        getNodeExpression,
                        expressionCompileBlockedMessage("Get-node expression")
                );
                default -> {
                    markCompileSurfaceNode(expression);
                    rememberBareCallCallee(expression);
                    walkNestedExpressionChildren(expression);
                }
            }
        }

        /// A recorded lambda (published plan + published body) is compile-ready: release its node onto
        /// the surface and recurse into the body so its facts are scanned like any other executable
        /// block. An unrecorded lambda (property initializer / parameter default / skipped subtree)
        /// publishes no plan, so it stays fail-closed behind the form-level blocker; the gate never
        /// silently releases a lambda that lacks the plan/synthetic-function/lowering contract facts.
        private void walkLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            if (isNotPublished(lambdaExpression)
                    || isNotPublished(lambdaExpression.body())
                    || !lambdaPlans.containsKey(lambdaExpression)) {
                reportExplicitCompileBlock(lambdaExpression, unrecordedLambdaCompileBlockedMessage());
                return;
            }
            markCompileSurfaceNode(lambdaExpression);
            walkSupportedExecutableBlock(lambdaExpression.body());
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
            scanBareValueReferenceCompileBlocks();
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
                if (isAssignmentRootCoveredByExplicitSelfPrefixDiagnostic(anchor)) {
                    continue;
                }
                if (isCoveredByPropagatedValueOperandCompileBlock(anchor, publishedType)) {
                    continue;
                }
                if (isCoveredByStaticSelfBindingDiagnostic(publishedType.detailReason())) {
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
        /// RESOLVED SIGNAL / Object METHOD value reads now lower through dedicated items; residual
        /// BLOCKED/DEFERRED/FAILED/UNSUPPORTED members stay compile-blocked here.
        private void scanResolvedMemberCompileBlocks() {
            for (var entry : resolvedMembers.entrySet()) {
                var anchor = requireAttributePropertyStep(entry.getKey());
                var publishedMember = Objects.requireNonNull(entry.getValue(), "publishedMember must not be null");
                if (shouldBlockUnsupportedMethodReference(anchor, publishedMember)) {
                    reportCompileBlock(
                            anchor,
                            unsupportedMethodReferenceCompileBlockedMessage(publishedMember)
                    );
                    continue;
                }
                if (!isCompileBlocking(publishedMember.status()) || !compileSurfaceNodes.contains(anchor)) {
                    continue;
                }
                if (isCoveredByStaticSelfBindingDiagnostic(publishedMember.detailReason())) {
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

        /// Residual method-references: Dictionary instance keys and builtin type-meta methods.
        /// DYNAMIC facts stay runtime-open, matching the generic status exemption.
        private boolean shouldBlockUnsupportedMethodReference(
                @NotNull Node anchor,
                @NotNull FrontendResolvedMember publishedMember
        ) {
            if (!compileSurfaceNodes.contains(anchor)
                    || publishedMember.status() != FrontendMemberResolutionStatus.RESOLVED) {
                return false;
            }
            if (publishedMember.bindingKind() == FrontendBindingKind.STATIC_METHOD) {
                return publishedMember.ownerKind() == ScopeOwnerKind.BUILTIN;
            }
            return publishedMember.bindingKind() == FrontendBindingKind.METHOD
                    && publishedMember.ownerKind() == ScopeOwnerKind.BUILTIN
                    && publishedMember.receiverType() instanceof GdDictionaryType;
        }

        /// `BARE_VALUE_REFERENCE_BINDING_KINDS` is empty. This scan stays as the
        /// callee-exclusion hook if a later change re-blocks a bare kind.
        private void scanBareValueReferenceCompileBlocks() {
            for (var entry : symbolBindings.entrySet()) {
                // The published table also keys LiteralExpression / SelfExpression. Those are
                // never the CFG crash surface this blocker exists to stop.
                if (!(entry.getKey() instanceof IdentifierExpression identifierExpression)) {
                    continue;
                }
                var binding = Objects.requireNonNull(entry.getValue(), "binding must not be null");
                if (!compileSurfaceNodes.contains(identifierExpression)
                        || bareCallCallees.contains(identifierExpression)
                        || !BARE_VALUE_REFERENCE_BINDING_KINDS.contains(binding.kind())) {
                    continue;
                }
                reportCompileBlock(identifierExpression, bareValueReferenceCompileBlockedMessage(binding));
            }
        }

        /// Record a surface `CallExpression.callee()` so the later binding scan can exclude it.
        private void rememberBareCallCallee(@NotNull Expression expression) {
            if (expression instanceof CallExpression callExpression
                    && callExpression.callee() instanceof IdentifierExpression callee) {
                bareCallCallees.add(callee);
            }
        }

        /// Callable-local slot types are a lowering-only published fact. When the post analyzer had
        /// to warn that a supported declaration could not publish its slot type, compile mode must
        /// still stop even if the original publication issue was only emitted as a warning.
        /// Pattern binds use the same latch once the match is on the compile surface.
        private void scanSlotTypeCompileBlocks() {
            for (var compileSurfaceNode : compileSurfaceNodes) {
                if (compileSurfaceNode instanceof VariableDeclaration variableDeclaration) {
                    scanMissingSlotType(
                            variableDeclaration,
                            isSupportedCallableLocalDeclaration(variableDeclaration),
                            slotTypeCompileBlockedMessage(variableDeclaration)
                    );
                    continue;
                }
                if (compileSurfaceNode instanceof PatternBindingExpression patternBinding) {
                    scanMissingSlotType(
                            patternBinding,
                            isSupportedPatternBindDeclaration(patternBinding),
                            slotTypeCompileBlockedMessage(patternBinding)
                    );
                }
            }
        }

        private void scanMissingSlotType(
                @NotNull Node declaration,
                boolean supported,
                @NotNull java.util.function.Function<FrontendDiagnostic, String> message
        ) {
            if (!supported || slotTypes.containsKey(declaration)) {
                return;
            }
            var publicationDiagnostic = findPublishedDiagnosticAt(
                    declaration,
                    diagnostic -> diagnostic.severity() != FrontendDiagnosticSeverity.ERROR
                            && NON_ERROR_BLOCKING_DIAGNOSTIC_CATEGORIES.contains(diagnostic.category())
            );
            if (publicationDiagnostic == null) {
                return;
            }
            reportCompileBlock(
                    declaration,
                    message.apply(publicationDiagnostic),
                    isNonConflictingPublishedDiagnostic(publicationDiagnostic)
            );
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
        private boolean isAssignmentRootCoveredByExplicitSelfPrefixDiagnostic(@NotNull Node anchor) {
            if (!(anchor instanceof AssignmentExpression assignmentExpression)) {
                return false;
            }
            var selfExpression = directExplicitSelfAssignmentTargetPrefixOrNull(assignmentExpression);
            if (selfExpression == null) {
                return false;
            }
            return hasPublishedConflictingDiagnosticAt(selfExpression);
        }

        private boolean isCoveredByStaticSelfBindingDiagnostic(@Nullable String detailReason) {
            if (detailReason == null || !detailReason.contains("Keyword 'self' is not available in static context")) {
                return false;
            }
            return publishedDiagnostics.asList().stream().anyMatch(diagnostic ->
                    diagnostic.category().equals("sema.binding")
                            && diagnostic.message().contains("Keyword 'self' is not available in static context")
                            && (diagnostic.sourcePath() != null && diagnostic.sourcePath().equals(FrontendDiagnostic.sourcePathText(sourcePath)))
            );
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

        /// Cast/type-test roots that only echo a dependency's blocking fact must not add a second
        /// compile_check: either the value operand already owns an upstream blocking diagnostic at
        /// its exact range, or it carries the identical fact on compile surface and will be scanned
        /// at its own anchor.
        ///
        /// Current invariant: only cast and type-test record `rootOwnsOutcome` into body ownership
        /// and skip re-emitting root diagnostics when propagated. Binary/unary/call still re-own the
        /// root range, so exact-range conflict dedup covers them. Prefer a shared ownership signal
        /// over growing this AST-kind switch if more kinds publish non-root-owned facts.
        /// Relies on `propagated(...)` forwarding the same status + detailReason as the dependency.
        private boolean isCoveredByPropagatedValueOperandCompileBlock(
                @NotNull Node anchor,
                @NotNull FrontendExpressionType publishedType
        ) {
            if (anchor instanceof CastExpression castExpression) {
                return isPropagatedValueOperandCovered(castExpression.value(), publishedType);
            }
            if (anchor instanceof TypeTestExpression typeTestExpression) {
                return isPropagatedValueOperandCovered(typeTestExpression.value(), publishedType);
            }
            return false;
        }

        private boolean isPropagatedValueOperandCovered(
                @NotNull Expression operand,
                @NotNull FrontendExpressionType publishedType
        ) {
            if (hasPublishedConflictingDiagnosticAt(operand)) {
                return true;
            }
            var operandType = expressionTypes.get(operand);
            return operandType != null
                    && operandType.status() == publishedType.status()
                    && Objects.equals(operandType.detailReason(), publishedType.detailReason())
                    && (compileSurfaceNodes.contains(operand)
                    || compileSurfaceNodes.contains(compileAnchorForExpressionType(operand)));
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
                @NotNull Predicate<FrontendDiagnostic> predicate
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
        private static @NotNull java.util.function.Function<FrontendDiagnostic, String> slotTypeCompileBlockedMessage(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            return publicationDiagnostic -> "Local variable '"
                    + variableDeclaration.name().trim()
                    + "' is missing a lowering-ready published slot type in compile mode: "
                    + publicationDiagnostic.message();
        }

        private static @NotNull java.util.function.Function<FrontendDiagnostic, String> slotTypeCompileBlockedMessage(
                @NotNull PatternBindingExpression patternBinding
        ) {
            return publicationDiagnostic -> "Pattern binding '"
                    + patternBinding.name()
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
                    && scopesByAst.get(variableDeclaration) instanceof BlockScope blockScope
                    && FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(blockScope.kind());
        }

        private boolean isSupportedPatternBindDeclaration(@NotNull PatternBindingExpression patternBinding) {
            return scopesByAst.get(patternBinding) instanceof BlockScope blockScope
                    && FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(blockScope.kind());
        }

        /// The iteration plan is the single route truth and must already be published by the for-iteration
        /// resolution owner before the compile gate runs (type-check enforces the same boundary). A missing
        /// plan means the upstream phase boundary was not honored, so this path fails fast instead of
        /// masking the invariant break as an ordinary compile block.
        private @NotNull FrontendForIterationPlan requirePublishedForIterationPlan(@NotNull ForStatement forStatement) {
            Objects.requireNonNull(forStatement, "forStatement must not be null");
            var plan = forIterationPlans.get(forStatement);
            if (plan != null) {
                return plan;
            }
            throw new IllegalStateException("for-in iteration plan has not been published for ForStatement");
        }

        /// Compile mode only reasons about nodes that survived the shared publication pipeline.
        private boolean isNotPublished(@Nullable Node node) {
            return node == null || !scopesByAst.containsKey(node);
        }
    }
}
