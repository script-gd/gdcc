package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.CallableScope;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendDeclaredTypeSupport;
import dev.superice.gdcc.frontend.sema.FrontendExecutableInventorySupport;
import dev.superice.gdcc.frontend.sema.FrontendModuleSkeleton;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeValue;
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
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Frontend parameter/local inventory analyzer.
///
/// Current responsibilities are frozen as:
/// - require skeleton, diagnostics, and top-level source scopes to be published first
/// - write function/constructor parameters into `CallableScope`
/// - write supported ordinary locals into `BlockScope`
/// - keep lambda / `for` / `match` / block-local `const` inventory outside the current support
///   boundary
/// - emit explicit recovery diagnostics instead of letting unsupported inventory sources fail silently
public class FrontendVariableAnalyzer {
    private static final @NotNull String VARIABLE_BINDING_CATEGORY = "sema.variable_binding";
    private static final @NotNull String UNSUPPORTED_PARAMETER_DEFAULT_VALUE_CATEGORY =
            "sema.unsupported_parameter_default_value";
    private static final @NotNull String UNSUPPORTED_VARIABLE_INVENTORY_SUBTREE_CATEGORY =
            "sema.unsupported_variable_inventory_subtree";

    /// Runs variable analysis against the shared analysis carrier.
    ///
    /// The published scope graph is enriched in place so later phases can consume one stable
    /// lexical structure plus declaration inventory without rebuilding scope objects.
    public void analyze(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var moduleSkeleton = analysisData.moduleSkeleton();
        analysisData.diagnostics();

        // Missing top-level scopes indicate a broken phase boundary rather than a recoverable
        // source error, so this remains a fail-fast guard rail.
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
            new AstWalkerVariableBinder(
                    sourceClassRelation.unit().path(),
                    moduleSkeleton,
                    scopesByAst,
                    diagnosticManager
            ).walk(sourceClassRelation.unit().ast());
        }
    }

    /// ASTWalker-backed declaration-directed binder used by the current variable analyzer.
    ///
    /// `ASTWalker` is used here only as the typed dispatch mechanism. The analyzer still keeps
    /// explicit subtree control so unsupported domains remain sealed:
    /// - only source/class statement lists and supported executable blocks are descended into
    /// - function/constructor parameters are bound at the callable boundary
    /// - ordinary locals are bound only while the walker is inside a supported executable block
    /// - lambda / `for` / `match` subtrees are pruned explicitly
    /// - arbitrary expression children stay outside the binding walk
    private static final class AstWalkerVariableBinder implements ASTNodeHandler {
        private final @NotNull Path sourcePath;
        private final @NotNull FrontendModuleSkeleton moduleSkeleton;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull UnsupportedVariableBoundaryReporter unsupportedBoundaryReporter;
        private @Nullable Node currentCallableOwner;
        /// Counts how many supported executable-block boundaries the walker is currently inside.
        ///
        /// The counter acts as a narrow capability flag rather than a generic nesting metric:
        /// - `0` means the current traversal position is outside any block where local-variable
        ///   binding is allowed, such as source/class declaration containers
        /// - `> 0` means the walker is inside a function/constructor body or one of the currently
        ///   supported nested executable blocks beneath it
        ///
        /// This deliberately keeps variable binding disabled for non-executable containers while
        /// still allowing nested `Block` / `if` / `elif` / `while` nodes to participate once the
        /// callable body has opened the first supported executable scope.
        private int supportedExecutableBlockDepth;

        private AstWalkerVariableBinder(
                @NotNull Path sourcePath,
                @NotNull FrontendModuleSkeleton moduleSkeleton,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
            this.moduleSkeleton = Objects.requireNonNull(moduleSkeleton, "moduleSkeleton");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager");
            this.astWalker = new ASTWalker(this);
            unsupportedBoundaryReporter = new UnsupportedVariableBoundaryReporter(sourcePath, diagnosticManager);
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
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkNonExecutableContainerStatements(classDeclaration.body().statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration
        ) {
            bindCallableParameters(functionDeclaration, functionDeclaration.parameters(), functionDeclaration.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration
        ) {
            bindCallableParameters(constructorDeclaration, constructorDeclaration.parameters(), constructorDeclaration.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleBlock(@NotNull Block block) {
            // A plain `Block` is only considered bindable when the current traversal has already
            // entered a supported executable region. This prevents the walker from treating
            // structurally block-shaped but non-executable containers as local-binding domains.
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(block)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkStatements(block.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            // `if` branches contribute local scopes only when they appear inside an already
            // accepted executable block. At top level or class body they must stay inert.
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(ifStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
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
            // `elif` is gated by the same executable-context check as `if`, because its body is
            // only meaningful as a nested runtime branch, never as a declaration container.
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(elifClause)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkSupportedExecutableBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            // Loop bodies are part of the current supported executable-block set, but only once
            // the walker is already under a callable body. This keeps unsupported outer
            // containers sealed.
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(whileStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkSupportedExecutableBlock(whileStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleVariableDeclaration(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            // Variable declarations are only interpreted as callable-local inventory once the
            // walker is inside a supported executable block. Outside that region, the same AST
            // shape may represent class members and must remain untouched here.
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (variableDeclaration.kind() == DeclarationKind.CONST) {
                reportUnsupportedBlockLocalConst(variableDeclaration);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (variableDeclaration.kind() != DeclarationKind.VAR) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            bindLocal(variableDeclaration);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            // Unsupported feature-boundary diagnostics are emitted by
            // `UnsupportedVariableBoundaryReporter`, which can scan expression subtrees without
            // changing this binder's declaration-only walk.
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            // The binder itself still treats `for` as an unsupported boundary; explicit user
            // diagnostics are produced by the dedicated boundary reporter.
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            // The binder itself still treats `match` as an unsupported boundary; explicit user
            // diagnostics are produced by the dedicated boundary reporter.
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        private void bindCallableParameters(
                @NotNull Node callableOwner,
                @NotNull List<Parameter> parameters,
                @NotNull Block body
        ) {
            if (isNotPublished(callableOwner)) {
                return;
            }
            var previousCallableOwner = currentCallableOwner;
            currentCallableOwner = callableOwner;
            try {
                for (var parameter : parameters) {
                    bindParameter(parameter);
                }
                if (isNotPublished(body)) {
                    return;
                }
                unsupportedBoundaryReporter.report(body);
                walkSupportedExecutableBlock(body);
            } finally {
                currentCallableOwner = previousCallableOwner;
            }
        }

        private void walkStatements(@NotNull List<Statement> statements) {
            for (var statement : statements) {
                astWalker.walk(statement);
            }
        }

        /// Top-level and class-body statement lists are only declaration containers.
        /// Local-variable binding must stay disabled there so class properties are not reclassified
        /// as block locals.
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
            // Entering a supported executable block enables local binding for its statements and
            // any further supported nested blocks beneath it. The `finally` restore is required so
            // sibling top-level/class-body statements do not accidentally inherit executable state.
            supportedExecutableBlockDepth++;
            try {
                astWalker.walk(block);
            } finally {
                supportedExecutableBlockDepth--;
            }
        }

        private void bindParameter(@NotNull Parameter parameter) {
            var parameterName = parameter.name().trim();
            reportUnsupportedDefaultValue(parameter);

            var targetScope = scopesByAst.get(parameter);
            if (targetScope == null) {
                return;
            }
            if (!(targetScope instanceof CallableScope callableScope)) {
                reportBindingError(
                        parameter,
                        "Parameter '" + parameterName + "' expected CallableScope, but found "
                                + targetScope.getClass().getSimpleName()
                );
                return;
            }

            var parameterType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                    parameter.type(),
                    callableScope,
                    moduleSkeleton.topLevelCanonicalNameMap(),
                    sourcePath,
                    diagnosticManager
            );
            var existingBinding = callableScope.resolveValueHere(parameterName);
            if (existingBinding != null) {
                reportBindingError(parameter, switch (existingBinding.kind()) {
                    case PARAMETER -> "Duplicate parameter '" + parameterName + "' in the same callable";
                    case CAPTURE ->
                            "Parameter '" + parameterName + "' conflicts with existing capture '" + parameterName + "'";
                    default ->
                            "Parameter '" + parameterName + "' conflicts with existing callable binding '" + parameterName + "'";
                });
                return;
            }
            callableScope.defineParameter(parameterName, parameterType, parameter);
        }

        private void bindLocal(@NotNull VariableDeclaration variableDeclaration) {
            var variableName = variableDeclaration.name().trim();
            var targetScope = scopesByAst.get(variableDeclaration);
            if (targetScope == null) {
                return;
            }
            if (!(targetScope instanceof BlockScope blockScope)) {
                reportBindingError(
                        variableDeclaration,
                        "Local variable '" + variableName + "' expected BlockScope, but found "
                                + targetScope.getClass().getSimpleName()
                );
                return;
            }

            if (!FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(blockScope.kind())) {
                reportBindingError(
                        variableDeclaration,
                        "Local variable '" + variableName + "' expected supported executable BlockScope, but found "
                                + blockScope.kind()
                );
                return;
            }

            var existingLocal = blockScope.resolveValueHere(variableName);
            if (existingLocal != null) {
                reportLocalConflict(variableDeclaration, blockScope, existingLocal, false);
                return;
            }

            var sameCallableConflict = findSameCallableConflict(blockScope, variableName);
            if (sameCallableConflict != null) {
                reportLocalConflict(variableDeclaration, blockScope, sameCallableConflict, true);
                return;
            }

            var variableType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                    variableDeclaration.type(),
                    blockScope,
                    moduleSkeleton.topLevelCanonicalNameMap(),
                    sourcePath,
                    diagnosticManager
            );
            try {
                blockScope.defineLocal(variableName, variableType, variableDeclaration);
            } catch (IllegalArgumentException exception) {
                reportBindingError(
                        variableDeclaration,
                        "Duplicate local variable '" + variableName + "' in the same block"
                );
            }
        }

        /// Duplicate and shadowing locals are user-facing source errors rather than phase
        /// invariants. The message therefore carries both declaration locations plus the callable /
        /// block context so compile-only callers can stop before lowering while shared analysis keeps
        /// processing unaffected facts.
        private void reportLocalConflict(
                @NotNull VariableDeclaration variableDeclaration,
                @NotNull BlockScope blockScope,
                @NotNull ScopeValue conflictingBinding,
                boolean shadowing
        ) {
            var variableName = variableDeclaration.name().trim();
            var currentRange = FrontendRange.fromAstRange(variableDeclaration.range());
            var conflictingDeclaration = conflictingBinding.declaration();
            var conflictingRange = conflictingDeclaration instanceof Node conflictingNode
                    ? FrontendRange.fromAstRange(conflictingNode.range())
                    : null;
            var message = shadowing
                    ? "Local variable '%s' in %s shadows %s; shadowing declaration is at %s and the earlier declaration is at %s in %s"
                      .formatted(
                              variableName,
                              describeLocalContext(blockScope),
                              describeShadowingTarget(conflictingBinding),
                              formatRange(currentRange),
                              formatRange(conflictingRange),
                              sourcePath
                      )
                    : "Duplicate local variable '%s' in %s; current declaration is at %s and the earlier declaration is at %s in %s"
                      .formatted(
                              variableName,
                              describeLocalContext(blockScope),
                              formatRange(currentRange),
                              formatRange(conflictingRange),
                              sourcePath
                      );
            reportBindingError(variableDeclaration, message);
        }

        private @NotNull String describeLocalContext(@NotNull BlockScope blockScope) {
            return switch (blockScope.kind()) {
                case FUNCTION_BODY, CONSTRUCTOR_BODY -> describeCallableContext();
                case BLOCK_STATEMENT -> "block statement of " + describeCallableContext();
                case IF_BODY -> "if-body of " + describeCallableContext();
                case ELIF_BODY -> "elif-body of " + describeCallableContext();
                case ELSE_BODY -> "else-body of " + describeCallableContext();
                case WHILE_BODY -> "while-body of " + describeCallableContext();
                case LAMBDA_BODY -> "lambda-body of " + describeCallableContext();
                case FOR_BODY -> "`for` body of " + describeCallableContext();
                case MATCH_SECTION_BODY -> "`match` section of " + describeCallableContext();
            };
        }

        private @NotNull String describeCallableContext() {
            return switch (currentCallableOwner) {
                case FunctionDeclaration functionDeclaration -> "function '" + functionDeclaration.name().trim() + "'";
                case ConstructorDeclaration _ -> "constructor '_init'";
                case null -> "callable";
                default -> currentCallableOwner.getClass().getSimpleName();
            };
        }

        private @NotNull String describeShadowingTarget(@NotNull ScopeValue conflictingBinding) {
            var targetKind = switch (conflictingBinding.kind()) {
                case PARAMETER -> "parameter";
                case CAPTURE -> "capture";
                case LOCAL -> "outer local";
                case CONSTANT -> "outer constant";
                default -> "callable-local binding";
            };
            return targetKind + " '" + conflictingBinding.name() + "'";
        }

        private static @NotNull String formatRange(@Nullable FrontendRange range) {
            if (range == null) {
                return "<unknown-range>";
            }
            return "%d:%d-%d:%d".formatted(
                    range.start().line(),
                    range.start().column(),
                    range.end().line(),
                    range.end().column()
            );
        }

        /// Local shadowing is forbidden only inside the current callable boundary.
        /// Class/global bindings are intentionally not part of this check because they remain legal
        /// outer names for callable-local declarations.
        private @Nullable ScopeValue findSameCallableConflict(
                @NotNull BlockScope blockScope,
                @NotNull String variableName
        ) {
            Scope currentScope = blockScope.getParentScope();
            while (currentScope != null) {
                if (currentScope instanceof BlockScope outerBlockScope) {
                    var outerLocal = outerBlockScope.resolveValueHere(variableName);
                    if (outerLocal != null) {
                        return outerLocal;
                    }
                    currentScope = outerBlockScope.getParentScope();
                    continue;
                }
                if (currentScope instanceof CallableScope callableScope) {
                    return callableScope.resolveValueHere(variableName);
                }
                return null;
            }
            return null;
        }

        private void reportUnsupportedDefaultValue(@NotNull Parameter parameter) {
            if (parameter.defaultValue() == null) {
                return;
            }
            diagnosticManager.error(
                    UNSUPPORTED_PARAMETER_DEFAULT_VALUE_CATEGORY,
                    "Parameter default value for '" + parameter.name().trim()
                            + "' is not supported by the current frontend body-typing contract; "
                            + "the current variable analyzer ignores the default value expression",
                    sourcePath,
                    FrontendRange.fromAstRange(parameter.defaultValue().range())
            );
        }

        private void reportUnsupportedBlockLocalConst(@NotNull VariableDeclaration variableDeclaration) {
            diagnosticManager.error(
                    UNSUPPORTED_VARIABLE_INVENTORY_SUBTREE_CATEGORY,
                    "Variable analysis does not support block-local `const` declarations yet; constant '"
                            + variableDeclaration.name().trim()
                            + "' is not bound into the current executable scope yet",
                    sourcePath,
                    FrontendRange.fromAstRange(variableDeclaration.range())
            );
        }

        private void reportBindingError(
                @NotNull Node declaration,
                @NotNull String message
        ) {
            diagnosticManager.error(
                    VARIABLE_BINDING_CATEGORY,
                    message,
                    sourcePath,
                    FrontendRange.fromAstRange(declaration.range())
            );
        }

        private boolean isNotPublished(@Nullable Node astNode) {
            return astNode == null || !scopesByAst.containsKey(astNode);
        }
    }

    /// Scans supported callable bodies for unsupported variable-inventory boundaries that the
    /// current binder intentionally does not enter.
    ///
    /// This reporter exists because the binder itself is declaration-directed and therefore skips
    /// arbitrary expression children. Without a separate scan, lambdas nested inside expressions
    /// such as local initializers or return values would remain completely silent.
    private static final class UnsupportedVariableBoundaryReporter implements ASTNodeHandler {
        private final @NotNull Path sourcePath;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull ASTWalker astWalker;

        private UnsupportedVariableBoundaryReporter(
                @NotNull Path sourcePath,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager");
            astWalker = new ASTWalker(this);
        }

        private void report(@NotNull Block callableBody) {
            astWalker.walk(callableBody);
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleNode(@NotNull Node node) {
            return FrontendASTTraversalDirective.CONTINUE;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleClassDeclaration(@NotNull ClassDeclaration classDeclaration) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration
        ) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration
        ) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            reportUnsupportedBoundary(
                    lambdaExpression,
                    "Variable analysis does not support lambda subtrees yet; parameters, default values, locals, "
                            + "and captures inside this lambda are not bound yet"
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            reportUnsupportedBoundary(
                    forStatement,
                    "Variable analysis does not support `for` subtrees yet; the loop iterator binding and "
                            + "locals declared inside this loop body are not bound yet"
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            reportUnsupportedBoundary(
                    matchStatement,
                    "Variable analysis does not support `match` subtrees yet; pattern bindings and locals "
                            + "declared inside match sections are not bound yet"
            );
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        private void reportUnsupportedBoundary(@NotNull Node node, @NotNull String message) {
            diagnosticManager.error(
                    UNSUPPORTED_VARIABLE_INVENTORY_SUBTREE_CATEGORY,
                    message,
                    sourcePath,
                    FrontendRange.fromAstRange(node.range())
            );
        }
    }
}
