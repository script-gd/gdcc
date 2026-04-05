package dev.superice.gdcc.frontend.lowering.cfg.item;

import dev.superice.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import dev.superice.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/// Explicit value-producing read-modify-write step for compound assignment.
///
/// `AssignmentItem` keeps representing only the final store commit. This item freezes the
/// computation that must happen before that commit:
/// - read the current target value from already-frozen receiver/index operands
/// - combine it with the already-evaluated RHS through one binary operator
/// - publish the computed result value id that the later `AssignmentItem` writes back
///
/// Keeping this as its own CFG item prevents body lowering from having to rediscover compound
/// assignment semantics from the original assignment AST.
public record CompoundAssignmentBinaryOpItem(
        @NotNull AssignmentExpression assignment,
        @NotNull String binaryOperatorLexeme,
        @NotNull String currentTargetValueId,
        @NotNull String rhsValueId,
        @NotNull String resultValueId
) implements ValueOpItem {
    public CompoundAssignmentBinaryOpItem {
        Objects.requireNonNull(assignment, "assignment must not be null");
        binaryOperatorLexeme = StringUtil.requireNonBlank(binaryOperatorLexeme, "binaryOperatorLexeme");
        currentTargetValueId = FrontendCfgGraph.validateValueId(currentTargetValueId, "currentTargetValueId");
        rhsValueId = FrontendCfgGraph.validateValueId(rhsValueId, "rhsValueId");
        resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
    }

    @Override
    public @NotNull Node anchor() {
        return assignment;
    }

    @Override
    public @NotNull String resultValueIdOrNull() {
        return resultValueId;
    }

    @Override
    public @NotNull List<String> operandValueIds() {
        return List.of(currentTargetValueId, rhsValueId);
    }
}
