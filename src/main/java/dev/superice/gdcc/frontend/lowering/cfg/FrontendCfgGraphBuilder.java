package dev.superice.gdcc.frontend.lowering.cfg;

import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.frontend.lowering.FrontendCallMutabilitySupport;
import dev.superice.gdcc.frontend.lowering.FrontendSubscriptAccessSupport;
import dev.superice.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.BoolConstantItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CastItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CompoundAssignmentBinaryOpItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.DirectSlotAliasValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.FrontendWritableRoutePayload;
import dev.superice.gdcc.frontend.lowering.cfg.item.LocalDeclarationItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SequenceItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SourceAnchorItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.TypeTestItem;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendCfgRegion;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendElifRegion;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendIfRegion;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendWhileRegion;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendAstSideTable;
import dev.superice.gdcc.frontend.sema.FrontendBinding;
import dev.superice.gdcc.frontend.sema.FrontendBindingKind;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendResolvedCall;
import dev.superice.gdcc.frontend.sema.FrontendResolvedMember;
import dev.superice.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.BreakStatement;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.ContinueStatement;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import dev.superice.gdcc.type.GdType;
import dev.superice.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/// Frontend CFG builder for one compile-ready executable body.
///
/// The graph stays frontend-only:
/// - `SequenceNode` holds explicit source-level value-op items
/// - `BranchNode` keeps the condition fragment root and published condition value id
/// - `StopNode` marks either a real function return or a synthetic fully-terminated merge anchor
///
/// This builder owns structured executable control flow for the current supported statement surface:
/// - straight-line statements and local `var`
/// - `if` / `elif` / `else`
/// - `while`
/// - loop-local `break` / `continue`
///
/// Short-circuit `and` / `or` now lower through explicit condition/value CFG paths:
/// - condition-context binaries expand into multi-branch condition subgraphs
/// - value-context binaries materialize branch-local `true` / `false` writes into one merged result
///   slot before continuation rejoins
///
/// `ConditionalExpression` still stays outside the current compile-ready surface until its
/// branch-result merge contract is finalized.
public final class FrontendCfgGraphBuilder {
    private @Nullable FrontendAnalysisData analysisData;
    private @Nullable LinkedHashMap<String, FrontendCfgGraph.NodeDef> nodes;
    private @Nullable FrontendAstSideTable<FrontendCfgRegion> regions;
    private final @NotNull ArrayDeque<LoopFrame> loopStack = new ArrayDeque<>();
    private int nextSequenceIndex;
    private int nextBranchIndex;
    private int nextStopIndex;
    private int nextValueIndex;

    /// Builds one executable-body frontend CFG graph plus every AST-keyed region published inside it.
    ///
    /// The builder consumes only compile-ready frontend facts. Reaching an unsupported statement or a
    /// missing lowering-ready side-table entry here therefore indicates a pipeline contract violation,
    /// and the builder fails fast instead of publishing a partial graph.
    public @NotNull ExecutableBodyBuild buildExecutableBody(
            @NotNull Block rootBlock,
            @NotNull FrontendAnalysisData analysisData
    ) {
        Objects.requireNonNull(rootBlock, "rootBlock must not be null");
        initializeBuildState(analysisData);

        var fallthroughStopId = publishStopNode(FrontendCfgGraph.StopKind.RETURN, null);
        var rootBuild = buildBlock(rootBlock, fallthroughStopId);
        var entryId = rootBuild.entryId();
        if (entryId.equals(fallthroughStopId)) {
            entryId = publishSequenceNode(List.of(), fallthroughStopId);
            requireRegions().put(rootBlock, new FrontendCfgRegion.BlockRegion(entryId));
        } else if (!isNodeReferenced(fallthroughStopId)) {
            requireNodes().remove(fallthroughStopId);
        }

        return finishBuild(entryId);
    }

    /// Builds one expression-rooted frontend CFG graph for a property initializer helper.
    ///
    /// Property initializer contexts do not synthesize a fake `Block` wrapper. They reuse the same
    /// value/short-circuit graph core as executable bodies, then terminate in one synthetic
    /// `RETURN` stop carrying the initializer result value id. No structured region is published
    /// here because `FrontendCfgRegion` currently models block/branch/loop ownership only.
    public @NotNull ExecutableBodyBuild buildPropertyInitializer(
            @NotNull Expression rootExpression,
            @NotNull FrontendAnalysisData analysisData
    ) {
        Objects.requireNonNull(rootExpression, "rootExpression must not be null");
        initializeBuildState(analysisData);

        var rootBuild = buildValue(new BuildCursor(new OpenSequence(nextSequenceId())), rootExpression, null);
        var returnStopId = publishStopNode(FrontendCfgGraph.StopKind.RETURN, rootBuild.resultValueId());
        publishSequenceNode(
                rootBuild.cursor().currentSequence().id(),
                rootBuild.cursor().currentSequence().items(),
                returnStopId
        );
        return finishBuild(rootBuild.cursor().entryId());
    }

    private @NotNull BlockBuild buildBlock(@NotNull Block block, @NotNull String continuationId) {
        var state = new BlockState();
        for (var statement : block.statements()) {
            if (!state.reachable()) {
                break;
            }
            processStatement(state, statement);
        }

        var entryId = finalizeBlockState(state, continuationId);
        requireRegions().put(block, new FrontendCfgRegion.BlockRegion(entryId));
        return new BlockBuild(entryId, state.reachable());
    }

    private void processStatement(@NotNull BlockState state, @NotNull Statement statement) {
        switch (statement) {
            case PassStatement passStatement ->
                    requireCurrentSequence(state).items().add(new SourceAnchorItem(passStatement));
            case ExpressionStatement expressionStatement ->
                    processExpressionStatement(state, expressionStatement.expression());
            case VariableDeclaration variableDeclaration when variableDeclaration.kind() == DeclarationKind.VAR ->
                    processLocalDeclaration(state, variableDeclaration);
            case ReturnStatement returnStatement -> processReturnStatement(state, returnStatement);
            case IfStatement ifStatement -> processIfStatement(state, ifStatement);
            case WhileStatement whileStatement -> processWhileStatement(state, whileStatement);
            case BreakStatement breakStatement ->
                    processLoopJump(state, breakStatement, requireLoopFrame().breakTargetId());
            case ContinueStatement continueStatement -> processLoopJump(
                    state,
                    continueStatement,
                    requireLoopFrame().continueTargetId()
            );
            default -> throw unsupportedReachableStatement(statement);
        }
    }

    private void processLocalDeclaration(
            @NotNull BlockState state,
            @NotNull VariableDeclaration variableDeclaration
    ) {
        var cursor = new BuildCursor(requireCurrentSequence(state));
        var initializer = variableDeclaration.value();
        ValueBuild initializerBuild = initializer == null
                ? null
                : buildValue(cursor, initializer, nextVariableValueId(variableDeclaration.name()));
        var currentCursor = initializerBuild == null ? cursor : initializerBuild.cursor();
        currentCursor.currentSequence().items().add(new LocalDeclarationItem(
                variableDeclaration,
                initializerBuild == null ? null : initializerBuild.resultValueId()
        ));
        state.setCurrentSequence(currentCursor.currentSequence());
    }

    private void processExpressionStatement(
            @NotNull BlockState state,
            @NotNull Expression expression
    ) {
        requireLoweringReadyExpressionType(expression);
        switch (expression) {
            case AssignmentExpression assignmentExpression -> state.setCurrentSequence(buildAssignmentCommit(
                    new BuildCursor(requireCurrentSequence(state)),
                    assignmentExpression
            ).currentSequence());
            default -> state.setCurrentSequence(buildValue(
                    new BuildCursor(requireCurrentSequence(state)),
                    expression,
                    null
            ).cursor().currentSequence());
        }
    }

    private void processReturnStatement(@NotNull BlockState state, @NotNull ReturnStatement returnStatement) {
        var returnValue = returnStatement.value();
        String returnValueId = null;
        if (returnValue != null) {
            var returnBuild = buildValue(new BuildCursor(requireCurrentSequence(state)), returnValue, null);
            state.setCurrentSequence(returnBuild.cursor().currentSequence());
            returnValueId = returnBuild.resultValueId();
        } else {
            requireCurrentSequence(state).items().add(new SourceAnchorItem(returnStatement));
        }
        closeCurrentSequence(state, publishStopNode(FrontendCfgGraph.StopKind.RETURN, returnValueId));
        state.setReachable(false);
    }

    private void processLoopJump(@NotNull BlockState state, @NotNull Statement statement, @NotNull String targetId) {
        var sequence = requireCurrentSequence(state);
        sequence.items().add(new SourceAnchorItem(statement));
        closeCurrentSequence(state, targetId);
        state.setReachable(false);
    }

    private void processIfStatement(@NotNull BlockState state, @NotNull IfStatement ifStatement) {
        var mergeSequence = new OpenSequence(nextSequenceId());
        var thenBuild = buildBlock(ifStatement.body(), mergeSequence.id());
        var falseBuild = buildIfFalseChain(ifStatement.elifClauses(), ifStatement.elseBody(), mergeSequence.id());
        var conditionBuild = buildCondition(ifStatement.condition(), thenBuild.entryId(), falseBuild.entryId());
        attachStructuredEntry(state, conditionBuild.entryId());

        var fallsThrough = thenBuild.fallsThrough() || falseBuild.fallsThrough();
        var mergeId = fallsThrough
                ? mergeSequence.id()
                : publishStopNode(FrontendCfgGraph.StopKind.TERMINAL_MERGE, null);
        requireRegions().put(
                ifStatement,
                new FrontendIfRegion(
                        conditionBuild.entryId(),
                        thenBuild.entryId(),
                        falseBuild.entryId(),
                        mergeId
                )
        );

        if (fallsThrough) {
            state.setCurrentSequence(mergeSequence);
            state.setReachable(true);
            return;
        }
        state.setCurrentSequence(null);
        state.setReachable(false);
    }

    private @NotNull ClauseBuild buildIfFalseChain(
            @NotNull List<ElifClause> elifClauses,
            @Nullable Block elseBody,
            @NotNull String mergeTargetId
    ) {
        if (!elifClauses.isEmpty()) {
            return buildElifChain(elifClauses, 0, elseBody, mergeTargetId);
        }
        if (elseBody != null) {
            var elseBuild = buildBlock(elseBody, mergeTargetId);
            return new ClauseBuild(elseBuild.entryId(), elseBuild.fallsThrough());
        }
        return new ClauseBuild(mergeTargetId, true);
    }

    private @NotNull ClauseBuild buildElifChain(
            @NotNull List<ElifClause> elifClauses,
            int clauseIndex,
            @Nullable Block elseBody,
            @NotNull String mergeTargetId
    ) {
        if (clauseIndex >= elifClauses.size()) {
            if (elseBody != null) {
                var elseBuild = buildBlock(elseBody, mergeTargetId);
                return new ClauseBuild(elseBuild.entryId(), elseBuild.fallsThrough());
            }
            return new ClauseBuild(mergeTargetId, true);
        }

        var elifClause = elifClauses.get(clauseIndex);
        var nextClause = buildElifChain(elifClauses, clauseIndex + 1, elseBody, mergeTargetId);
        var bodyBuild = buildBlock(elifClause.body(), mergeTargetId);
        var conditionBuild = buildCondition(elifClause.condition(), bodyBuild.entryId(), nextClause.entryId());
        requireRegions().put(
                elifClause,
                new FrontendElifRegion(
                        conditionBuild.entryId(),
                        bodyBuild.entryId(),
                        nextClause.entryId()
                )
        );
        return new ClauseBuild(conditionBuild.entryId(), bodyBuild.fallsThrough() || nextClause.fallsThrough());
    }

    private void processWhileStatement(@NotNull BlockState state, @NotNull WhileStatement whileStatement) {
        var exitSequence = new OpenSequence(nextSequenceId());
        var conditionCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        loopStack.push(new LoopFrame(conditionCursor.entryId(), exitSequence.id()));
        BlockBuild bodyBuild;
        try {
            bodyBuild = buildBlock(whileStatement.body(), conditionCursor.entryId());
        } finally {
            loopStack.pop();
        }

        var conditionBuild = buildCondition(
                conditionCursor,
                whileStatement.condition(),
                bodyBuild.entryId(),
                exitSequence.id()
        );
        attachStructuredEntry(state, conditionBuild.entryId());
        requireRegions().put(
                whileStatement,
                new FrontendWhileRegion(
                        conditionBuild.entryId(),
                        bodyBuild.entryId(),
                        exitSequence.id()
                )
        );
        state.setCurrentSequence(exitSequence);
        state.setReachable(true);
    }

    private void attachStructuredEntry(@NotNull BlockState state, @NotNull String structuredEntryId) {
        if (state.currentSequenceOrNull() == null) {
            state.setEntryIdIfMissing(structuredEntryId);
            return;
        }
        closeCurrentSequence(state, structuredEntryId);
    }

    private @NotNull ConditionBuild buildCondition(
            @NotNull Expression condition,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        return buildCondition(new BuildCursor(new OpenSequence(nextSequenceId())), condition, trueTargetId, falseTargetId);
    }

    private @NotNull ConditionBuild buildCondition(
            @NotNull BuildCursor cursor,
            @NotNull Expression condition,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        requireLoweringReadyExpressionType(condition);
        return switch (condition) {
            case UnaryExpression unaryExpression when isLogicalNotExpression(unaryExpression) ->
                    buildCondition(cursor, unaryExpression.operand(), falseTargetId, trueTargetId);
            case BinaryExpression binaryExpression when isShortCircuitBinaryExpression(binaryExpression) ->
                    buildShortCircuitCondition(cursor, binaryExpression, trueTargetId, falseTargetId);
            case ConditionalExpression conditionalExpression ->
                    buildConditionalExpressionCondition(conditionalExpression);
            default -> buildConditionFromValue(cursor, condition, trueTargetId, falseTargetId);
        };
    }

    private @NotNull ConditionBuild buildConditionFromValue(
            @NotNull BuildCursor cursor,
            @NotNull Expression condition,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var conditionValueBuild = buildValue(cursor, condition, null);
        var conditionSequence = conditionValueBuild.cursor().currentSequence();
        return publishConditionBranch(
                conditionValueBuild.cursor().entryId(),
                conditionSequence,
                condition,
                conditionValueBuild.resultValueId(),
                trueTargetId,
                falseTargetId
        );
    }

    /// Publishes one branch that immediately tests the given fragment value.
    ///
    /// The caller must pass the expression root that directly produced `conditionValueId`, not the
    /// outer source-level condition root. This keeps `BranchNode.conditionRoot` aligned with the
    /// concrete value being tested:
    /// - plain condition roots keep themselves
    /// - `not x` strips the wrapper and only swaps true/false targets
    /// - short-circuit branches publish separate roots for `a`, `b`, ... instead of
    ///   repeating the outer `a and b` / `a or b` shell on every split
    private @NotNull ConditionBuild publishConditionBranch(
            @NotNull String entryId,
            @NotNull OpenSequence conditionSequence,
            @NotNull Expression conditionFragmentRoot,
            @NotNull String conditionValueId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var branchId = nextBranchId();
        publishSequenceNode(conditionSequence.id(), conditionSequence.items(), branchId);
        requireNodes().put(
                branchId,
                new FrontendCfgGraph.BranchNode(
                        branchId,
                        conditionFragmentRoot,
                        conditionValueId,
                        trueTargetId,
                        falseTargetId
                )
        );
        return new ConditionBuild(entryId);
    }

    /// Recursively materializes one lowering-ready value and returns both the current writable
    /// continuation and the published frontend-local value id.
    ///
    /// The builder deliberately special-cases only operations whose semantics later lowering must not
    /// rediscover from raw AST alone. Generic expressions still use `OpaqueExprValueItem`, but only
    /// after all of their lowering-ready children have already published explicit operand ids.
    /// Short-circuit `and` / `or` no longer share the generic eager route; they branch through the
    /// shared condition core and merge one explicit bool result value back to the parent consumer.
    private @NotNull ValueBuild buildValue(
            @NotNull BuildCursor cursor,
            @NotNull Expression expression,
            @Nullable String preferredResultValueId
    ) {
        requireLoweringReadyExpressionType(expression);
        return switch (expression) {
            case AssignmentExpression _ -> throw new IllegalStateException(
                    "Assignment expressions do not produce a lowering-ready value in the current compile surface"
            );
            case AttributeExpression attributeExpression -> buildAttributeExpressionValue(
                    cursor,
                    attributeExpression,
                    preferredResultValueId
            );
            case CallExpression callExpression -> buildBareCallValue(cursor, callExpression, preferredResultValueId);
            case SubscriptExpression subscriptExpression -> buildPlainSubscriptValue(
                    cursor,
                    subscriptExpression,
                    preferredResultValueId
            );
            case CastExpression castExpression -> buildCastValue(cursor, castExpression, preferredResultValueId);
            case TypeTestExpression typeTestExpression ->
                    buildTypeTestValue(cursor, typeTestExpression, preferredResultValueId);
            case ConditionalExpression conditionalExpression -> buildConditionalExpressionValue(conditionalExpression);
            case UnaryExpression unaryExpression -> {
                var operandBuild = buildValue(cursor, unaryExpression.operand(), null);
                yield emitOpaqueValue(
                        operandBuild.cursor(),
                        unaryExpression,
                        List.of(operandBuild.resultValueId()),
                        preferredResultValueId
                );
            }
            case BinaryExpression binaryExpression when isShortCircuitBinaryExpression(binaryExpression) ->
                    buildShortCircuitBinaryValue(cursor, binaryExpression, preferredResultValueId);
            case BinaryExpression binaryExpression -> {
                var leftBuild = buildValue(cursor, binaryExpression.left(), null);
                var rightBuild = buildValue(leftBuild.cursor(), binaryExpression.right(), null);
                yield emitOpaqueValue(
                        rightBuild.cursor(),
                        binaryExpression,
                        List.of(leftBuild.resultValueId(), rightBuild.resultValueId()),
                        preferredResultValueId
                );
            }
            case IdentifierExpression _, LiteralExpression _, SelfExpression _ -> emitOpaqueValue(
                    cursor,
                    expression,
                    List.of(),
                    preferredResultValueId
            );
            default -> throw unsupportedReachableExpression(expression);
        };
    }

    /// Attribute chains are expanded step by step so later lowering receives explicit intermediate
    /// value ids instead of having to rerun chain reduction over the full outer expression root.
    private @NotNull ValueBuild buildAttributeExpressionValue(
            @NotNull BuildCursor cursor,
            @NotNull AttributeExpression attributeExpression,
            @Nullable String preferredResultValueId
    ) {
        if (attributeExpression.steps().isEmpty()) {
            throw new IllegalStateException("AttributeExpression must contain at least one step");
        }
        if (attributeExpression.base() instanceof IdentifierExpression identifierExpression
                && Objects.requireNonNull(analysisData).symbolBindings().get(identifierExpression) instanceof FrontendBinding binding
                && binding.kind() == FrontendBindingKind.TYPE_META) {
            return buildTypeMetaHeadAttributeExpressionValue(cursor, attributeExpression, preferredResultValueId);
        }
        var currentBuild = buildValue(cursor, attributeExpression.base(), null);
        for (var stepIndex = 0; stepIndex < attributeExpression.steps().size(); stepIndex++) {
            var step = attributeExpression.steps().get(stepIndex);
            currentBuild = applyAttributeStep(
                    currentBuild,
                    step,
                    stepIndex + 1 == attributeExpression.steps().size() ? preferredResultValueId : null
            );
        }
        return currentBuild;
    }

    /// Type-meta chain heads such as `Vector3.ZERO`, `Color.RED`, `Node.new()` or `Worker.build(...)`
    /// do not materialize the head identifier as a runtime value.
    ///
    /// The first lowering step must therefore start directly from a published type-meta fact:
    /// - static member loads publish `MemberLoadItem(..., null, ...)`
    /// - static/constructor calls publish `CallItem(..., null, ...)`
    ///
    /// Only subsequent steps consume the produced result as an ordinary runtime value.
    private @NotNull ValueBuild buildTypeMetaHeadAttributeExpressionValue(
            @NotNull BuildCursor cursor,
            @NotNull AttributeExpression attributeExpression,
            @Nullable String preferredResultValueId
    ) {
        var currentBuild = switch (attributeExpression.steps().getFirst()) {
            case AttributePropertyStep firstPropertyStep -> buildTypeMetaHeadMemberStep(
                    cursor,
                    firstPropertyStep,
                    attributeExpression.steps().size() == 1 ? preferredResultValueId : null
            );
            case AttributeCallStep firstCallStep -> buildTypeMetaHeadCallStep(
                    cursor,
                    firstCallStep,
                    attributeExpression.steps().size() == 1 ? preferredResultValueId : null
            );
            default -> throw new IllegalStateException(
                    "Type-meta attribute head '" + attributeExpression.base()
                            + "' currently requires a property step or call step to enter lowering"
            );
        };
        for (var stepIndex = 1; stepIndex < attributeExpression.steps().size(); stepIndex++) {
            var step = attributeExpression.steps().get(stepIndex);
            currentBuild = applyAttributeStep(
                    currentBuild,
                    step,
                    stepIndex + 1 == attributeExpression.steps().size() ? preferredResultValueId : null
            );
        }
        return currentBuild;
    }

    private @NotNull ValueBuild buildTypeMetaHeadMemberStep(
            @NotNull BuildCursor cursor,
            @NotNull AttributePropertyStep attributePropertyStep,
            @Nullable String preferredResultValueId
    ) {
        var publishedMember = requireLoweringReadyMember(attributePropertyStep);
        var resultValueId = chooseResultValueId(preferredResultValueId);
        cursor.currentSequence().items().add(new MemberLoadItem(
                attributePropertyStep,
                publishedMember.memberName(),
                null,
                resultValueId
        ));
        return valueRootBuild(cursor, attributePropertyStep, resultValueId);
    }

    private @NotNull ValueBuild buildTypeMetaHeadCallStep(
            @NotNull BuildCursor cursor,
            @NotNull AttributeCallStep attributeCallStep,
            @Nullable String preferredResultValueId
    ) {
        var publishedCall = requireLoweringReadyCall(attributeCallStep);
        var argumentsBuild = buildArgumentValues(cursor, attributeCallStep.arguments());
        var resultValueId = chooseResultValueId(preferredResultValueId);
        argumentsBuild.cursor().currentSequence().items().add(new CallItem(
                attributeCallStep,
                publishedCall.callableName(),
                null,
                argumentsBuild.valueIds(),
                resultValueId
        ));
        return valueRootBuild(argumentsBuild.cursor(), attributeCallStep, resultValueId);
    }

    /// Bare call lowering consumes the published `resolvedCalls()` fact directly.
    ///
    /// The current compile-ready contract permits:
    /// - ordinary bare/global/static calls
    /// - bare builtin direct constructors such as `Vector3i(...)`
    ///
    /// Callable-value invocation stays outside the accepted lowering surface, so a non-identifier callee
    /// is treated as a protocol violation instead of being silently dropped from operand ordering.
    private @NotNull ValueBuild buildBareCallValue(
            @NotNull BuildCursor cursor,
            @NotNull CallExpression callExpression,
            @Nullable String preferredResultValueId
    ) {
        if (!(callExpression.callee() instanceof IdentifierExpression)) {
            throw new IllegalStateException(
                    "Bare call lowering currently requires an IdentifierExpression callee, but got "
                            + callExpression.callee().getClass().getSimpleName()
            );
        }
        var publishedCall = requireLoweringReadyCall(callExpression);
        var argumentsBuild = buildArgumentValues(cursor, callExpression.arguments());
        var resultValueId = chooseResultValueId(preferredResultValueId);
        argumentsBuild.cursor().currentSequence().items().add(new CallItem(
                callExpression,
                publishedCall.callableName(),
                null,
                argumentsBuild.valueIds(),
                resultValueId
        ));
        return valueRootBuild(argumentsBuild.cursor(), callExpression, resultValueId);
    }

    /// Plain subscripts first materialize their base and arguments, then commit one explicit indexed
    /// read item that consumes those operand ids.
    private @NotNull ValueBuild buildPlainSubscriptValue(
            @NotNull BuildCursor cursor,
            @NotNull SubscriptExpression subscriptExpression,
            @Nullable String preferredResultValueId
    ) {
        var baseBuild = buildValue(cursor, subscriptExpression.base(), null);
        var argumentsBuild = buildArgumentValues(baseBuild.cursor(), subscriptExpression.arguments());
        var resultValueId = chooseResultValueId(preferredResultValueId);
        argumentsBuild.cursor().currentSequence().items().add(new SubscriptLoadItem(
                subscriptExpression,
                null,
                baseBuild.resultValueId(),
                argumentsBuild.valueIds(),
                resultValueId
        ));
        return new ValueBuild(
                argumentsBuild.cursor(),
                subscriptExpression,
                resultValueId,
                appendSubscriptWritableRoute(
                        baseBuild,
                        subscriptExpression,
                        argumentsBuild.valueIds(),
                        determineWritableSubscriptAccessKind(
                                subscriptExpression,
                                requireWritableRouteAnchorType(subscriptExpression.base()),
                                requireWritableRouteAnchorType(subscriptExpression.arguments().getFirst())
                        )
                )
        );
    }

    /// Cast expressions are still compile-blocked by the default pipeline, but the item contract is
    /// already wired so targeted builder tests and later compile-surface expansion do not require a
    /// structural rewrite of the frontend CFG surface.
    private @NotNull ValueBuild buildCastValue(
            @NotNull BuildCursor cursor,
            @NotNull CastExpression castExpression,
            @Nullable String preferredResultValueId
    ) {
        var operandBuild = buildValue(cursor, castExpression.value(), null);
        var resultValueId = chooseResultValueId(preferredResultValueId);
        operandBuild.cursor().currentSequence().items().add(new CastItem(
                castExpression,
                operandBuild.resultValueId(),
                resultValueId
        ));
        return new ValueBuild(operandBuild.cursor(), castExpression, resultValueId, null);
    }

    /// Type-test expressions share the same “child first, then one explicit result item” contract as
    /// casts. They stay compile-blocked today, but the CFG item surface already preserves the operand /
    /// result boundary needed when that route is accepted later.
    private @NotNull ValueBuild buildTypeTestValue(
            @NotNull BuildCursor cursor,
            @NotNull TypeTestExpression typeTestExpression,
            @Nullable String preferredResultValueId
    ) {
        var operandBuild = buildValue(cursor, typeTestExpression.value(), null);
        var resultValueId = chooseResultValueId(preferredResultValueId);
        operandBuild.cursor().currentSequence().items().add(new TypeTestItem(
                typeTestExpression,
                operandBuild.resultValueId(),
                resultValueId
        ));
        return new ValueBuild(operandBuild.cursor(), typeTestExpression, resultValueId, null);
    }

    /// Value-context `and` / `or` reuse the condition builder so only the necessary operand path is
    /// evaluated. The taken arm then writes an explicit bool constant into a shared merged result id.
    private @NotNull ValueBuild buildShortCircuitBinaryValue(
            @NotNull BuildCursor cursor,
            @NotNull BinaryExpression binaryExpression,
            @Nullable String preferredResultValueId
    ) {
        var resultValueId = chooseResultValueId(preferredResultValueId);
        var mergeSequence = new OpenSequence(nextSequenceId());
        var rightCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var trueWriteSequence = new OpenSequence(nextSequenceId());
        var falseWriteSequence = new OpenSequence(nextSequenceId());

        var shortCircuitOperator = requireShortCircuitBinaryOperator(binaryExpression);
        var entryBuild = switch (shortCircuitOperator) {
            case AND -> {
                var leftCondition = buildCondition(
                        cursor,
                        binaryExpression.left(),
                        rightCursor.entryId(),
                        falseWriteSequence.id()
                );
                buildCondition(
                        rightCursor,
                        binaryExpression.right(),
                        trueWriteSequence.id(),
                        falseWriteSequence.id()
                );
                yield leftCondition;
            }
            case OR -> {
                var leftCondition = buildCondition(
                        cursor,
                        binaryExpression.left(),
                        trueWriteSequence.id(),
                        rightCursor.entryId()
                );
                buildCondition(
                        rightCursor,
                        binaryExpression.right(),
                        trueWriteSequence.id(),
                        falseWriteSequence.id()
                );
                yield leftCondition;
            }
            default -> throw unsupportedShortCircuitBinary(binaryExpression);
        };

        publishMergedBooleanWriteSequence(
                trueWriteSequence,
                binaryExpression,
                true,
                resultValueId,
                mergeSequence.id()
        );
        publishMergedBooleanWriteSequence(
                falseWriteSequence,
                binaryExpression,
                false,
                resultValueId,
                mergeSequence.id()
        );
        return new ValueBuild(new BuildCursor(entryBuild.entryId(), mergeSequence), binaryExpression, resultValueId, null);
    }

    /// Condition-context `and` / `or` split the left fragment first, then only build the right
    /// fragment on the path where source semantics require it.
    private @NotNull ConditionBuild buildShortCircuitCondition(
            @NotNull BuildCursor cursor,
            @NotNull BinaryExpression binaryExpression,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var shortCircuitOperator = requireShortCircuitBinaryOperator(binaryExpression);
        var rightCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        return switch (shortCircuitOperator) {
            case AND -> {
                var leftCondition = buildCondition(
                        cursor,
                        binaryExpression.left(),
                        rightCursor.entryId(),
                        falseTargetId
                );
                buildCondition(
                        rightCursor,
                        binaryExpression.right(),
                        trueTargetId,
                        falseTargetId
                );
                yield leftCondition;
            }
            case OR -> {
                var leftCondition = buildCondition(
                        cursor,
                        binaryExpression.left(),
                        trueTargetId,
                        rightCursor.entryId()
                );
                buildCondition(
                        rightCursor,
                        binaryExpression.right(),
                        trueTargetId,
                        falseTargetId
                );
                yield leftCondition;
            }
            default -> throw unsupportedShortCircuitBinary(binaryExpression);
        };
    }

    private @NotNull ValueBuild buildConditionalExpressionValue(@NotNull ConditionalExpression conditionalExpression) {
        throw unsupportedConditionalExpression(conditionalExpression);
    }

    private @NotNull ConditionBuild buildConditionalExpressionCondition(
            @NotNull ConditionalExpression conditionalExpression
    ) {
        throw unsupportedConditionalExpression(conditionalExpression);
    }

    /// Plain assignment preserves target evaluation before RHS evaluation.
    ///
    /// Compound assignment uses a separate read-modify-write path so `AssignmentItem` can stay the
    /// minimal "final store commit" node.
    private @NotNull BuildCursor buildAssignmentCommit(
            @NotNull BuildCursor cursor,
            @NotNull AssignmentExpression assignmentExpression
    ) {
        if (isCompoundAssignmentOperator(assignmentExpression)) {
            return buildCompoundAssignmentCommit(cursor, assignmentExpression);
        }
        var targetOperandsBuild = buildAssignmentTargetOperands(cursor, assignmentExpression.left());
        var rhsBuild = buildValue(targetOperandsBuild.cursor(), assignmentExpression.right(), null);
        rhsBuild.cursor().currentSequence().items().add(new AssignmentItem(
                assignmentExpression,
                targetOperandsBuild.valueIds(),
                rhsBuild.resultValueId(),
                null,
                targetOperandsBuild.writableRoutePayload().withRouteAnchor(assignmentExpression)
        ));
        return rhsBuild.cursor();
    }

    /// Compound assignment must freeze read-modify-write explicitly instead of pretending the target
    /// can be rebuilt from source later.
    ///
    /// The source-order contract is:
    /// 1. freeze target receiver/index operands once
    /// 2. read the current target value from those frozen operands
    /// 3. evaluate the RHS
    /// 4. publish one explicit compound-op value item
    /// 5. commit the computed result through the ordinary assignment store route
    private @NotNull BuildCursor buildCompoundAssignmentCommit(
            @NotNull BuildCursor cursor,
            @NotNull AssignmentExpression assignmentExpression
    ) {
        var binaryOperatorLexeme = requireCompoundBinaryOperatorLexeme(assignmentExpression);
        var targetOperandsBuild = buildAssignmentTargetOperands(cursor, assignmentExpression.left());
        var currentTargetValueBuild = buildCompoundAssignmentCurrentTargetValue(
                targetOperandsBuild.cursor(),
                assignmentExpression.left(),
                targetOperandsBuild.valueIds()
        );
        var rhsBuild = buildValue(currentTargetValueBuild.cursor(), assignmentExpression.right(), null);
        var compoundResultValueId = nextValueId();
        rhsBuild.cursor().currentSequence().items().add(new CompoundAssignmentBinaryOpItem(
                assignmentExpression,
                binaryOperatorLexeme,
                currentTargetValueBuild.resultValueId(),
                rhsBuild.resultValueId(),
                compoundResultValueId
        ));
        rhsBuild.cursor().currentSequence().items().add(new AssignmentItem(
                assignmentExpression,
                targetOperandsBuild.valueIds(),
                compoundResultValueId,
                null,
                targetOperandsBuild.writableRoutePayload().withRouteAnchor(assignmentExpression)
        ));
        return rhsBuild.cursor();
    }

    /// Builds the already-evaluated operands required to commit one assignment target.
    ///
    /// The target AST itself remains on `AssignmentItem`, but any child expressions with real
    /// evaluation order, such as chain prefixes or subscript arguments, are materialized here so
    /// later lowering does not need to recurse back into the target subtree to discover them.
    ///
    /// Every currently supported assignment target must also publish one writable-route payload here.
    /// Step-4 body lowering no longer has an AST-replay fallback for final stores.
    private @NotNull AssignmentTargetBuild buildAssignmentTargetOperands(
            @NotNull BuildCursor cursor,
            @NotNull Expression targetExpression
    ) {
        return switch (targetExpression) {
            case IdentifierExpression identifierExpression -> new AssignmentTargetBuild(
                    cursor,
                    List.of(),
                    buildIdentifierWritableRoute(identifierExpression)
            );
            case AttributeExpression attributeExpression -> buildAttributeTargetOperands(cursor, attributeExpression);
            case SubscriptExpression subscriptExpression -> {
                var operands = new ArrayList<String>(1 + subscriptExpression.arguments().size());
                var baseBuild = buildAssignmentTargetValue(cursor, subscriptExpression.base());
                operands.add(baseBuild.resultValueId());
                var argumentsBuild = buildArgumentValues(baseBuild.cursor(), subscriptExpression.arguments());
                operands.addAll(argumentsBuild.valueIds());
                yield new AssignmentTargetBuild(
                        argumentsBuild.cursor(),
                        List.copyOf(operands),
                        appendSubscriptWritableRoute(
                                baseBuild,
                                subscriptExpression,
                                argumentsBuild.valueIds(),
                                determineWritableSubscriptAccessKind(
                                        subscriptExpression,
                                        requireWritableRouteAnchorType(subscriptExpression.base()),
                                        requireWritableRouteAnchorType(subscriptExpression.arguments().getFirst())
                                )
                        ).withRouteAnchor(subscriptExpression)
                );
            }
            default -> throw unsupportedReachableAssignmentTarget(targetExpression);
        };
    }

    /// Compound assignment reuses the already-frozen assignment-target operands to read the current
    /// target value. Rebuilding the original LHS expression here would duplicate side effects such as
    /// prefix calls or subscript indexes.
    private @NotNull ValueBuild buildCompoundAssignmentCurrentTargetValue(
            @NotNull BuildCursor cursor,
            @NotNull Expression targetExpression,
            @NotNull List<String> frozenTargetOperandValueIds
    ) {
        return switch (targetExpression) {
            case IdentifierExpression identifierExpression ->
                    emitOpaqueValue(cursor, identifierExpression, List.of(), null);
            case AttributeExpression attributeExpression -> buildCompoundAssignmentAttributeTargetValue(
                    cursor,
                    attributeExpression,
                    frozenTargetOperandValueIds
            );
            case SubscriptExpression subscriptExpression -> buildCompoundAssignmentSubscriptTargetValue(
                    cursor,
                    subscriptExpression,
                    frozenTargetOperandValueIds
            );
            default -> throw unsupportedReachableAssignmentTarget(targetExpression);
        };
    }

    /// Attribute assignment targets lower every prefix step as an ordinary read/call/subscript chain,
    /// then stop before the final writable slot.
    ///
    /// For example:
    /// - `obj.payload = rhs` publishes only the receiver value for `obj`
    /// - `obj.items[i] = rhs` publishes the receiver value for `obj` plus the index operand ids
    /// - `obj.a().items[i] = rhs` first builds `obj.a()` as explicit prefix value-ops, then exports
    ///   the final target receiver/index operands
    private @NotNull AssignmentTargetBuild buildAttributeTargetOperands(
            @NotNull BuildCursor cursor,
            @NotNull AttributeExpression attributeExpression
    ) {
        if (attributeExpression.steps().isEmpty()) {
            throw new IllegalStateException("AttributeExpression assignment target must contain at least one step");
        }

        var currentBuild = buildAssignmentTargetValue(cursor, attributeExpression.base());
        for (var stepIndex = 0; stepIndex + 1 < attributeExpression.steps().size(); stepIndex++) {
            currentBuild = applyAttributeStep(currentBuild, attributeExpression.steps().get(stepIndex), null);
        }

        var finalStep = attributeExpression.steps().getLast();
        return switch (finalStep) {
            case AttributePropertyStep attributePropertyStep -> new AssignmentTargetBuild(
                    currentBuild.cursor(),
                    List.of(currentBuild.resultValueId()),
                    appendPropertyWritableRoute(
                            currentBuild,
                            attributePropertyStep,
                            attributePropertyStep.name()
                    ).withRouteAnchor(attributeExpression)
            );
            case AttributeSubscriptStep attributeSubscriptStep -> {
                var operands = new ArrayList<String>(1 + attributeSubscriptStep.arguments().size());
                operands.add(currentBuild.resultValueId());
                var argumentsBuild = buildArgumentValues(currentBuild.cursor(), attributeSubscriptStep.arguments());
                operands.addAll(argumentsBuild.valueIds());
                yield new AssignmentTargetBuild(
                        argumentsBuild.cursor(),
                        List.copyOf(operands),
                        appendSubscriptWritableRoute(
                                currentBuild,
                                attributeSubscriptStep,
                                argumentsBuild.valueIds(),
                                determineWritableSubscriptAccessKind(
                                        attributeSubscriptStep,
                                        requireWritableRouteAnchorType(currentBuild.valueAnchor()),
                                        requireWritableRouteAnchorType(attributeSubscriptStep.arguments().getFirst())
                                )
                        ).withRouteAnchor(attributeExpression)
                );
            }
            default -> throw new IllegalStateException(
                    "Assignment target step '"
                            + finalStep.getClass().getSimpleName()
                            + "' is not supported by the current frontend CFG contract"
            );
        };
    }

    /// Attribute compound-assignment reads reuse the frozen final receiver/index operands instead of
    /// replaying the prefix chain. The final writable step becomes one explicit load item that feeds
    /// the later compound binary op.
    private @NotNull ValueBuild buildCompoundAssignmentAttributeTargetValue(
            @NotNull BuildCursor cursor,
            @NotNull AttributeExpression attributeExpression,
            @NotNull List<String> frozenTargetOperandValueIds
    ) {
        if (attributeExpression.steps().isEmpty()) {
            throw new IllegalStateException("AttributeExpression compound target must contain at least one step");
        }
        var finalStep = attributeExpression.steps().getLast();
        return switch (finalStep) {
            case AttributePropertyStep attributePropertyStep -> {
                requireLoweringReadyCompoundMemberRead(
                        attributePropertyStep,
                        "compound attribute-property current-value read"
                );
                var receiverValueId = requireFrozenTargetOperandValue(
                        frozenTargetOperandValueIds,
                        0,
                        attributeExpression,
                        "receiver"
                );
                var resultValueId = nextValueId();
                cursor.currentSequence().items().add(new MemberLoadItem(
                        attributePropertyStep,
                        attributePropertyStep.name(),
                        receiverValueId,
                        resultValueId
                ));
                yield new ValueBuild(cursor, attributePropertyStep, resultValueId, null);
            }
            case AttributeSubscriptStep attributeSubscriptStep -> {
                var receiverValueId = requireFrozenTargetOperandValue(
                        frozenTargetOperandValueIds,
                        0,
                        attributeExpression,
                        "receiver"
                );
                var resultValueId = nextValueId();
                cursor.currentSequence().items().add(new SubscriptLoadItem(
                        attributeSubscriptStep,
                        attributeSubscriptStep.name(),
                        receiverValueId,
                        requireFrozenTargetTrailingOperands(frozenTargetOperandValueIds, attributeExpression),
                        resultValueId
                ));
                yield new ValueBuild(cursor, attributeSubscriptStep, resultValueId, null);
            }
            default -> throw new IllegalStateException(
                    "Compound assignment target step '"
                            + finalStep.getClass().getSimpleName()
                            + "' is not supported by the current frontend CFG contract"
            );
        };
    }

    private @NotNull ValueBuild buildCompoundAssignmentSubscriptTargetValue(
            @NotNull BuildCursor cursor,
            @NotNull SubscriptExpression subscriptExpression,
            @NotNull List<String> frozenTargetOperandValueIds
    ) {
        var receiverValueId = requireFrozenTargetOperandValue(
                frozenTargetOperandValueIds,
                0,
                subscriptExpression,
                "base"
        );
        var resultValueId = nextValueId();
        cursor.currentSequence().items().add(new SubscriptLoadItem(
                subscriptExpression,
                null,
                receiverValueId,
                requireFrozenTargetTrailingOperands(frozenTargetOperandValueIds, subscriptExpression),
                resultValueId
        ));
        return new ValueBuild(cursor, subscriptExpression, resultValueId, null);
    }

    /// Assignment-target prefixes are not always part of the ordinary published `expressionTypes()`
    /// surface.
    ///
    /// For example, the container base of `items[idx] = rhs` may only be visited through assignment
    /// target analysis, so the builder cannot require a normal expression-type entry before it
    /// materializes the receiver value. This helper therefore falls back to a target-specific value
    /// path for those prefixes while still reusing ordinary `buildValue(...)` whenever a lowering-ready
    /// expression fact actually exists.
    private @NotNull ValueBuild buildAssignmentTargetValue(
            @NotNull BuildCursor cursor,
            @NotNull Expression expression
    ) {
        if (hasLoweringReadyExpressionType(expression)) {
            return buildValue(cursor, expression, null);
        }
        return switch (expression) {
            case IdentifierExpression _, SelfExpression _ -> emitOpaqueValue(cursor, expression, List.of(), null);
            case AttributeExpression attributeExpression ->
                    buildAssignmentTargetAttributeValue(cursor, attributeExpression);
            case SubscriptExpression subscriptExpression ->
                    buildAssignmentTargetSubscriptValue(cursor, subscriptExpression);
            case CallExpression callExpression -> buildBareCallValue(cursor, callExpression, null);
            default -> throw new IllegalStateException(
                    "Assignment target value "
                            + expression.getClass().getSimpleName()
                            + " is missing a lowering-ready expression fact"
            );
        };
    }

    private @NotNull ValueBuild buildAssignmentTargetAttributeValue(
            @NotNull BuildCursor cursor,
            @NotNull AttributeExpression attributeExpression
    ) {
        if (attributeExpression.steps().isEmpty()) {
            throw new IllegalStateException("AttributeExpression target value must contain at least one step");
        }
        var currentBuild = buildAssignmentTargetValue(cursor, attributeExpression.base());
        for (var step : attributeExpression.steps()) {
            currentBuild = applyAttributeStep(currentBuild, step, null);
        }
        return currentBuild;
    }

    private @NotNull ValueBuild buildAssignmentTargetSubscriptValue(
            @NotNull BuildCursor cursor,
            @NotNull SubscriptExpression subscriptExpression
    ) {
        var baseBuild = buildAssignmentTargetValue(cursor, subscriptExpression.base());
        var argumentsBuild = buildArgumentValues(baseBuild.cursor(), subscriptExpression.arguments());
        var resultValueId = chooseResultValueId(null);
        argumentsBuild.cursor().currentSequence().items().add(new SubscriptLoadItem(
                subscriptExpression,
                null,
                baseBuild.resultValueId(),
                argumentsBuild.valueIds(),
                resultValueId
        ));
        return new ValueBuild(
                argumentsBuild.cursor(),
                subscriptExpression,
                resultValueId,
                appendSubscriptWritableRoute(
                        baseBuild,
                        subscriptExpression,
                        argumentsBuild.valueIds(),
                        determineWritableSubscriptAccessKind(
                                subscriptExpression,
                                requireWritableRouteAnchorType(subscriptExpression.base()),
                                requireWritableRouteAnchorType(subscriptExpression.arguments().getFirst())
                        )
                )
        );
    }

    /// Applies one attribute-chain step to the current receiver value and returns the produced value id.
    ///
    /// The step kind decides which explicit item is emitted, but the overall contract stays uniform:
    /// the receiver value id arrives from the previous chain segment, step-local arguments are built
    /// before the item is appended, and the returned value id becomes the receiver for the next step.
    private @NotNull ValueBuild applyAttributeStep(
            @NotNull ValueBuild receiverBuild,
            @NotNull AttributeStep step,
            @Nullable String preferredResultValueId
    ) {
        return switch (step) {
            case AttributePropertyStep attributePropertyStep -> {
                var publishedMember = requireLoweringReadyMember(attributePropertyStep);
                var resultValueId = chooseResultValueId(preferredResultValueId);
                receiverBuild.cursor().currentSequence().items().add(new MemberLoadItem(
                        attributePropertyStep,
                        publishedMember.memberName(),
                        receiverBuild.resultValueId(),
                        resultValueId
                ));
                yield new ValueBuild(
                        receiverBuild.cursor(),
                        attributePropertyStep,
                        resultValueId,
                        appendPropertyWritableRoute(
                                receiverBuild,
                                attributePropertyStep,
                                publishedMember.memberName()
                        )
                );
            }
            case AttributeCallStep attributeCallStep -> {
                var publishedCall = requireLoweringReadyCall(attributeCallStep);
                receiverBuild = maybePublishDirectSlotReceiverAlias(
                        receiverBuild,
                        publishedCall,
                        attributeCallStep.arguments()
                );
                var argumentsBuild = buildArgumentValues(receiverBuild.cursor(), attributeCallStep.arguments());
                var resultValueId = chooseResultValueId(preferredResultValueId);
                var receiverRoute = routePayloadOrValueRoot(receiverBuild);
                argumentsBuild.cursor().currentSequence().items().add(new CallItem(
                        attributeCallStep,
                        publishedCall.callableName(),
                        receiverBuild.resultValueId(),
                        argumentsBuild.valueIds(),
                        resultValueId,
                        // Mutating call receivers reuse the current receiver leaf as the call object and
                        // therefore need the promoted leaf to appear in reverseCommitSteps. Without this,
                        // property/subscript receivers would carry provenance but no actual post-call
                        // writeback plan.
                        new FrontendWritableRoutePayload(
                                attributeCallStep,
                                receiverRoute.root(),
                                receiverRoute.leaf(),
                                appendPromotedLeaf(receiverRoute)
                        )
                ));
                yield valueRootBuild(argumentsBuild.cursor(), attributeCallStep, resultValueId);
            }
            case AttributeSubscriptStep attributeSubscriptStep -> {
                var argumentsBuild = buildArgumentValues(receiverBuild.cursor(), attributeSubscriptStep.arguments());
                var resultValueId = chooseResultValueId(preferredResultValueId);
                argumentsBuild.cursor().currentSequence().items().add(new SubscriptLoadItem(
                        attributeSubscriptStep,
                        attributeSubscriptStep.name(),
                        receiverBuild.resultValueId(),
                        argumentsBuild.valueIds(),
                        resultValueId
                ));
                yield new ValueBuild(
                        argumentsBuild.cursor(),
                        attributeSubscriptStep,
                        resultValueId,
                        appendSubscriptWritableRoute(
                                receiverBuild,
                                attributeSubscriptStep,
                                argumentsBuild.valueIds(),
                                determineWritableSubscriptAccessKind(
                                        attributeSubscriptStep,
                                        requireWritableRouteAnchorType(receiverBuild.valueAnchor()),
                                        requireWritableRouteAnchorType(attributeSubscriptStep.arguments().getFirst())
                                )
                        )
                );
            }
            default -> throw new IllegalStateException(
                    "Unsupported attribute step in frontend CFG builder: " + step.getClass().getSimpleName()
            );
        };
    }

    private @NotNull ValueListBuild buildArgumentValues(
            @NotNull BuildCursor cursor,
            @NotNull List<Expression> arguments
    ) {
        var currentCursor = cursor;
        var valueIds = new ArrayList<String>(arguments.size());
        for (var argument : arguments) {
            var argumentBuild = buildValue(currentCursor, argument, null);
            currentCursor = argumentBuild.cursor();
            valueIds.add(argumentBuild.resultValueId());
        }
        return new ValueListBuild(currentCursor, List.copyOf(valueIds));
    }

    /// Generic opaque items still exist as a bridge for simple expression forms whose exact lowering
    /// will be finalized later, but they no longer hide nested child evaluation order.
    private @NotNull ValueBuild emitOpaqueValue(
            @NotNull BuildCursor cursor,
            @NotNull Expression expression,
            @NotNull List<String> operandValueIds,
            @Nullable String preferredResultValueId
    ) {
        var resultValueId = chooseResultValueId(preferredResultValueId);
        cursor.currentSequence().items().add(new OpaqueExprValueItem(expression, operandValueIds, resultValueId));
        return new ValueBuild(
                cursor,
                expression,
                resultValueId,
                routePayloadForOpaqueExpression(expression)
        );
    }

    /// Step 6 keeps alias publication narrower than generic identifier/self lowering: this item is
    /// reserved for direct-slot mutating receivers whose dedicated value id must stay bound to one
    /// trusted source slot instead of becoming a dead `cfg_tmp_*`.
    private @NotNull ValueBuild emitDirectSlotAliasValue(
            @NotNull BuildCursor cursor,
            @NotNull Expression expression,
            @Nullable String preferredResultValueId
    ) {
        var resultValueId = chooseResultValueId(preferredResultValueId);
        cursor.currentSequence().items().add(new DirectSlotAliasValueItem(expression, resultValueId));
        return new ValueBuild(
                cursor,
                expression,
                resultValueId,
                routePayloadForOpaqueExpression(expression)
        );
    }

    /// Ordinary value publication and writable-route publication are deliberately kept separate:
    /// CFG items still own evaluation order, while the route payload only freezes how a later
    /// mutation on the published value could be written back into its owner chain.
    private @Nullable FrontendWritableRoutePayload routePayloadForOpaqueExpression(@NotNull Expression expression) {
        return switch (expression) {
            case IdentifierExpression identifierExpression -> buildIdentifierWritableRoute(identifierExpression);
            case SelfExpression selfExpression -> new FrontendWritableRoutePayload(
                    selfExpression,
                    new FrontendWritableRoutePayload.RootDescriptor(
                            FrontendWritableRoutePayload.RootKind.DIRECT_SLOT,
                            selfExpression,
                            null
                    ),
                    new FrontendWritableRoutePayload.LeafDescriptor(
                            FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT,
                            selfExpression,
                            null,
                            List.of(),
                            null,
                            null
                    ),
                    List.of()
            );
            default -> null;
        };
    }

    private @NotNull FrontendWritableRoutePayload buildIdentifierWritableRoute(
            @NotNull IdentifierExpression identifierExpression
    ) {
        var binding = requirePublishedBinding(identifierExpression);
        return switch (binding.kind()) {
            case LOCAL_VAR, PARAMETER, CAPTURE -> new FrontendWritableRoutePayload(
                    identifierExpression,
                    new FrontendWritableRoutePayload.RootDescriptor(
                            FrontendWritableRoutePayload.RootKind.DIRECT_SLOT,
                            identifierExpression,
                            null
                    ),
                    new FrontendWritableRoutePayload.LeafDescriptor(
                            FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT,
                            identifierExpression,
                            null,
                            List.of(),
                            null,
                            null
                    ),
                    List.of()
            );
            case SELF -> throw new IllegalStateException(
                    "Identifier writable-route publication must use explicit SelfExpression instead of binding kind SELF"
            );
            case PROPERTY -> new FrontendWritableRoutePayload(
                    identifierExpression,
                    new FrontendWritableRoutePayload.RootDescriptor(
                            isStaticPropertyBinding(binding)
                                    ? FrontendWritableRoutePayload.RootKind.STATIC_CONTEXT
                                    : FrontendWritableRoutePayload.RootKind.SELF_CONTEXT,
                            identifierExpression,
                            null
                    ),
                    new FrontendWritableRoutePayload.LeafDescriptor(
                            FrontendWritableRoutePayload.LeafKind.PROPERTY,
                            identifierExpression,
                            null,
                            List.of(),
                            binding.symbolName(),
                            null
                    ),
                    List.of()
            );
            default -> throw new IllegalStateException(
                    "Identifier writable-route publication is not supported for binding kind " + binding.kind()
            );
        };
    }

    private @NotNull FrontendWritableRoutePayload routePayloadOrValueRoot(@NotNull ValueBuild valueBuild) {
        return valueBuild.writableRoutePayloadOrNull() != null
                ? valueBuild.writableRoutePayloadOrNull()
                : valueRootRoutePayload(valueBuild.valueAnchor(), valueBuild.resultValueId());
    }

    /// Direct-slot alias publication is valid only for the narrow mutating-receiver surface:
    /// - the receiver is already a direct-slot writable root
    /// - the current publication is still the generic opaque temp path
    /// - the receiver belongs to one explicit root category (`SelfExpression`, `LOCAL_VAR`,
    ///   `PARAMETER`) instead of an implicit/self-context fallback
    /// - `CAPTURE` is intentionally excluded until lambda/capture lowering semantics are frozen;
    ///   otherwise Step 6 would prematurely promise alias behavior for a deferred surface
    /// - for identifier-backed roots, later argument evaluation must stay inside a proven
    ///   no-rebinding subset; otherwise Step 6 deliberately keeps the ordinary temp snapshot
    private @NotNull ValueBuild maybePublishDirectSlotReceiverAlias(
            @NotNull ValueBuild receiverBuild,
            @NotNull FrontendResolvedCall publishedCall,
            @NotNull List<Expression> arguments
    ) {
        if (!FrontendCallMutabilitySupport.mayMutateReceiver(publishedCall)) {
            return receiverBuild;
        }
        var routePayload = receiverBuild.writableRoutePayloadOrNull();
        if (routePayload == null
                || routePayload.root().kind() != FrontendWritableRoutePayload.RootKind.DIRECT_SLOT
                || routePayload.leaf().kind() != FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT) {
            return receiverBuild;
        }
        if (!(receiverBuild.valueAnchor() instanceof IdentifierExpression || receiverBuild.valueAnchor() instanceof SelfExpression)) {
            return receiverBuild;
        }
        var items = receiverBuild.cursor().currentSequence().items();
        if (!(items.getLast() instanceof OpaqueExprValueItem opaqueValueItem)
                || !opaqueValueItem.resultValueId().equals(receiverBuild.resultValueId())
                || opaqueValueItem.expression() != receiverBuild.valueAnchor()) {
            return receiverBuild;
        }
        var aliasRoot = requireDirectSlotAliasRoot((Expression) receiverBuild.valueAnchor());
        if (!shouldPublishDirectSlotAlias(aliasRoot, arguments)) {
            return receiverBuild;
        }
        items.removeLast();
        return emitDirectSlotAliasValue(
                receiverBuild.cursor(),
                (Expression) receiverBuild.valueAnchor(),
                receiverBuild.resultValueId()
        );
    }

    /// Alias safety is phrased in terms of caller storage semantics, not one hard-coded AST node:
    /// - explicit `self` is always stable because user code cannot rebind the `self` slot
    /// - local/parameter/capture roots only alias when every later argument stays inside a proven
    ///   no-rebinding subset
    /// - anything effect-open or future/unknown falls back to the existing ordinary temp snapshot so
    ///   newly added rebinding forms cannot silently tunnel through alias publication
    private @NotNull DirectSlotAliasRoot requireDirectSlotAliasRoot(@NotNull Expression expression) {
        return switch (expression) {
            case SelfExpression selfExpression -> new DirectSlotAliasRoot(
                    selfExpression,
                    DirectSlotAliasRootKind.EXPLICIT_SELF
            );
            case IdentifierExpression identifierExpression -> {
                var binding = requirePublishedBinding(identifierExpression);
                yield switch (binding.kind()) {
                    case LOCAL_VAR -> new DirectSlotAliasRoot(identifierExpression, DirectSlotAliasRootKind.LOCAL_VAR);
                    case PARAMETER -> new DirectSlotAliasRoot(identifierExpression, DirectSlotAliasRootKind.PARAMETER);
                    case CAPTURE -> throw new IllegalStateException(
                            "Direct-slot alias publication does not support CAPTURE binding before lambda/capture semantics are implemented"
                    );
                    case SELF -> throw new IllegalStateException(
                            "Direct-slot alias publication must use explicit SelfExpression instead of identifier binding kind SELF"
                    );
                    default -> throw new IllegalStateException(
                            "Direct-slot alias publication requires LOCAL_VAR/PARAMETER binding, but got "
                                    + binding.kind()
                    );
                };
            }
            default -> throw new IllegalStateException(
                    "Direct-slot alias publication requires IdentifierExpression or SelfExpression, but got "
                            + expression.getClass().getSimpleName()
            );
        };
    }

    private boolean shouldPublishDirectSlotAlias(
            @NotNull DirectSlotAliasRoot aliasRoot,
            @NotNull List<Expression> arguments
    ) {
        if (aliasRoot.kind() == DirectSlotAliasRootKind.EXPLICIT_SELF) {
            return true;
        }
        return classifyDirectSlotAliasArguments(arguments) == DirectSlotAliasArgumentSafety.SAFE_TO_ALIAS;
    }

    private @NotNull DirectSlotAliasArgumentSafety classifyDirectSlotAliasArguments(
            @NotNull List<Expression> arguments
    ) {
        var safety = DirectSlotAliasArgumentSafety.SAFE_TO_ALIAS;
        for (var argument : arguments) {
            safety = mergeDirectSlotAliasArgumentSafety(safety, classifyDirectSlotAliasArgument(argument));
            if (safety == DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT) {
                return safety;
            }
        }
        return safety;
    }

    /// This classifier is intentionally conservative. Step 6 only aliases identifier-backed receiver
    /// roots across argument evaluation when the entire later subtree is already known to be
    /// no-rebinding for caller direct-slot storage. Current value-level calls are still treated as
    /// effect-open because future lambda/capture/callable surfaces could otherwise start rebinding the
    /// same root through an ordinary-looking `CallExpression` without touching alias publication code.
    private @NotNull DirectSlotAliasArgumentSafety classifyDirectSlotAliasArgument(@NotNull Expression expression) {
        return switch (expression) {
            case IdentifierExpression _, LiteralExpression _, SelfExpression _ ->
                    DirectSlotAliasArgumentSafety.SAFE_TO_ALIAS;
            case UnaryExpression unaryExpression -> classifyDirectSlotAliasArgument(unaryExpression.operand());
            case BinaryExpression binaryExpression -> mergeDirectSlotAliasArgumentSafety(
                    classifyDirectSlotAliasArgument(binaryExpression.left()),
                    classifyDirectSlotAliasArgument(binaryExpression.right())
            );
            case CastExpression castExpression -> classifyDirectSlotAliasArgument(castExpression.value());
            case TypeTestExpression typeTestExpression -> classifyDirectSlotAliasArgument(typeTestExpression.value());
            case ConditionalExpression conditionalExpression -> mergeDirectSlotAliasArgumentSafety(
                    mergeDirectSlotAliasArgumentSafety(
                            classifyDirectSlotAliasArgument(conditionalExpression.condition()),
                            classifyDirectSlotAliasArgument(conditionalExpression.left())
                    ),
                    classifyDirectSlotAliasArgument(conditionalExpression.right())
            );
            case SubscriptExpression subscriptExpression -> mergeDirectSlotAliasArgumentSafety(
                    classifyDirectSlotAliasArgument(subscriptExpression.base()),
                    classifyDirectSlotAliasArguments(subscriptExpression.arguments())
            );
            case AttributeExpression attributeExpression ->
                    classifyDirectSlotAliasAttributeArgument(attributeExpression);
            case AssignmentExpression _, CallExpression _ -> DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT;
            default -> DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT;
        };
    }

    private @NotNull DirectSlotAliasArgumentSafety classifyDirectSlotAliasAttributeArgument(
            @NotNull AttributeExpression attributeExpression
    ) {
        var safety = classifyDirectSlotAliasArgument(attributeExpression.base());
        for (var step : attributeExpression.steps()) {
            safety = mergeDirectSlotAliasArgumentSafety(safety, classifyDirectSlotAliasAttributeStep(step));
            if (safety == DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT) {
                return safety;
            }
        }
        return safety;
    }

    private @NotNull DirectSlotAliasArgumentSafety classifyDirectSlotAliasAttributeStep(
            @NotNull AttributeStep step
    ) {
        return switch (step) {
            case AttributePropertyStep _ -> DirectSlotAliasArgumentSafety.SAFE_TO_ALIAS;
            case AttributeSubscriptStep attributeSubscriptStep ->
                    classifyDirectSlotAliasArguments(attributeSubscriptStep.arguments());
            case AttributeCallStep _ -> DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT;
            default -> DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT;
        };
    }

    private @NotNull DirectSlotAliasArgumentSafety mergeDirectSlotAliasArgumentSafety(
            @NotNull DirectSlotAliasArgumentSafety left,
            @NotNull DirectSlotAliasArgumentSafety right
    ) {
        return left == DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT
                || right == DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT
                ? DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT
                : DirectSlotAliasArgumentSafety.SAFE_TO_ALIAS;
    }

    private @NotNull ValueBuild valueRootBuild(
            @NotNull BuildCursor cursor,
            @NotNull Node valueAnchor,
            @NotNull String resultValueId
    ) {
        return new ValueBuild(cursor, valueAnchor, resultValueId, valueRootRoutePayload(valueAnchor, resultValueId));
    }

    private @NotNull FrontendWritableRoutePayload valueRootRoutePayload(
            @NotNull Node valueAnchor,
            @NotNull String resultValueId
    ) {
        return new FrontendWritableRoutePayload(
                valueAnchor,
                new FrontendWritableRoutePayload.RootDescriptor(
                        FrontendWritableRoutePayload.RootKind.VALUE_ID,
                        valueAnchor,
                        resultValueId
                ),
                new FrontendWritableRoutePayload.LeafDescriptor(
                        FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT,
                        valueAnchor,
                        null,
                        List.of(),
                        null,
                        null
                ),
                List.of()
        );
    }

    private @NotNull FrontendWritableRoutePayload appendPropertyWritableRoute(
            @NotNull ValueBuild receiverBuild,
            @NotNull Node propertyAnchor,
            @NotNull String propertyName
    ) {
        var baseRoute = routePayloadOrValueRoot(receiverBuild);
        return new FrontendWritableRoutePayload(
                propertyAnchor,
                baseRoute.root(),
                new FrontendWritableRoutePayload.LeafDescriptor(
                        FrontendWritableRoutePayload.LeafKind.PROPERTY,
                        propertyAnchor,
                        // Ordinary property reads still use `receiverBuild.resultValueId()` through the
                        // published `MemberLoadItem`. When the base is an unwrapped direct-slot root such
                        // as `self` or `box`, reverse commit must *not* freeze that transient read slot as
                        // the owner; otherwise `box.payloads.push_back(seed)` would try to write the
                        // property back into the snapshot temp instead of the real `box` slot.
                        useImplicitRootContainer(baseRoute) ? null : receiverBuild.resultValueId(),
                        List.of(),
                        StringUtil.requireNonBlank(propertyName, "propertyName"),
                        null
                ),
                appendPromotedLeaf(baseRoute)
        );
    }

    private @NotNull FrontendWritableRoutePayload appendSubscriptWritableRoute(
            @NotNull ValueBuild receiverBuild,
            @NotNull Node subscriptAnchor,
            @NotNull List<String> keyValueIds,
            @NotNull FrontendSubscriptAccessSupport.AccessKind accessKind
    ) {
        var baseRoute = routePayloadOrValueRoot(receiverBuild);
        requireSingleWritableRouteKey(subscriptAnchor, keyValueIds);
        return new FrontendWritableRoutePayload(
                subscriptAnchor,
                baseRoute.root(),
                new FrontendWritableRoutePayload.LeafDescriptor(
                        FrontendWritableRoutePayload.LeafKind.SUBSCRIPT,
                        subscriptAnchor,
                        useImplicitRootContainer(baseRoute) ? null : receiverBuild.resultValueId(),
                        List.copyOf(keyValueIds),
                        subscriptAnchor instanceof AttributeSubscriptStep attributeSubscriptStep
                                ? attributeSubscriptStep.name()
                                : null,
                        Objects.requireNonNull(accessKind, "accessKind must not be null")
                ),
                appendPromotedLeaf(baseRoute)
        );
    }

    private boolean useImplicitRootContainer(@NotNull FrontendWritableRoutePayload routePayload) {
        return routePayload.reverseCommitSteps().isEmpty()
                && routePayload.leaf().kind() == FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT
                && routePayload.root().kind() != FrontendWritableRoutePayload.RootKind.VALUE_ID;
    }

    private @NotNull List<FrontendWritableRoutePayload.StepDescriptor> appendPromotedLeaf(
            @NotNull FrontendWritableRoutePayload routePayload
    ) {
        var steps = new ArrayList<>(routePayload.reverseCommitSteps());
        var promoted = promoteLeafToCommitStep(routePayload.leaf());
        if (promoted != null) {
            steps.add(promoted);
        }
        return List.copyOf(steps);
    }

    private @Nullable FrontendWritableRoutePayload.StepDescriptor promoteLeafToCommitStep(
            @NotNull FrontendWritableRoutePayload.LeafDescriptor leaf
    ) {
        return switch (leaf.kind()) {
            case DIRECT_SLOT -> null;
            case PROPERTY -> new FrontendWritableRoutePayload.StepDescriptor(
                    FrontendWritableRoutePayload.StepKind.PROPERTY,
                    leaf.anchor(),
                    leaf.containerValueIdOrNull(),
                    List.of(),
                    leaf.memberNameOrNull(),
                    null
            );
            case SUBSCRIPT -> new FrontendWritableRoutePayload.StepDescriptor(
                    FrontendWritableRoutePayload.StepKind.SUBSCRIPT,
                    leaf.anchor(),
                    leaf.containerValueIdOrNull(),
                    leaf.operandValueIds(),
                    leaf.memberNameOrNull(),
                    leaf.subscriptAccessKindOrNull()
            );
        };
    }

    private @NotNull FrontendSubscriptAccessSupport.AccessKind determineWritableSubscriptAccessKind(
            @NotNull Node subscriptAnchor,
            @NotNull GdType receiverType,
            @NotNull GdType keyType
    ) {
        return FrontendSubscriptAccessSupport.determineAccessKind(
                subscriptAnchor instanceof AttributeSubscriptStep ? GdVariantType.VARIANT : receiverType,
                keyType
        );
    }

    private void requireSingleWritableRouteKey(@NotNull Node anchor, @NotNull List<String> keyValueIds) {
        if (keyValueIds.size() != 1) {
            throw new IllegalStateException(
                    "Writable-route publication currently supports exactly one subscript key for "
                            + anchor.getClass().getSimpleName()
            );
        }
    }

    private void publishMergedBooleanWriteSequence(
            @NotNull OpenSequence sequence,
            @NotNull BinaryExpression mergeAnchor,
            boolean constantValue,
            @NotNull String mergedResultValueId,
            @NotNull String nextId
    ) {
        var constantValueId = nextValueId();
        sequence.items().add(new BoolConstantItem(mergeAnchor, constantValue, constantValueId));
        // The outward-facing short-circuit result behaves like a merge slot written on mutually
        // exclusive paths, not like one unique SSA expression definition.
        sequence.items().add(new MergeValueItem(mergeAnchor, constantValueId, mergedResultValueId));
        publishSequenceNode(sequence.id(), sequence.items(), nextId);
    }

    private @NotNull String finalizeBlockState(@NotNull BlockState state, @NotNull String continuationId) {
        if (state.reachable()) {
            closeCurrentSequence(state, continuationId);
            return state.entryIdOrNull() == null ? continuationId : state.entryIdOrNull();
        }
        var entryId = state.entryIdOrNull();
        if (entryId == null) {
            throw new IllegalStateException("Structured block terminated before publishing an entry node");
        }
        return entryId;
    }

    private void closeCurrentSequence(@NotNull BlockState state, @NotNull String nextId) {
        var currentSequence = state.currentSequenceOrNull();
        if (currentSequence == null) {
            return;
        }
        if (!currentSequence.items().isEmpty()) {
            publishSequenceNode(currentSequence.id(), currentSequence.items(), nextId);
        } else if (Objects.equals(state.entryIdOrNull(), currentSequence.id())) {
            state.setEntryId(nextId);
        } else {
            publishSequenceNode(currentSequence.id(), List.of(), nextId);
        }
        state.setCurrentSequence(null);
    }

    private @NotNull OpenSequence requireCurrentSequence(@NotNull BlockState state) {
        var currentSequence = state.currentSequenceOrNull();
        if (currentSequence != null) {
            return currentSequence;
        }
        var created = new OpenSequence(nextSequenceId());
        state.setEntryIdIfMissing(created.id());
        state.setCurrentSequence(created);
        return created;
    }

    private @NotNull LinkedHashMap<String, FrontendCfgGraph.NodeDef> orderNodes(@NotNull String entryId) {
        var ordered = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        var visited = new LinkedHashSet<String>();
        visitNode(entryId, visited, ordered);
        for (var entry : requireNodes().entrySet()) {
            ordered.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return ordered;
    }

    private void visitNode(
            @NotNull String nodeId,
            @NotNull LinkedHashSet<String> visited,
            @NotNull LinkedHashMap<String, FrontendCfgGraph.NodeDef> ordered
    ) {
        if (!visited.add(nodeId)) {
            return;
        }
        var node = requireNodes().get(nodeId);
        if (node == null) {
            throw new IllegalStateException("Frontend CFG node has not been published: " + nodeId);
        }
        ordered.put(nodeId, node);
        switch (node) {
            case FrontendCfgGraph.SequenceNode(_, _, var nextId) -> visitNode(nextId, visited, ordered);
            case FrontendCfgGraph.BranchNode(_, _, _, var trueTargetId, var falseTargetId) -> {
                visitNode(trueTargetId, visited, ordered);
                visitNode(falseTargetId, visited, ordered);
            }
            case FrontendCfgGraph.StopNode _ -> {
            }
        }
    }

    private boolean isNodeReferenced(@NotNull String nodeId) {
        for (var node : requireNodes().values()) {
            switch (node) {
                case FrontendCfgGraph.SequenceNode(_, _, var nextId) -> {
                    if (nextId.equals(nodeId)) {
                        return true;
                    }
                }
                case FrontendCfgGraph.BranchNode(_, _, _, var trueTargetId, var falseTargetId) -> {
                    if (trueTargetId.equals(nodeId) || falseTargetId.equals(nodeId)) {
                        return true;
                    }
                }
                case FrontendCfgGraph.StopNode _ -> {
                }
            }
        }
        return false;
    }

    private @NotNull String publishSequenceNode(@NotNull List<SequenceItem> items, @NotNull String nextId) {
        var sequenceId = nextSequenceId();
        publishSequenceNode(sequenceId, items, nextId);
        return sequenceId;
    }

    private void publishSequenceNode(
            @NotNull String sequenceId,
            @NotNull List<SequenceItem> items,
            @NotNull String nextId
    ) {
        requireNodes().put(sequenceId, new FrontendCfgGraph.SequenceNode(sequenceId, items, nextId));
    }

    private @NotNull String publishStopNode(
            @NotNull FrontendCfgGraph.StopKind kind,
            @Nullable String returnValueIdOrNull
    ) {
        var stopId = nextStopId();
        requireNodes().put(stopId, new FrontendCfgGraph.StopNode(stopId, kind, returnValueIdOrNull));
        return stopId;
    }

    private @NotNull FrontendAstSideTable<FrontendCfgRegion> requireRegions() {
        if (regions == null) {
            throw new IllegalStateException("Frontend CFG regions have not been initialized");
        }
        return regions;
    }

    private @NotNull LinkedHashMap<String, FrontendCfgGraph.NodeDef> requireNodes() {
        if (nodes == null) {
            throw new IllegalStateException("Frontend CFG nodes have not been initialized");
        }
        return nodes;
    }

    private @NotNull FrontendAnalysisData requireAnalysisData() {
        if (analysisData == null) {
            throw new IllegalStateException("Frontend analysis data has not been initialized");
        }
        return analysisData;
    }

    private @NotNull LoopFrame requireLoopFrame() {
        var loopFrame = loopStack.peek();
        if (loopFrame == null) {
            throw new IllegalStateException("Loop control statement requires an active loop frame");
        }
        return loopFrame;
    }

    /// Sequence ids are lexical-order scoped to one build so tests can assert exact graph shape
    /// without leaking counters across functions.
    private @NotNull String nextSequenceId() {
        return "seq_" + nextSequenceIndex++;
    }

    private @NotNull String nextBranchId() {
        return "branch_" + nextBranchIndex++;
    }

    /// Stop ids share the same per-function deterministic contract as sequence ids.
    private @NotNull String nextStopId() {
        return "stop_" + nextStopIndex++;
    }

    /// Value ids name frontend-local temporary results referenced by later CFG nodes.
    private @NotNull String nextValueId() {
        return "v" + nextValueIndex++;
    }

    /// Variable initializer ids keep the declaration name as a stable prefix while still sharing the
    /// same monotonic counter as other frontend-local values.
    private @NotNull String nextVariableValueId(@NotNull String variableName) {
        return FrontendCfgGraph.validateNodeId(variableName, "variableName") + "_" + nextValueIndex++;
    }

    private @NotNull String chooseResultValueId(@Nullable String preferredResultValueId) {
        return preferredResultValueId == null ? nextValueId() : preferredResultValueId;
    }

    private static boolean isLogicalNotExpression(@NotNull UnaryExpression unaryExpression) {
        return tryResolveUnaryOperator(unaryExpression.operator()) == GodotOperator.NOT;
    }

    private static boolean isShortCircuitBinaryExpression(@NotNull BinaryExpression binaryExpression) {
        var operator = tryResolveBinaryOperator(binaryExpression.operator());
        return operator == GodotOperator.AND || operator == GodotOperator.OR;
    }

    private static boolean isCompoundAssignmentOperator(@NotNull AssignmentExpression assignmentExpression) {
        return !Objects.requireNonNull(assignmentExpression, "assignmentExpression must not be null")
                .operator()
                .equals("=");
    }

    private static @NotNull String requireCompoundBinaryOperatorLexeme(
            @NotNull AssignmentExpression assignmentExpression
    ) {
        var operatorText = Objects.requireNonNull(assignmentExpression, "assignmentExpression must not be null")
                .operator();
        var binaryOperatorLexeme = switch (operatorText) {
            case "+=" -> "+";
            case "-=" -> "-";
            case "*=" -> "*";
            case "/=" -> "/";
            case "%=" -> "%";
            case "**=" -> "**";
            case ">>=" -> ">>";
            case "<<=" -> "<<";
            case "&=" -> "&";
            case "^=" -> "^";
            case "|=" -> "|";
            default -> null;
        };
        if (binaryOperatorLexeme != null) {
            return binaryOperatorLexeme;
        }
        throw new IllegalStateException(
                "Compound assignment operator '"
                        + operatorText
                        + "' is not recognized by the current frontend CFG read-modify-write contract"
        );
    }

    private void requireLoweringReadyCompoundMemberRead(
            @NotNull AttributePropertyStep attributePropertyStep,
            @NotNull String contractDetail
    ) {
        var publishedMember = requireAnalysisData().resolvedMembers().get(attributePropertyStep);
        if (publishedMember == null) {
            throw new IllegalStateException(
                    "compound-assignment publication contract is missing a lowering-ready member fact for "
                            + "AttributePropertyStep '"
                            + attributePropertyStep.name()
                            + "' during "
                            + contractDetail
            );
        }
        if (publishedMember.status() != FrontendMemberResolutionStatus.RESOLVED
                && publishedMember.status() != FrontendMemberResolutionStatus.DYNAMIC) {
            throw new IllegalStateException(
                    "compound-assignment member read for AttributePropertyStep '"
                            + attributePropertyStep.name()
                            + "' is not lowering-ready during "
                            + contractDetail
                            + ": "
                            + publishedMember.status()
            );
        }
    }

    private static @NotNull GodotOperator requireShortCircuitBinaryOperator(
            @NotNull BinaryExpression binaryExpression
    ) {
        var operator = tryResolveBinaryOperator(binaryExpression.operator());
        if (operator == GodotOperator.AND || operator == GodotOperator.OR) {
            return operator;
        }
        throw unsupportedShortCircuitBinary(binaryExpression);
    }

    private static @Nullable GodotOperator tryResolveUnaryOperator(@NotNull String operatorText) {
        try {
            return GodotOperator.fromSourceLexeme(
                    Objects.requireNonNull(operatorText, "operatorText must not be null"),
                    GodotOperator.OperatorArity.UNARY
            );
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static @Nullable GodotOperator tryResolveBinaryOperator(@NotNull String operatorText) {
        try {
            return GodotOperator.fromSourceLexeme(
                    Objects.requireNonNull(operatorText, "operatorText must not be null"),
                    GodotOperator.OperatorArity.BINARY
            );
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static @NotNull String requireFrozenTargetOperandValue(
            @NotNull List<String> frozenTargetOperandValueIds,
            int index,
            @NotNull Expression targetExpression,
            @NotNull String operandRole
    ) {
        if (index < frozenTargetOperandValueIds.size()) {
            return frozenTargetOperandValueIds.get(index);
        }
        throw new IllegalStateException(
                "Compound assignment target "
                        + targetExpression.getClass().getSimpleName()
                        + " is missing frozen "
                        + operandRole
                        + " operands in the frontend CFG read-modify-write contract"
        );
    }

    private static @NotNull List<String> requireFrozenTargetTrailingOperands(
            @NotNull List<String> frozenTargetOperandValueIds,
            @NotNull Expression targetExpression
    ) {
        if (frozenTargetOperandValueIds.size() >= 2) {
            return List.copyOf(frozenTargetOperandValueIds.subList(1, frozenTargetOperandValueIds.size()));
        }
        throw new IllegalStateException(
                "Compound assignment target "
                        + targetExpression.getClass().getSimpleName()
                        + " is missing frozen index operands in the frontend CFG read-modify-write contract"
        );
    }

    private @NotNull FrontendBinding requirePublishedBinding(@NotNull Node useSite) {
        var binding = requireAnalysisData().symbolBindings().get(Objects.requireNonNull(useSite, "useSite must not be null"));
        if (binding == null) {
            throw new IllegalStateException("Missing published symbol binding for " + useSite.getClass().getSimpleName());
        }
        return binding;
    }

    private boolean isStaticPropertyBinding(@NotNull FrontendBinding binding) {
        return binding.kind() == FrontendBindingKind.PROPERTY
                && binding.declarationSite() instanceof dev.superice.gdcc.scope.PropertyDef propertyDef
                && propertyDef.isStatic();
    }

    /// Writable-route publication must resolve access families and payload leaf types from already
    /// published semantic facts instead of re-running chain reduction.
    ///
    /// Some assignment-target prefixes are still analyzed through the shared semantic surface where a
    /// trusted binding-backed leaf such as `items` may expose slot/property metadata without also
    /// publishing an `expressionTypes()` entry for that identifier use site. Writable-route freezing is
    /// still allowed to consume those binding-backed types because the route stays within the same
    /// compile-checked owner chain and does not reopen general expression inference.
    private @NotNull GdType requireWritableRouteAnchorType(@NotNull Node anchor) {
        var publishedType = requireAnalysisData().expressionTypes().get(Objects.requireNonNull(anchor, "anchor must not be null"));
        if (publishedType != null) {
            if (publishedType.publishedType() != null) {
                return publishedType.publishedType();
            }
            throw new IllegalStateException(
                    "Writable-route publication requires a lowering-ready type for "
                            + anchor.getClass().getSimpleName()
                            + ", but got "
                            + publishedType.status()
            );
        }
        var binding = requireAnalysisData().symbolBindings().get(anchor);
        if (binding != null && binding.declarationSite() instanceof Node declarationNode) {
            var slotType = requireAnalysisData().slotTypes().get(declarationNode);
            if (slotType != null) {
                return slotType;
            }
        }
        if (binding != null
                && binding.kind() == FrontendBindingKind.PROPERTY
                && binding.declarationSite() instanceof dev.superice.gdcc.scope.PropertyDef propertyDef) {
            return propertyDef.getType();
        }
        throw new IllegalStateException(
                "Writable-route publication is missing a published expression type for "
                        + anchor.getClass().getSimpleName()
        );
    }

    /// Compile-ready lowering must only see expressions whose type facts already stabilized to a
    /// lowering-safe state.
    ///
    /// `BLOCKED`, `DEFERRED`, `FAILED`, and `UNSUPPORTED` all indicate the compile gate should have
    /// stopped the pipeline earlier. The builder therefore treats them as protocol violations instead
    /// of trying to recover locally.
    private boolean hasLoweringReadyExpressionType(@NotNull Expression expression) {
        var publishedType = requireAnalysisData().expressionTypes().get(expression);
        return publishedType != null
                && (publishedType.status() == FrontendExpressionTypeStatus.RESOLVED
                || publishedType.status() == FrontendExpressionTypeStatus.DYNAMIC);
    }

    private void requireLoweringReadyExpressionType(@NotNull Expression expression) {
        var publishedType = requireAnalysisData().expressionTypes().get(expression);
        if (publishedType == null) {
            throw new IllegalStateException(
                    "expressionTypes() is missing a lowering-ready fact for "
                            + expression.getClass().getSimpleName()
                            + " at "
                            + expression.range()
            );
        }
        if (publishedType.status() != FrontendExpressionTypeStatus.RESOLVED
                && publishedType.status() != FrontendExpressionTypeStatus.DYNAMIC) {
            throw new IllegalStateException(
                    "Expression "
                            + expression.getClass().getSimpleName()
                            + " is not lowering-ready: "
                            + publishedType.status()
            );
        }
    }

    private @NotNull FrontendResolvedCall requireLoweringReadyCall(@NotNull Node callAnchor) {
        var publishedCall = requireAnalysisData().resolvedCalls().get(callAnchor);
        if (publishedCall == null) {
            throw new IllegalStateException(
                    "resolvedCalls() is missing a lowering-ready fact for " + callAnchor.getClass().getSimpleName()
            );
        }
        if (publishedCall.status() != FrontendCallResolutionStatus.RESOLVED
                && publishedCall.status() != FrontendCallResolutionStatus.DYNAMIC) {
            throw new IllegalStateException(
                    "Call anchor "
                            + callAnchor.getClass().getSimpleName()
                            + " is not lowering-ready: "
                            + publishedCall.status()
            );
        }
        return publishedCall;
    }

    private @NotNull FrontendResolvedMember requireLoweringReadyMember(
            @NotNull AttributePropertyStep attributePropertyStep
    ) {
        var publishedMember = requireAnalysisData().resolvedMembers().get(attributePropertyStep);
        if (publishedMember == null) {
            throw new IllegalStateException(
                    "resolvedMembers() is missing a lowering-ready fact for AttributePropertyStep '"
                            + attributePropertyStep.name()
                            + "'"
            );
        }
        if (publishedMember.status() != FrontendMemberResolutionStatus.RESOLVED
                && publishedMember.status() != FrontendMemberResolutionStatus.DYNAMIC) {
            throw new IllegalStateException(
                    "AttributePropertyStep '"
                            + attributePropertyStep.name()
                            + "' is not lowering-ready: "
                            + publishedMember.status()
            );
        }
        return publishedMember;
    }

    private static @NotNull IllegalStateException unsupportedReachableStatement(@NotNull Statement statement) {
        return new IllegalStateException(
                "Frontend CFG builder reached an unsupported reachable statement: "
                        + statement.getClass().getSimpleName()
        );
    }

    private static @NotNull IllegalStateException unsupportedReachableExpression(@NotNull Expression expression) {
        return new IllegalStateException(
                "Frontend CFG builder reached an unsupported lowering-ready expression: "
                        + expression.getClass().getSimpleName()
        );
    }

    private static @NotNull IllegalStateException unsupportedReachableAssignmentTarget(@NotNull Expression targetExpression) {
        return new IllegalStateException(
                "Frontend CFG builder reached an unsupported assignment target expression: "
                        + targetExpression.getClass().getSimpleName()
        );
    }

    private static @NotNull IllegalStateException unsupportedShortCircuitBinary(
            @NotNull BinaryExpression binaryExpression
    ) {
        return new IllegalStateException(
                "Binary operator '"
                        + binaryExpression.operator()
                        + "' must use the dedicated frontend CFG short-circuit path"
        );
    }

    private static @NotNull IllegalStateException unsupportedConditionalExpression(
            @NotNull ConditionalExpression conditionalExpression
    ) {
        return new IllegalStateException(
                "ConditionalExpression must use the shared frontend CFG branch-result merge path, but that path is not implemented yet at "
                        + conditionalExpression.range()
        );
    }

    private static @NotNull FrontendAstSideTable<FrontendCfgRegion> copyRegions(
            @NotNull FrontendAstSideTable<FrontendCfgRegion> regions
    ) {
        var copied = new FrontendAstSideTable<FrontendCfgRegion>();
        copied.putAll(regions);
        return copied;
    }

    private void initializeBuildState(@NotNull FrontendAnalysisData analysisData) {
        this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
        nodes = new LinkedHashMap<>();
        regions = new FrontendAstSideTable<>();
        loopStack.clear();
        nextSequenceIndex = 0;
        nextBranchIndex = 0;
        nextStopIndex = 0;
        nextValueIndex = 0;
    }

    private @NotNull ExecutableBodyBuild finishBuild(@NotNull String entryId) {
        return new ExecutableBodyBuild(
                new FrontendCfgGraph(entryId, orderNodes(entryId)),
                copyRegions(requireRegions())
        );
    }

    public record ExecutableBodyBuild(
            @NotNull FrontendCfgGraph graph,
            @NotNull FrontendAstSideTable<FrontendCfgRegion> regions
    ) {
        public ExecutableBodyBuild {
            Objects.requireNonNull(graph, "graph must not be null");
            regions = copyRegions(Objects.requireNonNull(regions, "regions must not be null"));
        }
    }

    /// `entryId` freezes the first node of one expression subgraph while `currentSequence` tracks
    /// the currently writable continuation. Linear expressions keep both on the same sequence;
    /// branchy expressions may move only the continuation to a later merge sequence.
    private record BuildCursor(
            @NotNull String entryId,
            @NotNull OpenSequence currentSequence
    ) {
        private BuildCursor(@NotNull OpenSequence currentSequence) {
            this(currentSequence.id(), currentSequence);
        }

        private BuildCursor {
            entryId = FrontendCfgGraph.validateNodeId(entryId, "entryId");
            Objects.requireNonNull(currentSequence, "currentSequence must not be null");
        }
    }

    private record ValueBuild(
            @NotNull BuildCursor cursor,
            @NotNull Node valueAnchor,
            @NotNull String resultValueId,
            @Nullable FrontendWritableRoutePayload writableRoutePayloadOrNull
    ) {
        private ValueBuild {
            Objects.requireNonNull(cursor, "cursor must not be null");
            Objects.requireNonNull(valueAnchor, "valueAnchor must not be null");
            resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
        }
    }

    private record AssignmentTargetBuild(
            @NotNull BuildCursor cursor,
            @NotNull List<String> valueIds,
            @NotNull FrontendWritableRoutePayload writableRoutePayload
    ) {
        private AssignmentTargetBuild {
            Objects.requireNonNull(cursor, "cursor must not be null");
            valueIds = List.copyOf(Objects.requireNonNull(valueIds, "valueIds must not be null"));
            Objects.requireNonNull(
                    writableRoutePayload,
                    "writableRoutePayload must not be null"
            );
        }
    }

    private record ValueListBuild(
            @NotNull BuildCursor cursor,
            @NotNull List<String> valueIds
    ) {
        private ValueListBuild {
            Objects.requireNonNull(cursor, "cursor must not be null");
            valueIds = List.copyOf(Objects.requireNonNull(valueIds, "valueIds must not be null"));
        }
    }

    private record ConditionBuild(
            @NotNull String entryId
    ) {
        private ConditionBuild {
            entryId = FrontendCfgGraph.validateNodeId(entryId, "entryId");
        }
    }

    private record ClauseBuild(
            @NotNull String entryId,
            boolean fallsThrough
    ) {
        private ClauseBuild {
            Objects.requireNonNull(entryId, "entryId must not be null");
        }
    }

    private record BlockBuild(
            @NotNull String entryId,
            boolean fallsThrough
    ) {
        private BlockBuild {
            Objects.requireNonNull(entryId, "entryId must not be null");
        }
    }

    private record OpenSequence(
            @NotNull String id,
            @NotNull ArrayList<SequenceItem> items
    ) {
        private OpenSequence(@NotNull String id) {
            this(id, new ArrayList<>());
        }

        private OpenSequence {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(items, "items must not be null");
        }
    }

    private record LoopFrame(
            @NotNull String continueTargetId,
            @NotNull String breakTargetId
    ) {
        private LoopFrame {
            Objects.requireNonNull(continueTargetId, "continueTargetId must not be null");
            Objects.requireNonNull(breakTargetId, "breakTargetId must not be null");
        }
    }

    private record DirectSlotAliasRoot(
            @NotNull Expression expression,
            @NotNull DirectSlotAliasRootKind kind
    ) {
        private DirectSlotAliasRoot {
            Objects.requireNonNull(expression, "expression must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
        }
    }

    private enum DirectSlotAliasRootKind {
        EXPLICIT_SELF,
        LOCAL_VAR,
        PARAMETER
    }

    private enum DirectSlotAliasArgumentSafety {
        SAFE_TO_ALIAS,
        REQUIRES_SNAPSHOT
    }

    private static final class BlockState {
        private @Nullable String entryId;
        private @Nullable OpenSequence currentSequence;
        private boolean reachable = true;

        private @Nullable String entryIdOrNull() {
            return entryId;
        }

        private void setEntryId(@NotNull String entryId) {
            this.entryId = Objects.requireNonNull(entryId, "entryId must not be null");
        }

        private void setEntryIdIfMissing(@NotNull String entryId) {
            if (this.entryId == null) {
                this.entryId = Objects.requireNonNull(entryId, "entryId must not be null");
            }
        }

        private @Nullable OpenSequence currentSequenceOrNull() {
            return currentSequence;
        }

        private void setCurrentSequence(@Nullable OpenSequence currentSequence) {
            this.currentSequence = currentSequence;
        }

        private boolean reachable() {
            return reachable;
        }

        private void setReachable(boolean reachable) {
            this.reachable = reachable;
        }
    }
}
