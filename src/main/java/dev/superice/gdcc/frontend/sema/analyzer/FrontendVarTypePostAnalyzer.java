package dev.superice.gdcc.frontend.sema.analyzer;

import dev.superice.gdcc.frontend.diagnostic.DiagnosticManager;
import dev.superice.gdcc.frontend.diagnostic.FrontendRange;
import dev.superice.gdcc.frontend.scope.BlockScope;
import dev.superice.gdcc.frontend.scope.CallableScope;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendExecutableInventorySupport;
import dev.superice.gdcc.scope.Scope;
import dev.superice.gdcc.scope.ScopeValue;
import dev.superice.gdcc.scope.ScopeValueKind;
import dev.superice.gdcc.type.GdType;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/// Publishes final callable-local slot types after expression typing has already settled local `:=`
/// backfill inside the lexical inventory.
public class FrontendVarTypePostAnalyzer {
    public static final @NotNull String VARIABLE_SLOT_PUBLICATION_CATEGORY = "sema.variable_slot_publication";

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

        var slotTypes = analysisData.slotTypes();
        slotTypes.clear();
        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            new SlotTypePublisher(
                    sourceClassRelation.unit().path(),
                    scopesByAst,
                    diagnosticManager,
                    slotTypes
            ).walk(sourceClassRelation.unit().ast());
        }
        analysisData.updateSlotTypes(slotTypes);
    }

    /// Traverses only the current supported executable surface and republishes the already-settled
    /// inventory slot types on declaration-site AST identities.
    private static final class SlotTypePublisher implements ASTNodeHandler {
        private final @NotNull Path sourcePath;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull FrontendAstSideTable<GdType> slotTypes;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull Deque<BlockScope> blockScopeStack = new ArrayDeque<>();
        private @Nullable Node currentCallableOwner;
        private int supportedExecutableBlockDepth;

        private SlotTypePublisher(
                @NotNull Path sourcePath,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull DiagnosticManager diagnosticManager,
                @NotNull FrontendAstSideTable<GdType> slotTypes
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager");
            this.slotTypes = Objects.requireNonNull(
                    slotTypes,
                    "slotTypes"
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
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkNonExecutableContainerStatements(classDeclaration.body().statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration
        ) {
            publishCallableSlots(functionDeclaration, functionDeclaration.parameters(), functionDeclaration.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration
        ) {
            publishCallableSlots(constructorDeclaration, constructorDeclaration.parameters(), constructorDeclaration.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleBlock(@NotNull Block block) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(block)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            var blockScope = requireBlockScope(block, "Executable block expected published BlockScope");
            blockScopeStack.addLast(blockScope);
            try {
                walkStatements(block.statements());
            } finally {
                blockScopeStack.removeLast();
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
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
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(elifClause)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkSupportedExecutableBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
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
            if (supportedExecutableBlockDepth <= 0 || variableDeclaration.kind() != DeclarationKind.VAR) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            publishLocalSlotType(variableDeclaration);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        private void publishCallableSlots(
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
                    publishParameterSlotType(parameter);
                }
                if (isNotPublished(body)) {
                    return;
                }
                walkSupportedExecutableBlock(body);
            } finally {
                currentCallableOwner = previousCallableOwner;
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

        private void walkStatements(@NotNull List<Statement> statements) {
            for (var statement : statements) {
                astWalker.walk(statement);
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

        private void publishParameterSlotType(@NotNull Parameter parameter) {
            var parameterName = parameter.name().trim();
            var declarationScope = scopesByAst.get(parameter);
            if (!(declarationScope instanceof CallableScope callableScope)) {
                throw new IllegalStateException(
                        "Parameter '" + parameterName + "' expected published CallableScope in " + sourcePath
                );
            }
            var slot = requireDeclaredSlot(
                    callableScope.resolveValueHere(parameterName),
                    "Parameter '" + parameterName + "' has no published inventory slot"
            );
            requireMatchingDeclaration(
                    slot,
                    ScopeValueKind.PARAMETER,
                    parameter,
                    "Parameter '" + parameterName + "' inventory slot drifted from its declaration site"
            );
            slotTypes.put(parameter, slot.type());
        }

        private void publishLocalSlotType(@NotNull VariableDeclaration variableDeclaration) {
            var variableName = variableDeclaration.name().trim();
            var declarationScope = scopesByAst.get(variableDeclaration);
            if (!(declarationScope instanceof BlockScope blockScope)) {
                throw new IllegalStateException(
                        "Local variable '" + variableName + "' expected published BlockScope in " + sourcePath
                );
            }
            if (!FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(blockScope.kind())) {
                return;
            }
            if (blockScope != currentBlockScope()) {
                throw new IllegalStateException(
                        "Local variable '" + variableName + "' drifted away from its enclosing block scope in " + sourcePath
                );
            }
            var currentLayerSlot = blockScope.resolveValueHere(variableName);
            if (currentLayerSlot == null
                    || currentLayerSlot.kind() != ScopeValueKind.LOCAL
                    || currentLayerSlot.declaration() != variableDeclaration) {
                // Earlier variable inventory may reject duplicate/shadowing locals on purpose. The
                // published slot-type surface must stay fail-closed for those declarations, but the
                // gap itself still needs to be visible to tooling and compile-only callers.
                reportRejectedLocalSlotPublication(
                        variableDeclaration,
                        findSurvivingCallableLocalBinding(blockScope, variableName, currentLayerSlot)
                );
                return;
            }
            slotTypes.put(variableDeclaration, currentLayerSlot.type());
        }

        private @NotNull BlockScope requireBlockScope(@NotNull Block block, @NotNull String message) {
            var publishedScope = scopesByAst.get(block);
            if (publishedScope instanceof BlockScope blockScope) {
                return blockScope;
            }
            throw new IllegalStateException(message + " in " + sourcePath);
        }

        private @NotNull BlockScope currentBlockScope() {
            var current = blockScopeStack.peekLast();
            if (current == null) {
                throw new IllegalStateException("Current executable block scope is unavailable in " + sourcePath);
            }
            return current;
        }

        private @NotNull ScopeValue requireDeclaredSlot(
                @Nullable ScopeValue slot,
                @NotNull String message
        ) {
            if (slot == null) {
                throw new IllegalStateException(message + " in " + sourcePath);
            }
            return slot;
        }

        private void requireMatchingDeclaration(
                @NotNull ScopeValue slot,
                @NotNull ScopeValueKind expectedKind,
                @NotNull Node declarationSite,
                @NotNull String mismatchMessage
        ) {
            if (slot.kind() != expectedKind || slot.declaration() != declarationSite) {
                throw new IllegalStateException(mismatchMessage + " in " + sourcePath);
            }
        }

        private boolean isNotPublished(@Nullable Node astNode) {
            return astNode == null || !scopesByAst.containsKey(astNode);
        }

        /// Duplicate locals fail in the current block layer, while shadowing locals fail because a
        /// parent block/callable within the same callable boundary already owns the visible name.
        /// The post analyzer must describe that surviving binding without accidentally walking into
        /// class/global scopes, because only callable-local inventory participates in this contract.
        private @Nullable ScopeValue findSurvivingCallableLocalBinding(
                @NotNull BlockScope declarationScope,
                @NotNull String variableName,
                @Nullable ScopeValue currentLayerSlot
        ) {
            if (currentLayerSlot != null) {
                return currentLayerSlot;
            }
            Scope currentScope = declarationScope.getParentScope();
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

        /// Missing callable-local slot facts are warning-level here because the root cause is
        /// usually already a source diagnostic from earlier inventory analysis. The warning still
        /// matters: compile-only callers need one explicit published signal that lowering-ready slot
        /// facts are missing for this declaration.
        private void reportRejectedLocalSlotPublication(
                @NotNull VariableDeclaration variableDeclaration,
                @Nullable ScopeValue survivingSlot
        ) {
            var variableName = variableDeclaration.name().trim();
            var declarationRange = formatRange(variableDeclaration);
            var message = new StringBuilder()
                    .append("Local variable '")
                    .append(variableName)
                    .append("' in ")
                    .append(describeCurrentLocalContext())
                    .append(" has no lowering-ready published slot type at ")
                    .append(declarationRange)
                    .append(" in ")
                    .append(sourcePath)
                    .append("; the declaration was not accepted into callable-local inventory");
            if (survivingSlot != null && survivingSlot.declaration() instanceof Node survivingDeclaration) {
                message.append("; surviving slot currently resolves to ")
                        .append(describeSurvivingDeclaration(survivingSlot))
                        .append(" at ")
                        .append(formatRange(survivingDeclaration));
            } else {
                message.append("; this usually means earlier variable analysis rejected the declaration as duplicate or shadowing");
            }
            diagnosticManager.warning(
                    VARIABLE_SLOT_PUBLICATION_CATEGORY,
                    message.toString(),
                    sourcePath,
                    FrontendRange.fromAstRange(variableDeclaration.range())
            );
        }

        private @NotNull String describeCurrentLocalContext() {
            var currentBlockScope = currentBlockScope();
            return switch (currentBlockScope.kind()) {
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

        private static @NotNull String describeSurvivingDeclaration(@NotNull ScopeValue survivingSlot) {
            return switch (survivingSlot.kind()) {
                case LOCAL -> "another accepted local declaration";
                case PARAMETER -> "the parameter declaration";
                case CAPTURE -> "the capture declaration";
                case CONSTANT -> "the constant declaration";
                default -> "an accepted callable-local binding";
            };
        }

        private static @NotNull String formatRange(@NotNull Node node) {
            var range = FrontendRange.fromAstRange(node.range());
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
    }
}
