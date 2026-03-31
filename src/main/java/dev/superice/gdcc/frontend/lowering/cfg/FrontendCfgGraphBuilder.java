package dev.superice.gdcc.frontend.lowering.cfg;

import dev.superice.gdcc.enums.GodotOperator;
import dev.superice.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CallItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.CastItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.LocalDeclarationItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SequenceItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SourceAnchorItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import dev.superice.gdcc.frontend.lowering.cfg.item.TypeTestItem;
import dev.superice.gdcc.frontend.lowering.cfg.region.FrontendCfgRegion;
import dev.superice.gdcc.frontend.sema.FrontendAnalysisData;
import dev.superice.gdcc.frontend.sema.FrontendCallResolutionStatus;
import dev.superice.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import dev.superice.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/// Frontend CFG builder for the current linear executable-body subset.
///
/// The accepted statement shape is still deliberately small:
/// - empty blocks
/// - `pass`
/// - `ExpressionStatement`
/// - local `var`
/// - `ReturnStatement`
///
/// Within that linear statement surface, the builder now recursively expands value evaluation for
/// call/member/subscript/assignment roots and explicit child operands. Short-circuit `and` / `or`
/// are already reserved for a dedicated control-flow path and therefore remain explicit
/// compile-blockers until that path lands. This keeps sequence item data-flow aligned with source
/// evaluation order without silently reintroducing eager short-circuit bugs.
public final class FrontendCfgGraphBuilder {
    private @Nullable FrontendAnalysisData analysisData;
    private @Nullable ArrayList<SequenceItem> items;
    private int nextSequenceIndex;
    private int nextStopIndex;
    private int nextValueIndex;

    /// Cheap preflight for the default pipeline.
    ///
    /// The build pass uses this helper to decide whether the new linear builder can fully own the
    /// current executable body, or whether the function must temporarily stay on the legacy
    /// metadata-only path for structured control-flow publication.
    ///
    /// The scan intentionally stops at the first reachable `return`. Anything after that point is
    /// lexical remainder and must not veto the current linear shape, because the builder never
    /// attempts to materialize unreachable siblings.
    public static boolean supportsStraightLineExecutableBody(@NotNull Block rootBlock) {
        Objects.requireNonNull(rootBlock, "rootBlock must not be null");
        for (var statement : rootBlock.statements()) {
            if (!isSupportedStraightLineStatement(statement)) {
                return false;
            }
            if (statement instanceof ReturnStatement) {
                return true;
            }
        }
        return true;
    }

    /// Builds the current linear graph for one executable-body root.
    ///
    /// The resulting sequence preserves explicit evaluation order:
    /// - generic expression roots still use `OpaqueExprValueItem`, but now consume any already-built
    ///   child operand ids instead of hiding nested special operations inside one opaque subtree
    /// - bare call / attribute call / property load / subscript / assignment each emit dedicated
    ///   value-op items
    /// - local declaration and assignment commits remain separate from the value computation that
    ///   feeds them
    ///
    /// Reaching an unsupported statement or expression here means the caller bypassed the expected
    /// compile-ready preflight contract, so the builder fails fast instead of publishing a partial
    /// graph.
    public @NotNull ExecutableBodyBuild buildStraightLineExecutableBody(
            @NotNull Block rootBlock,
            @NotNull FrontendAnalysisData analysisData
    ) {
        Objects.requireNonNull(rootBlock, "rootBlock must not be null");
        this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
        items = new ArrayList<>();
        nextSequenceIndex = 0;
        nextStopIndex = 0;
        nextValueIndex = 0;

        String returnValueId = null;
        for (var statement : rootBlock.statements()) {
            switch (statement) {
                case PassStatement passStatement -> requireItems().add(new SourceAnchorItem(passStatement));
                case ExpressionStatement expressionStatement ->
                        buildExpressionStatement(expressionStatement.expression());
                case VariableDeclaration variableDeclaration when variableDeclaration.kind() == DeclarationKind.VAR -> {
                    var initializer = variableDeclaration.value();
                    var initializerValueId = initializer == null
                            ? null
                            : buildValue(initializer, nextVariableValueId(variableDeclaration.name()));
                    requireItems().add(new LocalDeclarationItem(variableDeclaration, initializerValueId));
                }
                case ReturnStatement returnStatement -> {
                    var returnValue = returnStatement.value();
                    if (returnValue != null) {
                        returnValueId = buildValue(returnValue, null);
                    }
                    var graph = buildGraph(requireItems(), returnValueId);
                    return new ExecutableBodyBuild(graph, new FrontendCfgRegion.BlockRegion(graph.entryNodeId()));
                }
                default -> throw unsupportedReachableStatement(statement);
            }
        }

        var graph = buildGraph(requireItems(), null);
        return new ExecutableBodyBuild(graph, new FrontendCfgRegion.BlockRegion(graph.entryNodeId()));
    }

    /// Expression statements either discard one ordinary value result or commit an assignment.
    ///
    /// Assignment roots stay as commit-only items because the current compile-ready surface does not
    /// allow assignment expressions to flow into an outer value consumer.
    private void buildExpressionStatement(@NotNull Expression expression) {
        requireLoweringReadyExpressionType(expression);
        switch (expression) {
            case AssignmentExpression assignmentExpression -> buildAssignmentCommit(assignmentExpression);
            default -> buildValue(expression, null);
        }
    }

    /// Recursively materializes one lowering-ready value and returns the published frontend-local id.
    ///
    /// The builder deliberately special-cases only operations whose semantics later lowering must not
    /// rediscover from raw AST alone. Generic expressions still use `OpaqueExprValueItem`, but only
    /// after all of their lowering-ready children have already published explicit operand ids.
    /// Short-circuit `and` / `or` no longer share that generic eager route; they are intercepted by
    /// a dedicated unimplemented path so compile-blocked sources cannot accidentally regress into one
    /// linear binary item.
    private @NotNull String buildValue(@NotNull Expression expression, @Nullable String preferredResultValueId) {
        requireLoweringReadyExpressionType(expression);
        return switch (expression) {
            case AssignmentExpression _ -> throw new IllegalStateException(
                    "Assignment expressions do not produce a lowering-ready value in the current compile surface"
            );
            case AttributeExpression attributeExpression -> buildAttributeExpressionValue(
                    attributeExpression,
                    preferredResultValueId
            );
            case CallExpression callExpression -> buildBareCallValue(callExpression, preferredResultValueId);
            case SubscriptExpression subscriptExpression -> buildPlainSubscriptValue(
                    subscriptExpression,
                    preferredResultValueId
            );
            case CastExpression castExpression -> buildCastValue(castExpression, preferredResultValueId);
            case TypeTestExpression typeTestExpression ->
                    buildTypeTestValue(typeTestExpression, preferredResultValueId);
            case UnaryExpression unaryExpression -> emitOpaqueValue(
                    unaryExpression,
                    List.of(buildValue(unaryExpression.operand(), null)),
                    preferredResultValueId
            );
            case BinaryExpression binaryExpression when isShortCircuitBinaryExpression(binaryExpression) ->
                    buildShortCircuitBinaryValue(binaryExpression);
            case BinaryExpression binaryExpression -> emitOpaqueValue(
                    binaryExpression,
                    List.of(
                            buildValue(binaryExpression.left(), null),
                            buildValue(binaryExpression.right(), null)
                    ),
                    preferredResultValueId
            );
            case IdentifierExpression _, LiteralExpression _, SelfExpression _ -> emitOpaqueValue(
                    expression,
                    List.of(),
                    preferredResultValueId
            );
            default -> throw unsupportedReachableExpression(expression);
        };
    }

    /// Attribute chains are expanded step by step so later lowering receives explicit intermediate
    /// value ids instead of having to rerun chain reduction over the full outer expression root.
    private @NotNull String buildAttributeExpressionValue(
            @NotNull AttributeExpression attributeExpression,
            @Nullable String preferredResultValueId
    ) {
        if (attributeExpression.steps().isEmpty()) {
            throw new IllegalStateException("AttributeExpression must contain at least one step");
        }
        var currentValueId = buildValue(attributeExpression.base(), null);
        for (var stepIndex = 0; stepIndex < attributeExpression.steps().size(); stepIndex++) {
            var step = attributeExpression.steps().get(stepIndex);
            currentValueId = applyAttributeStep(
                    currentValueId,
                    step,
                    stepIndex + 1 == attributeExpression.steps().size() ? preferredResultValueId : null
            );
        }
        return currentValueId;
    }

    /// Bare call lowering consumes the published `resolvedCalls()` fact directly.
    ///
    /// The current compile-ready contract only permits bare/global/static calls here. Callable-value
    /// invocation remains a separate future route, so a non-identifier callee is treated as a
    /// protocol violation instead of being silently dropped from operand ordering.
    private @NotNull String buildBareCallValue(
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
        var argumentValueIds = buildArgumentValues(callExpression.arguments());
        var resultValueId = chooseResultValueId(preferredResultValueId);
        requireItems().add(new CallItem(
                callExpression,
                publishedCall.callableName(),
                null,
                argumentValueIds,
                resultValueId
        ));
        return resultValueId;
    }

    /// Plain subscripts first materialize their base and arguments, then commit one explicit indexed
    /// read item that consumes those operand ids.
    private @NotNull String buildPlainSubscriptValue(
            @NotNull SubscriptExpression subscriptExpression,
            @Nullable String preferredResultValueId
    ) {
        var baseValueId = buildValue(subscriptExpression.base(), null);
        var argumentValueIds = buildArgumentValues(subscriptExpression.arguments());
        var resultValueId = chooseResultValueId(preferredResultValueId);
        requireItems().add(new SubscriptLoadItem(
                subscriptExpression,
                null,
                baseValueId,
                argumentValueIds,
                resultValueId
        ));
        return resultValueId;
    }

    /// Cast expressions are still compile-blocked by the default pipeline, but the item contract is
    /// already wired so targeted builder tests and later compile-surface expansion do not require a
    /// structural rewrite of the linear graph model.
    private @NotNull String buildCastValue(
            @NotNull CastExpression castExpression,
            @Nullable String preferredResultValueId
    ) {
        var operandValueId = buildValue(castExpression.value(), null);
        var resultValueId = chooseResultValueId(preferredResultValueId);
        requireItems().add(new CastItem(
                castExpression,
                operandValueId,
                resultValueId
        ));
        return resultValueId;
    }

    /// Type-test expressions share the same “child first, then one explicit result item” contract as
    /// casts. They remain compile-blocked today, but the CFG item surface is ready for that future
    /// migration.
    private @NotNull String buildTypeTestValue(
            @NotNull TypeTestExpression typeTestExpression,
            @Nullable String preferredResultValueId
    ) {
        var operandValueId = buildValue(typeTestExpression.value(), null);
        var resultValueId = chooseResultValueId(preferredResultValueId);
        requireItems().add(new TypeTestItem(
                typeTestExpression,
                operandValueId,
                resultValueId
        ));
        return resultValueId;
    }

    /// `and` / `or` never belong to the generic eager binary route.
    ///
    /// Their final lowering must allocate a shared result slot, branch on the left operand, and only
    /// materialize the right operand on the non-short-circuit path. The current linear-only builder
    /// cannot express that graph shape yet, so reaching this helper means the compile gate was
    /// bypassed and we must fail before any child operand is eagerly lowered.
    private @NotNull String buildShortCircuitBinaryValue(@NotNull BinaryExpression binaryExpression) {
        throw new IllegalStateException(
                "Binary operator '"
                        + binaryExpression.operator()
                        + "' must use the dedicated frontend CFG short-circuit path, but that path is not implemented yet"
        );
    }

    /// Assignment commit preserves target evaluation before RHS evaluation.
    ///
    /// The returned operand list on `AssignmentItem` therefore starts with any already-evaluated
    /// target receiver/index operands, then appends the RHS value id as the final consumed operand.
    private void buildAssignmentCommit(@NotNull AssignmentExpression assignmentExpression) {
        var targetOperandValueIds = buildAssignmentTargetOperands(assignmentExpression.left());
        var rhsValueId = buildValue(assignmentExpression.right(), null);
        requireItems().add(new AssignmentItem(
                assignmentExpression,
                targetOperandValueIds,
                rhsValueId,
                null
        ));
    }

    /// Builds the already-evaluated operands required to commit one assignment target.
    ///
    /// The target AST itself remains on `AssignmentItem`, but any child expressions with real
    /// evaluation order, such as chain prefixes or subscript arguments, are materialized here so
    /// later lowering does not need to recurse back into the target subtree to discover them.
    private @NotNull List<String> buildAssignmentTargetOperands(@NotNull Expression targetExpression) {
        return switch (targetExpression) {
            case IdentifierExpression _ -> List.of();
            case AttributeExpression attributeExpression -> buildAttributeTargetOperands(attributeExpression);
            case SubscriptExpression subscriptExpression -> {
                var operands = new ArrayList<String>(1 + subscriptExpression.arguments().size());
                operands.add(buildAssignmentTargetValue(subscriptExpression.base()));
                operands.addAll(buildArgumentValues(subscriptExpression.arguments()));
                yield List.copyOf(operands);
            }
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
    private @NotNull List<String> buildAttributeTargetOperands(@NotNull AttributeExpression attributeExpression) {
        if (attributeExpression.steps().isEmpty()) {
            throw new IllegalStateException("AttributeExpression assignment target must contain at least one step");
        }

        var currentValueId = buildAssignmentTargetValue(attributeExpression.base());
        for (var stepIndex = 0; stepIndex + 1 < attributeExpression.steps().size(); stepIndex++) {
            currentValueId = applyAttributeStep(currentValueId, attributeExpression.steps().get(stepIndex), null);
        }

        var finalStep = attributeExpression.steps().getLast();
        return switch (finalStep) {
            case AttributePropertyStep _ -> List.of(currentValueId);
            case AttributeSubscriptStep attributeSubscriptStep -> {
                var operands = new ArrayList<String>(1 + attributeSubscriptStep.arguments().size());
                operands.add(currentValueId);
                operands.addAll(buildArgumentValues(attributeSubscriptStep.arguments()));
                yield List.copyOf(operands);
            }
            default -> throw new IllegalStateException(
                    "Assignment target step '"
                            + finalStep.getClass().getSimpleName()
                            + "' is not supported by the current linear frontend CFG contract"
            );
        };
    }

    /// Assignment-target prefixes are not always part of the ordinary published `expressionTypes()`
    /// surface.
    ///
    /// For example, the container base of `items[idx] = rhs` may only be visited through assignment
    /// target analysis, so the builder cannot require a normal expression-type entry before it
    /// materializes the receiver value. This helper therefore falls back to a target-specific value
    /// path for those prefixes while still reusing ordinary `buildValue(...)` whenever a lowering-ready
    /// expression fact actually exists.
    private @NotNull String buildAssignmentTargetValue(@NotNull Expression expression) {
        if (hasLoweringReadyExpressionType(expression)) {
            return buildValue(expression, null);
        }
        return switch (expression) {
            case IdentifierExpression _, SelfExpression _ -> emitOpaqueValue(expression, List.of(), null);
            case AttributeExpression attributeExpression -> buildAssignmentTargetAttributeValue(attributeExpression);
            case SubscriptExpression subscriptExpression -> buildAssignmentTargetSubscriptValue(subscriptExpression);
            case CallExpression callExpression -> buildBareCallValue(callExpression, null);
            default -> throw new IllegalStateException(
                    "Assignment target value "
                            + expression.getClass().getSimpleName()
                            + " is missing a lowering-ready expression fact"
            );
        };
    }

    private @NotNull String buildAssignmentTargetAttributeValue(@NotNull AttributeExpression attributeExpression) {
        if (attributeExpression.steps().isEmpty()) {
            throw new IllegalStateException("AttributeExpression target value must contain at least one step");
        }
        var currentValueId = buildAssignmentTargetValue(attributeExpression.base());
        for (var step : attributeExpression.steps()) {
            currentValueId = applyAttributeStep(currentValueId, step, null);
        }
        return currentValueId;
    }

    private @NotNull String buildAssignmentTargetSubscriptValue(@NotNull SubscriptExpression subscriptExpression) {
        var baseValueId = buildAssignmentTargetValue(subscriptExpression.base());
        var argumentValueIds = buildArgumentValues(subscriptExpression.arguments());
        var resultValueId = chooseResultValueId(null);
        requireItems().add(new SubscriptLoadItem(
                subscriptExpression,
                null,
                baseValueId,
                argumentValueIds,
                resultValueId
        ));
        return resultValueId;
    }

    /// Applies one attribute-chain step to the current receiver value and returns the produced value id.
    ///
    /// The step kind decides which explicit item is emitted, but the overall contract stays uniform:
    /// the receiver value id arrives from the previous chain segment, step-local arguments are built
    /// before the item is appended, and the returned value id becomes the receiver for the next step.
    private @NotNull String applyAttributeStep(
            @NotNull String receiverValueId,
            @NotNull AttributeStep step,
            @Nullable String preferredResultValueId
    ) {
        return switch (step) {
            case AttributePropertyStep attributePropertyStep -> {
                var publishedMember = requireLoweringReadyMember(attributePropertyStep);
                var resultValueId = chooseResultValueId(preferredResultValueId);
                requireItems().add(new MemberLoadItem(
                        attributePropertyStep,
                        publishedMember.memberName(),
                        receiverValueId,
                        resultValueId
                ));
                yield resultValueId;
            }
            case AttributeCallStep attributeCallStep -> {
                var publishedCall = requireLoweringReadyCall(attributeCallStep);
                var argumentValueIds = buildArgumentValues(attributeCallStep.arguments());
                var resultValueId = chooseResultValueId(preferredResultValueId);
                requireItems().add(new CallItem(
                        attributeCallStep,
                        publishedCall.callableName(),
                        receiverValueId,
                        argumentValueIds,
                        resultValueId
                ));
                yield resultValueId;
            }
            case AttributeSubscriptStep attributeSubscriptStep -> {
                var argumentValueIds = buildArgumentValues(attributeSubscriptStep.arguments());
                var resultValueId = chooseResultValueId(preferredResultValueId);
                requireItems().add(new SubscriptLoadItem(
                        attributeSubscriptStep,
                        attributeSubscriptStep.name(),
                        receiverValueId,
                        argumentValueIds,
                        resultValueId
                ));
                yield resultValueId;
            }
            default -> throw new IllegalStateException(
                    "Unsupported attribute step in linear frontend CFG builder: " + step.getClass().getSimpleName()
            );
        };
    }

    private @NotNull List<String> buildArgumentValues(@NotNull List<Expression> arguments) {
        var valueIds = new ArrayList<String>(arguments.size());
        for (var argument : arguments) {
            valueIds.add(buildValue(argument, null));
        }
        return List.copyOf(valueIds);
    }

    /// Generic opaque items still exist as a bridge for simple expression forms whose exact lowering
    /// will be finalized later, but they no longer hide nested child evaluation order.
    private @NotNull String emitOpaqueValue(
            @NotNull Expression expression,
            @NotNull List<String> operandValueIds,
            @Nullable String preferredResultValueId
    ) {
        var resultValueId = chooseResultValueId(preferredResultValueId);
        requireItems().add(new OpaqueExprValueItem(expression, operandValueIds, resultValueId));
        return resultValueId;
    }

    /// Materializes the minimal linear topology.
    ///
    /// Keeping this in one helper ensures the entry sequence, terminal stop, and deterministic node
    /// ids stay uniform across empty functions, fallthrough functions, and early-return functions.
    private @NotNull FrontendCfgGraph buildGraph(
            @NotNull List<SequenceItem> items,
            @Nullable String returnValueIdOrNull
    ) {
        var entryId = nextSequenceId();
        var stopId = nextStopId();
        var nodes = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>(2);
        nodes.put(entryId, new FrontendCfgGraph.SequenceNode(entryId, items, stopId));
        nodes.put(stopId, new FrontendCfgGraph.StopNode(stopId, returnValueIdOrNull));
        return new FrontendCfgGraph(entryId, nodes);
    }

    private @NotNull ArrayList<SequenceItem> requireItems() {
        if (items == null) {
            throw new IllegalStateException("Sequence item buffer has not been initialized");
        }
        return items;
    }

    private @NotNull FrontendAnalysisData requireAnalysisData() {
        if (analysisData == null) {
            throw new IllegalStateException("Frontend analysis data has not been initialized");
        }
        return analysisData;
    }

    /// Sequence ids are lexical-order scoped to one build so tests can assert exact graph shape
    /// without leaking counters across functions.
    private @NotNull String nextSequenceId() {
        return "seq_" + nextSequenceIndex++;
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

    private static boolean isShortCircuitBinaryExpression(@NotNull BinaryExpression binaryExpression) {
        var operator = tryResolveBinaryOperator(binaryExpression.operator());
        return operator == GodotOperator.AND || operator == GodotOperator.OR;
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

    /// Returns whether the given statement can still be represented by the current single-sequence
    /// model before the first reachable terminator.
    private static boolean isSupportedStraightLineStatement(@NotNull Statement statement) {
        return switch (statement) {
            case PassStatement _, ExpressionStatement _, ReturnStatement _ -> true;
            case VariableDeclaration variableDeclaration -> variableDeclaration.kind() == DeclarationKind.VAR;
            default -> false;
        };
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

    private @NotNull dev.superice.gdcc.frontend.sema.FrontendResolvedCall requireLoweringReadyCall(@NotNull Node callAnchor) {
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

    private @NotNull dev.superice.gdcc.frontend.sema.FrontendResolvedMember requireLoweringReadyMember(
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

    /// Reaching this path means a caller asked the linear builder to materialize a statement that
    /// needs a richer CFG shape than the current executable-body contract provides.
    private static @NotNull IllegalStateException unsupportedReachableStatement(@NotNull Statement statement) {
        return new IllegalStateException(
                "Linear frontend CFG builder reached an unsupported reachable statement: "
                        + statement.getClass().getSimpleName()
        );
    }

    private static @NotNull IllegalStateException unsupportedReachableExpression(@NotNull Expression expression) {
        return new IllegalStateException(
                "Linear frontend CFG builder reached an unsupported lowering-ready expression: "
                        + expression.getClass().getSimpleName()
        );
    }

    private static @NotNull IllegalStateException unsupportedReachableAssignmentTarget(@NotNull Expression targetExpression) {
        return new IllegalStateException(
                "Linear frontend CFG builder reached an unsupported assignment target expression: "
                        + targetExpression.getClass().getSimpleName()
        );
    }

    /// Build product for one executable-body root.
    ///
    /// The builder returns both artifacts together because later passes typically need:
    /// - the graph itself for node/value traversal
    /// - the root `BlockRegion` for AST-keyed region publication back into `FunctionLoweringContext`
    public record ExecutableBodyBuild(
            @NotNull FrontendCfgGraph graph,
            @NotNull FrontendCfgRegion.BlockRegion rootRegion
    ) {
        public ExecutableBodyBuild {
            Objects.requireNonNull(graph, "graph must not be null");
            Objects.requireNonNull(rootRegion, "rootRegion must not be null");
        }
    }
}
